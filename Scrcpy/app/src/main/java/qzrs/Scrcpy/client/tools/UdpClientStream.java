package qzrs.Scrcpy.client.tools;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import qzrs.Scrcpy.BuildConfig;
import qzrs.Scrcpy.R;
import qzrs.Scrcpy.adb.Adb;
import qzrs.Scrcpy.client.decode.DecodecTools;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;
import qzrs.Scrcpy.helper.Logger;
import qzrs.Scrcpy.helper.PublicTools;
import qzrs.Scrcpy.network.UdpVideoReceiver;

/**
 * UDP模式的ClientStream
 * - 控制通道：TCP（稳定可靠）
 * - 视频通道：TCP（暂时禁用UDP）
 * 
 * 重要：视频读取方法使用父类ClientStream的实现
 */
public class UdpClientStream extends ClientStream {

    private static final String RELAY_HOST = "47.105.67.198";
    private static final int RELAY_PORT = 3479;
    private static final int TIMEOUT = 15000;

    private static final String serverName = "/data/local/tmp/scrcpy_server_" + BuildConfig.VERSION_CODE + ".jar";
    private static final boolean supportH265 = DecodecTools.isSupportH265();
    private static final boolean supportOpus = DecodecTools.isSupportOpus();

    // UDP视频接收
    private DatagramSocket udpSocket;
    private UdpVideoReceiver udpVideoReceiver;
    private boolean udpVideoReady = false;

    // TCP视频队列
    private final BlockingQueue<ByteBuffer> tcpVideoQueue = new LinkedBlockingQueue<>();
    private Thread tcpVideoThread;

    private final StatsOverlay statsOverlay = new StatsOverlay();

    @Override
    public StatsOverlay getStatsOverlay() {
        return statsOverlay;
    }

