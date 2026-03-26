package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 连接管理器 - 简化版
 * 直接连接UDP中继服务器
 */
public class ConnectionManager {
    
    /**
     * 连接模式
     */
    public enum ConnectionMode {
        UDP_RELAY("UDP中继"),
        TCP_DIRECT("TCP直连"),
        NONE("未连接");
        
        private final String description;
        ConnectionMode(String description) { this.description = description; }
        public String getDescription() { return description; }
    }
    
    // 服务器信息
    private String serverHost;
    private int serverPort;
    private String relayHost;
    private int relayPort;
    
    // 当前连接模式
    private ConnectionMode currentMode = ConnectionMode.NONE;
    
    // UDP通道
    private UdpChannel videoChannel;
    private UdpChannelWithAck controlChannel;
    
    // 连接状态
    private volatile boolean connected = false;
    
    // 监听器
    private ConnectionListener listener;
    
    // 调试标志
    private static final boolean DEBUG = true;
    
    public ConnectionManager(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.relayHost = serverHost;
        this.relayPort = 3479; // UDP中继端口
    }
    
    /**
     * 建立连接 - 直接使用UDP中继
     */
    public boolean connect() {
        // 策略1: UDP中继
        if (tryUdpRelay()) {
            currentMode = ConnectionMode.UDP_RELAY;
            notifyConnectionSuccess();
            return true;
        }
        
        // 策略2: TCP直连(备用)
        currentMode = ConnectionMode.NONE;
        notifyConnectionFailed("所有连接策略均失败");
        return false;
    }
    
    /**
     * UDP中继模式
     */
    private boolean tryUdpRelay() {
        try {
            debug("尝试连接UDP中继服务器: " + relayHost + ":" + relayPort);
            
            // 创建UDP通道
            videoChannel = new UdpChannel(relayHost, relayPort);
            controlChannel = new UdpChannelWithAck(relayHost, relayPort);
            
            // 发送注册包
            if (sendRegisterPacket()) {
                connected = true;
                debug("UDP中继连接成功!");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            debug("UDP中继失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 发送注册包到中继服务器
     */
    private boolean sendRegisterPacket() {
        try {
            // 构建注册包: [类型:1字节][设备ID:32字节][预留:31字节]
            ByteBuffer register = ByteBuffer.allocate(64);
            register.put((byte) 0x01); // 注册类型
            // 设备ID
            String deviceId = "android-" + android.os.Build.MODEL;
            byte[] idBytes = deviceId.getBytes();
            for (int i = 0; i < 32; i++) {
                register.put(i < idBytes.length ? idBytes[i] : 0);
            }
            register.flip();
            
            // 发送注册包
            videoChannel.sendRaw(register);
            
            debug("注册包已发送");
            return true;
            
        } catch (Exception e) {
            debug("发送注册包失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 发送视频数据
     */
    public void sendVideo(ByteBuffer data) throws IOException {
        if (videoChannel != null) {
            videoChannel.write(data);
        } else {
            throw new IOException("Not connected");
        }
    }
    
    /**
     * 发送控制指令
     */
    public void sendControl(ByteBuffer data) throws IOException {
        if (controlChannel != null) {
            controlChannel.write(data);
        } else {
            throw new IOException("Not connected");
        }
    }
    
    /**
     * 接收视频数据
     */
    public ByteBuffer receiveVideo() throws IOException, InterruptedException {
        if (videoChannel != null) {
            return videoChannel.read(65535);
        }
        throw new IOException("No video channel available");
    }
    
    /**
     * 接收控制数据
     */
    public ByteBuffer receiveControl() throws IOException, InterruptedException {
        if (controlChannel != null) {
            return controlChannel.read(4096);
        }
        throw new IOException("No control channel available");
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        connected = false;
        currentMode = ConnectionMode.NONE;
        
        if (videoChannel != null) {
            try { videoChannel.close(); } catch (Exception ignored) {}
            videoChannel = null;
        }
        
        if (controlChannel != null) {
            try { controlChannel.close(); } catch (Exception ignored) {}
            controlChannel = null;
        }
    }
    
    /**
     * 获取当前连接模式
     */
    public ConnectionMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 获取NAT信息
     */
    public StunClient.NatInfo getNatInfo() {
        return null;
    }
    
    /**
     * 是否使用UDP
     */
    public boolean isUdpMode() {
        return currentMode == ConnectionMode.UDP_RELAY;
    }
    
    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    // 调试输出
    private void debug(String msg) {
        if (DEBUG) {
            System.out.println("[ConnectionManager] " + msg);
        }
    }
    
    // 通知连接成功
    private void notifyConnectionSuccess() {
        if (listener != null) {
            listener.onConnected(currentMode);
        }
    }
    
    // 通知连接失败
    private void notifyConnectionFailed(String reason) {
        if (listener != null) {
            listener.onConnectFailed(reason);
        }
    }
    
    // 设置监听器
    public void setListener(ConnectionListener listener) {
        this.listener = listener;
    }
    
    /**
     * 连接监听器
     */
    public interface ConnectionListener {
        void onConnected(ConnectionMode mode);
        void onConnectFailed(String reason);
        void onDisconnected();
    }
}
