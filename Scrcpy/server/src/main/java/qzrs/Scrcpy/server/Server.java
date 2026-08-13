/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import qzrs.Scrcpy.server.entity.Device;
import qzrs.Scrcpy.server.entity.Options;
import qzrs.Scrcpy.server.helper.AudioEncode;
import qzrs.Scrcpy.server.helper.ControlPacket;
import qzrs.Scrcpy.server.helper.VideoEncode;
import qzrs.Scrcpy.server.wrappers.ClipboardManager;
import qzrs.Scrcpy.server.wrappers.DisplayManager;
import qzrs.Scrcpy.server.wrappers.InputManager;
import qzrs.Scrcpy.server.wrappers.SurfaceControl;
import qzrs.Scrcpy.server.wrappers.WindowManager;

// 此部分代码摘抄借鉴了著名投屏软件Scrcpy的开源代码(https://github.com/Genymobile/scrcpy/tree/master/server)
public final class Server {
  private static Socket mainSocket;
  private static Socket videoSocket;
  private static OutputStream mainOutputStream;
  private static OutputStream videoOutputStream;
  public static DataInputStream mainInputStream;

  // 视频 UDP 通道（直连模式下视频走 UDP，控制/心跳仍走 TCP main 通道）
  private static ServerSocket serverSocket;
  private static DatagramSocket videoUdpSocket;
  private static SocketAddress clientUdpAddress;
  private static int videoUdpPort;
  private static int videoFrameSeq = 0;
  private static final int UDP_CHUNK = 1400;   // 单分片最大载荷（远低于常见 MTU 1472）
  private static final int UDP_HEADER = 9;      // type(1)+seq(4)+fragIdx(2)+fragTotal(2)
  private static final Object udpReadyLock = new Object();

  private static final Object object = new Object();

  private static final int timeoutDelay = 1000 * 20;

  public static void main(String... args) {
    try {
      Thread timeOutThread = new Thread(() -> {
        try {
          Thread.sleep(timeoutDelay);
          release();
        } catch (InterruptedException ignored) {
        }
      });
      timeOutThread.start();
      // 解析参数
      Options.parse(args);
      // 初始化
      setManagers();
      Device.init();
      // 连接
      connectClient();
      // 初始化子服务
      boolean canAudio = AudioEncode.init();
      VideoEncode.init();
      // 启动
      ArrayList<Thread> threads = new ArrayList<>();
      threads.add(new Thread(Server::executeVideoOut));
      if (canAudio) {
        threads.add(new Thread(Server::executeAudioIn));
        threads.add(new Thread(Server::executeAudioOut));
      }
      threads.add(new Thread(Server::executeControlIn));
      for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
      for (Thread thread : threads) thread.start();
      // 程序运行
      timeOutThread.interrupt();
      synchronized (object) {
        object.wait();
      }
      // 终止子服务
      for (Thread thread : threads) thread.interrupt();
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      // 释放资源
      release();
    }
  }

  private static Method GET_SERVICE_METHOD;