    public UdpClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
        super(); // 空构造函数

        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT);
                Logger.e("UdpClientStream", "连接超时");
                PublicTools.logToast("UDP", "连接超时", true);
                handle.run(false);
            } catch (InterruptedException ignored) {}
        });

        Thread connectThread = new Thread(() -> {
            try {
                // 1. ADB连接
                Logger.i("UdpClientStream", "步骤1: ADB连接...");
                adb = AdbTools.connectADB(device);
                Logger.i("UdpClientStream", "ADB连接成功");

                // 2. 启动服务器
                Logger.i("UdpClientStream", "步骤2: 启动服务器...");
                startScrcpyServer(device);
                Logger.i("UdpClientStream", "服务器启动完成");

                // 3. TCP连接（控制+视频）
                Logger.i("UdpClientStream", "步骤3: TCP连接...");
                connectTcp(device);
                Logger.i("UdpClientStream", "TCP连接成功");

                // 4. 尝试建立UDP视频接收（暂时禁用，只用TCP）
                Logger.i("UdpClientStream", "步骤4: UDP视频接收...");
                tryConnectUdpVideo();
                Logger.i("UdpClientStream", "连接完成! UDP视频=" + udpVideoReady);
                PublicTools.logToast("UDP", "连接成功! " + (udpVideoReady ? "UDP" : "TCP") + "视频", true);
                handle.run(true);

            } catch (Exception e) {
                Logger.e("UdpClientStream", "连接失败: " + e.getMessage(), e);
                PublicTools.logToast("UDP", "连接失败: " + e.getMessage(), true);
                handle.run(false);
            } finally {
                timeoutThread.interrupt();
            }
        });

        timeoutThread.start();
        connectThread.start();
    }

    /**
     * 启动Scrcpy服务器 - 使用与ClientStream相同的方式
     */
    private void startScrcpyServer(Device device) throws Exception {
        if (BuildConfig.ENABLE_DEBUG_FEATURE || !adb.runAdbCmd("ls /data/local/tmp/scrcpy_*").contains(serverName)) {
            adb.runAdbCmd("rm /data/local/tmp/scrcpy_* ");
            adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.scrcpy_server), serverName, null);
        }
        
        // 使用和ClientStream一样的方式启动服务器
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
            + " startApp=" + device.startApp + "\n").getBytes()));
    }

    /**
     * TCP连接（控制通道 + 视频通道）
     */
    private void connectTcp(Device device) throws Exception {
        Thread.sleep(50);
        int retry = 40;
        InetSocketAddress addr = new InetSocketAddress(
            PublicTools.getIp(device.address), device.serverPort);

        // 连接主控制通道
        for (int i = 0; i < retry; i++) {
            try {
                mainSocket = new Socket();
                mainSocket.connect(addr, 500);
                mainSocket.setTcpNoDelay(true);
                mainOutputStream = mainSocket.getOutputStream();
                mainDataInputStream = new DataInputStream(mainSocket.getInputStream());
                break;
            } catch (Exception e) {
                if (i == retry - 1) throw e;
                Thread.sleep(300);
            }
        }

        // 连接视频通道
        for (int i = 0; i < retry; i++) {
            try {
                videoSocket = new Socket();
                videoSocket.connect(addr, 500);
                videoDataInputStream = new DataInputStream(videoSocket.getInputStream());
                connectDirect = true;
                break;
            } catch (Exception e) {
                if (i == retry - 1) throw e;
                Thread.sleep(300);
            }
        }
    }

    /**
     * 尝试建立UDP视频接收（暂时禁用，只用TCP）
     */
    private void tryConnectUdpVideo() {
        // 暂时禁用UDP，只用TCP视频
        // TODO: 修复UDP分片重组逻辑后重新启用
        Logger.w("UdpClientStream", "UDP视频暂时禁用，使用TCP视频");
        udpVideoReady = false;
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }
    }

    // ==================== 视频读取 ====================
    // 注意：不覆盖 readByteFromVideo() 和 readIntFromVideo()
    // 父类 ClientStream 的实现直接从 videoDataInputStream 读取，是正确的

    @Override
    public ByteBuffer readFrameFromVideo() throws Exception {
        // UDP 禁用时，使用父类的 TCP 实现
        if (!udpVideoReady) {
            return super.readFrameFromVideo();
        }
        
        // UDP 可用时，从 UDP 队列读取（待实现）
        throw new IOException("UDP video not implemented");
    }

    // ==================== 控制通道（TCP）====================

    @Override
    public byte readByteFromMain() throws IOException {
        return mainDataInputStream.readByte();
    }

    @Override
    public int readIntFromMain() throws IOException {
        return mainDataInputStream.readInt();
    }

    @Override
    public ByteBuffer readFrameFromMain() throws Exception {
        int size = mainDataInputStream.readInt();
        byte[] data = new byte[size];
        mainDataInputStream.readFully(data);
        return ByteBuffer.wrap(data);
    }

    @Override
    public ByteBuffer readByteArrayFromMain(int size) throws IOException {
        byte[] data = new byte[size];
        mainDataInputStream.readFully(data);
        return ByteBuffer.wrap(data);
    }

    @Override
    public void writeToMain(ByteBuffer byteBuffer) throws Exception {
        mainOutputStream.write(byteBuffer.array());
        mainOutputStream.flush();
    }

    @Override
    public void writeToMainWithLatency(ByteBuffer byteBuffer) throws Exception {
        pingSendTime = System.currentTimeMillis();
        writeToMain(byteBuffer);
    }

    // ==================== 关闭 ====================

    @Override
    public void close() {
        if (isClose) return;
        isClose = true;
        if (udpVideoReceiver != null) udpVideoReceiver.stop();
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
        if (tcpVideoThread != null) tcpVideoThread.interrupt();
        try { if (mainSocket != null) mainSocket.close(); } catch (Exception ignored) {}
        try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
        if (shell != null) PublicTools.logToast("server", new String(shell.readByteArrayBeforeClose().array()), false);
    }
}
