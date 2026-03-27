package qzrs.Scrcpy.client.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
import qzrs.Scrcpy.network.ConnectionManager;
import qzrs.Scrcpy.network.StunClient;
import qzrs.Scrcpy.network.UdpChannel;
import qzrs.Scrcpy.network.UdpChannelWithAck;

/**
 * UDP模式的ClientStream
 * 支持P2P直连和中继模式
 * 实现与ClientStream相同的接口
 */
public class UdpClientStream extends ClientStream {
    
    private boolean isClose = false;
    private boolean isConnected = false;
    
    // 信令服务器配置
    private static final String SIGNALING_SERVER = "47.105.67.198";
    private static final int SIGNALING_PORT = 8888;
    private static final int UDP_RELAY_PORT = 3479;
    
    // 连接管理器
    private ConnectionManager connectionManager;
    
    // UDP通道
    private UdpChannel videoChannel;
    private UdpChannelWithAck controlChannel;
    
    // 接收队列
    private final BlockingQueue<ByteBuffer> videoReceiveQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ByteBuffer> controlReceiveQueue = new LinkedBlockingQueue<>();
    
    // 接收线程
    private Thread videoReceiveThread;
    private Thread controlReceiveThread;
    
    // 设备信息 - 使用继承的 protected 字段
    
    // 统计信息 - 使用继承的字段
    // pingSendTime - 使用继承的字段
    
    private static final String serverName = "/data/local/tmp/scrcpy_server_" + BuildConfig.VERSION_CODE + ".jar";
    private static final boolean supportH265 = DecodecTools.isSupportH265();
    private static final boolean supportOpus = DecodecTools.isSupportOpus();
    private static final int timeoutDelay = 1000 * 15;
    
    // 重写父类的 statsOverlay
    private final StatsOverlay statsOverlay = new StatsOverlay();
    public long pingSendTime = 0;
    
    public StatsOverlay getStatsOverlay() {
        return statsOverlay;
    }
    
    public UdpClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
        super(); // 调用空构造函数
        
        // 创建超时线程
        Thread timeOutThread = new Thread(() -> {
            try {
                Thread.sleep(timeoutDelay);
                PublicTools.logToast("UDP", "连接超时", true);
                handle.run(false);
            } catch (InterruptedException ignored) {}
        });
        timeOutThread.start();
        
