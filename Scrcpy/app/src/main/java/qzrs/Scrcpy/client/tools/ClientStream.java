package qzrs.Scrcpy.client.tools;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

import qzrs.Scrcpy.BuildConfig;
import qzrs.Scrcpy.R;
import qzrs.Scrcpy.adb.Adb;
import qzrs.Scrcpy.buffer.BufferStream;
import qzrs.Scrcpy.client.decode.DecodecTools;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;
import qzrs.Scrcpy.helper.Logger;
import qzrs.Scrcpy.helper.PublicTools;

public class ClientStream {
  protected boolean isClose = false;
  protected boolean connectDirect = false;
  protected Adb adb;
  protected Socket mainSocket;
  protected Socket videoSocket;
  protected OutputStream mainOutputStream;
  protected DataInputStream mainDataInputStream;
  protected DataInputStream videoDataInputStream;
  protected BufferStream mainBufferStream;
  protected BufferStream videoBufferStream;
  protected BufferStream shell;
  protected Thread connectThread = null;
  private static final String serverName = "/data/local/tmp/scrcpy_server_" + BuildConfig.VERSION_CODE + ".jar";
  private static final boolean supportH265 = DecodecTools.isSupportH265();
  private static final boolean supportOpus = DecodecTools.isSupportOpus();

  private static final int timeoutDelay = 1000 * 15;

  // 统计信息覆盖层
  private final StatsOverlay statsOverlay = new StatsOverlay();

  // 心跳包发送时间戳，用于计算RTT
  public long pingSendTime = 0;

  public StatsOverlay getStatsOverlay() {
    return statsOverlay;
  }

  public ClientStream() {
    // Empty constructor for inheritance
  }

  public ClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
    Logger.i("ClientStream", "========== 开始连接 ==========");
    Logger.logConnection("设备信息", "uuid=" + device.uuid + ", address=" + device.address + ", serverPort=" + device.serverPort + ", useUdpMode=" + device.useUdpMode);
    