  @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
  private static void setManagers() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
    // 1
    WindowManager.init(getService("window", "android.view.IWindowManager"));
    // 2
    DisplayManager.init(Class.forName("android.hardware.display.DisplayManagerGlobal").getDeclaredMethod("getInstance").invoke(null));
    // 3
    Class<?> inputManagerClass;
    try {
      inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
    } catch (ClassNotFoundException e) {
      inputManagerClass = android.hardware.input.InputManager.class;
    }
    InputManager.init(inputManagerClass.getDeclaredMethod("getInstance").invoke(null));
    // 4
    ClipboardManager.init(getService("clipboard", "android.content.IClipboard"));
    // 5
    SurfaceControl.init();
  }

  private static IInterface getService(String service, String type) {
    try {
      IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
      Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
      return (IInterface) asInterfaceMethod.invoke(null, binder);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void connectClient() throws IOException {
    serverSocket = new ServerSocket(Options.serverPort);
    mainSocket = serverSocket.accept();
    mainOutputStream = mainSocket.getOutputStream();
    mainInputStream = new DataInputStream(mainSocket.getInputStream());
    // 关闭TCP的Nagle算法，避免小包缓冲
    mainSocket.setTcpNoDelay(true);

    // 视频 UDP 端口 = TCP 控制端口 + 1，仅在直连模式下启用
    videoUdpPort = Options.serverPort + 1;
    videoUdpSocket = new DatagramSocket(videoUdpPort);

    // 握手线程：接收客户端首个 UDP 包并记录其地址（直连 UDP 模式下由客户端主动上报）
    new Thread(() -> {
      try {
        byte[] buf = new byte[64];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        videoUdpSocket.receive(packet);
        synchronized (udpReadyLock) {
          clientUdpAddress = packet.getSocketAddress();
          udpReadyLock.notifyAll();
        }
      } catch (Exception ignored) {
      }
    }, "udp-video-handshake").start();

    // 兼容 adb / USB 模式：保留 TCP 视频通道（该模式连接时会连第二个 TCP）
    new Thread(() -> {
      try {
        videoSocket = serverSocket.accept();
        videoOutputStream = videoSocket.getOutputStream();
      } catch (Exception ignored) {
      }
    }, "tcp-video-accept").start();
  }

  private static void executeVideoOut() {
    try {
      int frame = 0;
      while (!Thread.interrupted()) {
        if (VideoEncode.isHasChangeConfig) {
          VideoEncode.isHasChangeConfig = false;
          VideoEncode.stopEncode();
          VideoEncode.startEncode();
        }
        VideoEncode.encodeOut();
        frame++;
        if (frame > 120) {
          if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay) throw new IOException("连接断开");
          frame = 0;
        }
      }
    } catch (Exception e) {
      errorClose(e);
    }
  }

  private static void executeAudioIn() {
    while (!Thread.interrupted()) AudioEncode.encodeIn();
  }

  private static void executeAudioOut() {
    try {
      while (!Thread.interrupted()) AudioEncode.encodeOut();
    } catch (Exception e) {
      errorClose(e);
    }
  }

  private static long lastKeepAliveTime = System.currentTimeMillis();

  private static void executeControlIn() {
    try {
      while (!Thread.interrupted()) {
        switch (Server.mainInputStream.readByte()) {
          case 1:
            ControlPacket.handleTouchEvent();
            break;
          case 2:
            ControlPacket.handleKeyEvent();
            break;
          case 3:
            ControlPacket.handleClipboardEvent();
            break;
          case 4:
            lastKeepAliveTime = System.currentTimeMillis();
            // 收到心跳包，原样返回，用于客户端计算RTT往返延迟
            mainOutputStream.write(new byte[]{4});
            // 强制flush，立刻发送，避免TCP缓冲
            mainOutputStream.flush();
            break;
          case 5:
            Device.changeResolution(mainInputStream.readFloat());
            break;
          case 6:
            Device.rotateDevice();
            break;
          case 7:
            Device.changeScreenPowerMode(mainInputStream.readByte());
            break;
          case 8:
            Device.changePower(mainInputStream.readInt());
            break;
          case 9:
            Device.changeResolution(mainInputStream.readInt(), mainInputStream.readInt());
            break;
          case 10:
            // 客户端 UDP 视频丢帧，请求服务端输出关键帧(IDR)以快速恢复
            VideoEncode.requestSync();
            break;
        }
      }
    } catch (Exception e) {
      errorClose(e);
    }
  }

  public synchronized static void writeMain(ByteBuffer byteBuffer) throws IOException {
    mainOutputStream.write(byteBuffer.array());
  }

  public static void writeVideo(ByteBuffer byteBuffer) throws IOException {
    if (clientUdpAddress == null) ensureUdpClient();
    if (clientUdpAddress != null) {
      // 直连模式：视频走 UDP，按 MTU 分片发送
      sendUdpVideo(byteBuffer);
    } else if (videoOutputStream != null) {
      // adb / USB 模式：视频仍走 TCP（adb 不支持 UDP 转发）
      videoOutputStream.write(byteBuffer.array());
    }
    // 两者皆未就绪（首帧握手未完成且无 TCP 视频连接）时丢弃该帧，由 IDR 机制恢复
  }

  // 等待客户端 UDP 握手（带超时），避免首帧在握手到达前被丢弃
  private static void ensureUdpClient() {
    synchronized (udpReadyLock) {
      long deadline = System.currentTimeMillis() + 2000;
      while (clientUdpAddress == null && System.currentTimeMillis() < deadline) {
        try {
          udpReadyLock.wait(deadline - System.currentTimeMillis());
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  // 将一条视频记录按 MTU 分片，封装帧头后通过 UDP 发送
  private static void sendUdpVideo(ByteBuffer byteBuffer) throws IOException {
    byte[] data = byteBuffer.array();
    int total = data.length;
    int fragTotal = (total + UDP_CHUNK - 1) / UDP_CHUNK;
    if (fragTotal == 0) fragTotal = 1;
    int seq = videoFrameSeq++;
    int offset = 0;
    for (int fragIdx = 0; fragIdx < fragTotal; fragIdx++) {
      int len = Math.min(UDP_CHUNK, total - offset);
      ByteBuffer packet = ByteBuffer.allocate(UDP_HEADER + len).order(ByteOrder.BIG_ENDIAN);
      packet.put((byte) 0);              // type: 视频数据
      packet.putInt(seq);
      packet.putShort((short) fragIdx);
      packet.putShort((short) fragTotal);
      packet.put(data, offset, len);
      packet.flip();
      videoUdpSocket.send(new DatagramPacket(packet.array(), packet.limit(), clientUdpAddress));
      offset += len;
    }
  }

  public static void errorClose(Exception e) {
    e.printStackTrace();
    synchronized (object) {
      object.notify();
    }
  }

  // 释放资源
  private static void release() {
    for (int i = 0; i < 4; i++) {
      try {
        switch (i) {
          case 0:
            mainInputStream.close();
            mainSocket.close();
            videoSocket.close();
            if (videoUdpSocket != null) videoUdpSocket.close();
            if (serverSocket != null) serverSocket.close();
            break;
          case 1:
            VideoEncode.release();
            AudioEncode.release();
            break;
          case 2:
            Device.fallbackResolution();
            Device.fallbackScreenLightTimeout();
          case 3:
            Runtime.getRuntime().exit(0);
            break;
        }
      } catch (Exception ignored) {
      }
    }
  }

}