        // 创建连接线程
        Thread connectThread = new Thread(() -> {
            try {
                // 1. 通过ADB连接设备并启动服务器
                adb = AdbTools.connectADB(device);
                startServer(device);
                
                // 2. 建立UDP连接
                if (connectUdp(device)) {
                    isConnected = true;
                    handle.run(true);
                } else {
                    PublicTools.logToast("UDP", "UDP连接失败", true);
                    handle.run(false);
                }
            } catch (Exception e) {
                PublicTools.logToast("UDP", "错误: " + e.getMessage(), true);
                handle.run(false);
            } finally {
                timeOutThread.interrupt();
            }
        });
        connectThread.start();
    }
    
    /**
     * 启动Scrcpy服务器
     */
    private void startServer(Device device) throws Exception {
        String serverCheck = adb.runAdbCmd("ls /data/local/tmp/scrcpy_*");
        if (serverCheck.isEmpty() || !serverCheck.contains(serverName)) {
            adb.runAdbCmd("rm /data/local/tmp/scrcpy_* ");
            adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.scrcpy_server), serverName, null);
        }
        shell = adb.getShell();
        
        // 启动服务器
        String cmd = "app_process -Djava.class.path=" + serverName + " / qzrs.Scrcpy.server.Server"
            + " serverPort=" + device.serverPort
            + " listenClip=" + (device.listenClip ? 1 : 0)
            + " isAudio=" + (device.isAudio ? 1 : 0)
            + " maxSize=" + device.maxSize
            + " maxFps=" + device.maxFps
            + " maxVideoBit=" + device.maxVideoBit
            + " keepAwake=" + (device.keepWakeOnRunning ? 1 : 0)
            + " supportH265=" + ((device.useH265 && supportH265) ? 1 : 0)
            + " supportOpus=" + (supportOpus ? 1 : 0)
            + " startApp=" + device.startApp + " \n";
        
        shell.write(ByteBuffer.wrap(cmd.getBytes()));
        Thread.sleep(1000);
    }
    
    /**
     * 建立UDP连接
     */
    private boolean connectUdp(Device device) {
        try {
            PublicTools.logToast("UDP", "正在建立UDP连接...", true);
            
            // 1. 查询本地NAT信息
            StunClient stun = new StunClient();
            StunClient.NatInfo natInfo = stun.queryPublicAddress();
            if (natInfo.success) {
                PublicTools.logToast("UDP", "NAT: " + natInfo.publicIp + ":" + natInfo.publicPort, true);
            }
            
            // 2. 获取服务器地址
            String serverAddr = SIGNALING_SERVER;
            if (device.signalingServer != null && !device.signalingServer.isEmpty()) {
                serverAddr = device.signalingServer;
            }
            
            int serverPort = SIGNALING_PORT;
            if (device.signalingPort > 0) {
                serverPort = device.signalingPort;
            }
            
            // 3. 创建连接管理器
            connectionManager = new ConnectionManager(serverAddr, serverPort);
            connectionManager.setListener(new ConnectionManager.ConnectionListener() {
                @Override
                public void onConnected(ConnectionManager.ConnectionMode mode) {
                    PublicTools.logToast("UDP", "连接模式: " + mode.getDescription(), true);
                }
                
                @Override
                public void onConnectFailed(String reason) {
                    PublicTools.logToast("UDP", "连接失败: " + reason, true);
                }
                
                @Override
                public void onDisconnected() {
                    PublicTools.logToast("UDP", "连接断开", true);
                }
            });
            
            // 4. 执行连接
            boolean connected = connectionManager.connect();
            
            if (connected) {
                // 5. 创建UDP通道
                videoChannel = new UdpChannel(SIGNALING_SERVER, UDP_RELAY_PORT);
                controlChannel = new UdpChannelWithAck(SIGNALING_SERVER, UDP_RELAY_PORT);
                
                // 6. 启动接收线程
                startReceiveThreads();
                
                PublicTools.logToast("UDP", "UDP连接成功! 模式: " + connectionManager.getCurrentMode().getDescription(), true);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            PublicTools.logToast("UDP", "UDP异常: " + e.getMessage(), true);
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 启动接收线程
     */
    private void startReceiveThreads() {
        // 视频接收线程
        videoReceiveThread = new Thread(() -> {
            while (!isClose && videoChannel != null) {
                try {
                    ByteBuffer frame = videoChannel.read(65535);
                    videoReceiveQueue.offer(frame);
                } catch (Exception e) {
                    if (!isClose) break;
                }
            }
        });
        videoReceiveThread.setName("UDP-Video-Receiver");
        videoReceiveThread.start();
        
        // 控制接收线程
        controlReceiveThread = new Thread(() -> {
            while (!isClose && controlChannel != null) {
                try {
                    ByteBuffer data = controlChannel.read(4096);
                    controlReceiveQueue.offer(data);
                } catch (Exception e) {
                    if (!isClose) break;
                }
            }
        });
        controlReceiveThread.setName("UDP-Control-Receiver");
        controlReceiveThread.start();
    }
    
    /**
     * 读取视频帧
     */
    @Override
    public ByteBuffer readFrameFromVideo() throws Exception {
        if (!isConnected) {
            throw new IOException("Not connected");
        }
        return videoReceiveQueue.take();
    }
    
    /**
     * 读取字节数组
     */
    @Override
    public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
        ByteBuffer result = ByteBuffer.allocate(size);
        try {
            ByteBuffer buffer = readFrameFromVideo();
            
            int remaining = buffer.remaining();
            if (remaining >= size) {
                for (int i = 0; i < size; i++) {
                    result.put(buffer.get());
                }
            } else {
                while (buffer.hasRemaining()) {
                    result.put(buffer.get());
                }
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        
        result.flip();
        return result;
    }
    
    /**
     * 读取字节
     */
    @Override
    public byte readByteFromVideo() throws IOException, InterruptedException {
        try {
            ByteBuffer buffer = readFrameFromVideo();
            return buffer.get();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    /**
     * 读取整数
     */
    @Override
    public int readIntFromVideo() throws IOException, InterruptedException {
        try {
            ByteBuffer buffer = readFrameFromVideo();
            return buffer.getInt();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    /**
     * 发送控制指令
     */
    public void sendControl(ByteBuffer control) throws IOException {
        if (controlChannel != null) {
            controlChannel.write(control);
        }
    }
    
    /**
     * 发送keepAlive并测量延迟
     */
    public void writeToMainWithLatency(ByteBuffer byteBuffer) throws Exception {
        pingSendTime = System.currentTimeMillis();
        sendControl(byteBuffer);
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        if (isClose) return;
        isClose = true;
        
        if (shell != null) {
            try { shell.close(); } catch (Exception ignored) {}
        }
        if (videoChannel != null) {
            try { videoChannel.close(); } catch (Exception ignored) {}
        }
        if (controlChannel != null) {
            try { controlChannel.close(); } catch (Exception ignored) {}
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        
        videoReceiveQueue.clear();
        controlReceiveQueue.clear();
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * 获取当前连接模式
     */
    public String getConnectionMode() {
        if (connectionManager != null) {
            return connectionManager.getCurrentMode().getDescription();
        }
        return "Not connected";
    }
}
