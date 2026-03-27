package qzrs.Scrcpy.client.tools;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
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
 * - 视频通道：UDP中继（低延迟）
 */
public class UdpClientStream extends ClientStream {

    private static final String RELAY_HOST = "47.105.67.198";
    private static final int RELAY_PORT = 3479;
    private static final int TIMEOUT = 15000;

    private static final String serverName = "/data/local/tmp/scrcpy_server_" + BuildConfig.VERSION_CODE + ".jar";
    private static final boolean supportH265 = DecodecTools.isSupportH265();
    private static final boolean supportOpus = DecodecTools.isSupportOpus();

    // TCP控制通道（复用父类字段）
    // mainSocket, mainOutputStream, mainDataInputStream 继承自父类

    // UDP视频接收
    private DatagramSocket udpSocket;
    private UdpVideoReceiver udpVideoReceiver;
    private boolean udpVideoReady = false;

    // TCP视频备用队列（UDP失败时使用）
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

                // 3. TCP连接（控制+视频备用）
                Logger.i("UdpClientStream", "步骤3: TCP连接...");
                connectTcp(device);
                Logger.i("UdpClientStream", "TCP连接成功");

                // 4. 尝试建立UDP视频接收
                Logger.i("UdpClientStream", "步骤4: 建立UDP视频接收...");
                tryConnectUdpVideo();

                Logger.i("UdpClientStream", "连接完成! UDP视频=" + udpVideoReady);
                PublicTools.logToast("UDP", "连接成功! " + (udpVideoReady ? "UDP视频" : "TCP视频"), true);
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
     * 启动Scrcpy服务器
     */
    private void startScrcpyServer(Device device) throws Exception {
        if (BuildConfig.ENABLE_DEBUG_FEATURE || !adb.runAdbCmd("ls /data/local/tmp/scrcpy_*").contains(serverName)) {
            adb.runAdbCmd("rm /data/local/tmp/scrcpy_* ");
            adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.scrcpy_server), serverName, null);
        }
        
        shell = adb.getShell();
        
        // 使用dalvikvm直接执行Java程序
        StringBuilder cmd = new StringBuilder();
        cmd.append("CLASSPATH=").append(serverName).append(" ");
        cmd.append("exec app_process / qzrs.Scrcpy.server.Server");
        cmd.append(" serverPort=").append(device.serverPort);
        cmd.append(" listenClip=").append(device.listenClip ? 1 : 0);
        cmd.append(" isAudio=").append(device.isAudio ? 1 : 0);
        cmd.append(" maxSize=").append(device.maxSize);
        cmd.append(" maxFps=").append(device.maxFps);
        cmd.append(" maxVideoBit=").append(device.maxVideoBit);
        cmd.append(" keepAwake=").append(device.keepWakeOnRunning ? 1 : 0);
        cmd.append(" supportH265=").append((device.useH265 && supportH265) ? 1 : 0);
        cmd.append(" supportOpus=").append(supportOpus ? 1 : 0);
        cmd.append(" startApp=").append(device.startApp);
        cmd.append("\n");
        
        Logger.i("UdpClientStream", "启动命令: " + cmd.toString());
        shell.write(ByteBuffer.wrap(cmd.toString().getBytes()));
        Thread.sleep(500);
    }

    /**
     * TCP连接（控制通道 + 视频备用通道）
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

        // 连接视频备用通道
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

        // 启动TCP视频备用线程
        startTcpVideoThread();
    }

    /**
     * 启动TCP视频备用线程（当UDP不可用时使用）
     */
    private void startTcpVideoThread() {
        tcpVideoThread = new Thread(() -> {
            try {
                while (!isClose) {
                    int size = videoDataInputStream.readInt();
                    if (size <= 0 || size > 10 * 1024 * 1024) continue;
                    byte[] data = new byte[size];
                    videoDataInputStream.readFully(data);
                    tcpVideoQueue.offer(ByteBuffer.wrap(data));
                }
            } catch (Exception e) {
                if (!isClose) Logger.e("UdpClientStream", "TCP视频线程异常: " + e.getMessage());
            }
        });
        tcpVideoThread.setDaemon(true);
        tcpVideoThread.start();
    }

    /**
     * 尝试建立UDP视频接收
     */
    private void tryConnectUdpVideo() {
        try {
            udpSocket = new DatagramSocket();
            udpSocket.setSoTimeout(3000);

            // 注册到UDP中继
            String deviceId = "client-" + android.os.Build.MODEL.replaceAll("[^a-zA-Z0-9]", "");
            byte[] regPacket = new byte[64];
            regPacket[0] = 0x01;
            byte[] idBytes = deviceId.getBytes();
            System.arraycopy(idBytes, 0, regPacket, 1, Math.min(idBytes.length, 32));

            java.net.DatagramPacket sendPkt = new java.net.DatagramPacket(
                regPacket, regPacket.length,
                java.net.InetAddress.getByName(RELAY_HOST), RELAY_PORT);
            udpSocket.send(sendPkt);

            // 等待确认
            byte[] respBuf = new byte[17];
            java.net.DatagramPacket respPkt = new java.net.DatagramPacket(respBuf, respBuf.length);
            udpSocket.receive(respPkt);

            if (respBuf[0] == 0x02) {
                udpSocket.setSoTimeout(0);
                udpVideoReceiver = new UdpVideoReceiver(udpSocket);
                udpVideoReceiver.start();
                udpVideoReady = true;
                Logger.i("UdpClientStream", "UDP视频接收就绪");
            }
        } catch (Exception e) {
            Logger.w("UdpClientStream", "UDP视频不可用，使用TCP: " + e.getMessage());
            udpVideoReady = false;
            if (udpSocket != null) udpSocket.close();
        }
    }

    // ==================== 视频读取（优先UDP，备用TCP）====================

    @Override
    public ByteBuffer readFrameFromVideo() throws IOException, InterruptedException {
        if (udpVideoReady && udpVideoReceiver != null) {
            return udpVideoReceiver.readFrame();
        } else {
            return tcpVideoQueue.take();
        }
    }

    @Override
    public byte readByteFromVideo() throws IOException, InterruptedException {
        ByteBuffer buf = readFrameFromVideo();
        return buf.get();
    }

    @Override
    public int readIntFromVideo() throws IOException, InterruptedException {
        ByteBuffer buf = readFrameFromVideo();
        if (buf.remaining() >= 4) return buf.getInt();
        // 从TCP直接读
        return videoDataInputStream.readInt();
    }

    @Override
    public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
        if (udpVideoReady) {
            return readFrameFromVideo();
        } else {
            byte[] data = new byte[size];
            videoDataInputStream.readFully(data);
            return ByteBuffer.wrap(data);
        }
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