    Thread timeOutThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        Logger.e("ClientStream", "连接超时!");
        PublicTools.logToast("stream", AppData.applicationContext.getString(R.string.toast_timeout), true);
        handle.run(false);
        if (connectThread != null) connectThread.interrupt();
      } catch (InterruptedException ignored) {
      }
    });
    connectThread = new Thread(() -> {
      try {
        Logger.logConnection("ADB连接", "正在连接ADB...");
        adb = AdbTools.connectADB(device);
        Logger.logConnection("ADB连接", "ADB连接成功");
        
        Logger.logConnection("启动服务器", "正在启动Scrcpy服务器...");
        startServer(device);
        Logger.logConnection("启动服务器", "服务器启动完成");
        
        Logger.logConnection("建立连接", "正在建立视频连接...");
        connectServer(device);
        Logger.logConnection("建立连接", "视频连接成功!");
        
        Logger.i("ClientStream", "========== 连接成功 ==========");
        handle.run(true);
      } catch (Exception e) {
        Logger.e("ClientStream", "连接失败: " + e.getMessage(), e);
        PublicTools.logToast("stream", e.toString(), true);
        handle.run(false);
      } finally {
        timeOutThread.interrupt();
      }
    });
    connectThread.start();
    timeOutThread.start();
  }

  private void startServer(Device device) throws Exception {
    if (BuildConfig.ENABLE_DEBUG_FEATURE || !adb.runAdbCmd("ls /data/local/tmp/scrcpy_*").contains(serverName)) {
      adb.runAdbCmd("rm /data/local/tmp/scrcpy_* ");
      adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.scrcpy_server), serverName, null);
    }
    shell = adb.getShell();
    shell.write(ByteBuffer.wrap(("app_process -Djava.class.path=" + serverName + " / qzrs.Scrcpy.server.Server"
      + " serverPort=" + device.serverPort
      + " listenClip=" + (device.listenClip ? 1 : 0)
      + " isAudio=" + (device.isAudio ? 1 : 0)
      + " maxSize=" + device.maxSize
      + " maxFps=" + device.maxFps
      + " maxVideoBit=" + device.maxVideoBit
      + " keepAwake=" + (device.keepWakeOnRunning ? 1 : 0)
      + " supportH265=" + ((device.useH265 && supportH265) ? 1 : 0)
      + " supportOpus=" + (supportOpus ? 1 : 0)
      + " startApp=" + device.startApp + " \n").getBytes()));
  }

  private void connectServer(Device device) throws Exception {
    Thread.sleep(50);
    int reTry = 40;
    int reTryTime = timeoutDelay / reTry;
    if (!device.isLinkDevice()) {
      long startTime = System.currentTimeMillis();
      boolean mainConn = false;
      InetSocketAddress inetSocketAddress = new InetSocketAddress(PublicTools.getIp(device.address), device.serverPort);
      for (int i = 0; i < reTry; i++) {
        try {
          if (!mainConn) {
            mainSocket = new Socket();
            mainSocket.connect(inetSocketAddress, timeoutDelay / 2);
            mainConn = true;
          }
          videoSocket = new Socket();
          videoSocket.connect(inetSocketAddress, timeoutDelay / 2);
          mainOutputStream = mainSocket.getOutputStream();
          mainDataInputStream = new DataInputStream(mainSocket.getInputStream());
          videoDataInputStream = new DataInputStream(videoSocket.getInputStream());
          connectDirect = true;
          return;
        } catch (Exception ignored) {
          if (mainSocket != null) mainSocket.close();
          if (videoSocket != null) videoSocket.close();
          if (System.currentTimeMillis() - startTime >= timeoutDelay / 2 - 1000) i = reTry;
          else Thread.sleep(reTryTime);
        }
      }
    }
    for (int i = 0; i < reTry; i++) {
      try {
        if (mainBufferStream == null) mainBufferStream = adb.tcpForward(device.serverPort);
        if (videoBufferStream == null) videoBufferStream = adb.tcpForward(device.serverPort);
        return;
      } catch (Exception ignored) {
        Thread.sleep(reTryTime);
      }
    }
    throw new Exception(AppData.applicationContext.getString(R.string.toast_connect_server));
  }

  public String runShell(String cmd) throws Exception {
    return adb.runAdbCmd(cmd);
  }

  public byte readByteFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readByte();
    else return mainBufferStream.readByte();
  }

  public byte readByteFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readByte();
    else return videoBufferStream.readByte();
  }

  public int readIntFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readInt();
    else return mainBufferStream.readInt();
  }

  public int readIntFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readInt();
    else return videoBufferStream.readInt();
  }

  public ByteBuffer readByteArrayFromMain(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      byte[] buffer = new byte[size];
      mainDataInputStream.readFully(buffer);
      return ByteBuffer.wrap(buffer);
    } else return mainBufferStream.readByteArray(size);
  }

  public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      byte[] buffer = new byte[size];
      videoDataInputStream.readFully(buffer);
      return ByteBuffer.wrap(buffer);
    }
    return videoBufferStream.readByteArray(size);
  }

  public ByteBuffer readFrameFromMain() throws Exception {
    if (!connectDirect) mainBufferStream.flush();
    return readByteArrayFromMain(readIntFromMain());
  }

  public ByteBuffer readFrameFromVideo() throws Exception {
    Logger.logVideo("读取视频帧", "开始读取...");
    
    if (!connectDirect) {
      Logger.logVideo("Buffer模式", "刷新缓冲区");
      videoBufferStream.flush();
    }
    
    Logger.logVideo("读取", "等待视频数据...");
    int size = readIntFromVideo();
    Logger.logVideo("读取", "视频帧大小: " + size + " bytes");
    
    if (size <= 0) {
      Logger.w("ClientStream", "警告: 视频帧大小为 " + size);
      return ByteBuffer.allocate(0);
    }
    
    ByteBuffer frame = readByteArrayFromVideo(size);
    Logger.logVideo("读取", "视频帧完成: " + frame.remaining() + " bytes");
    
    return frame;
  }

  public void writeToMain(ByteBuffer byteBuffer) throws Exception {
    if (connectDirect) mainOutputStream.write(byteBuffer.array());
    else mainBufferStream.write(byteBuffer);
  }

  /**
   * 发送 keepAlive 并测量 RTT 延迟，结果上报给 StatsOverlay
   */
  public void writeToMainWithLatency(ByteBuffer byteBuffer) throws Exception {
    pingSendTime = System.currentTimeMillis();
    writeToMain(byteBuffer);
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    if (shell != null) PublicTools.logToast("server", new String(shell.readByteArrayBeforeClose().array()), false);
    if (connectDirect) {
      try {
        mainOutputStream.close();
        videoDataInputStream.close();
        mainDataInputStream.close();
        mainSocket.close();
        videoSocket.close();
      } catch (Exception ignored) {
      }
    } else {
      mainBufferStream.close();
      videoBufferStream.close();
    }
  }
}
