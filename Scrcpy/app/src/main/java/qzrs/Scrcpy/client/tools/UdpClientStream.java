package qzrs.Scrcpy.client.tools;

import java.io.IOException;
import java.nio.ByteBuffer;

import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.network.ConnectionManager;
import qzrs.Scrcpy.network.StunClient;

/**
 * UDP模式的ClientStream
 * 替换原有的TCP连接方式，支持P2P直连和中继
 */
public class UdpClientStream {
    
    private boolean isClose = false;
    
    // UDP连接管理器
    private ConnectionManager connectionManager;
    
    // 连接模式
    private ConnectionManager.ConnectionMode connectionMode;
    
    // 设备信息
    private Device device;
    
    // 回调
    private StreamCallback callback;
    
    /**
     * 连接结果
     */
    public static class ConnectResult {
        public boolean success;
        public ConnectionManager.ConnectionMode mode;
        public String errorMessage;
        public StunClient.NatInfo natInfo;
    }
    
    /**
     * 构造UDP流客户端
     */
    public UdpClientStream(Device device, StreamCallback callback) {
        this.device = device;
        this.callback = callback;
    }
    
    /**
     * 连接到服务器(尝试UDP P2P或中继)
     */
    public ConnectResult connect() {
        ConnectResult result = new ConnectResult();
        
        try {
            // 创建连接管理器
            String serverAddr = getServerAddress();
            int serverPort = device.signalingPort;
            
            connectionManager = new ConnectionManager(serverAddr, serverPort);
            connectionManager.setListener(new ConnectionManager.ConnectionListener() {
                @Override
                public void onConnected(ConnectionManager.ConnectionMode mode) {
                    if (callback != null) {
                        callback.onConnected(mode);
                    }
                }
                
                @Override
                public void onConnectFailed(String reason) {
                    if (callback != null) {
                        callback.onConnectFailed(reason);
                    }
                }
                
                @Override
                public void onDisconnected() {
                    if (callback != null) {
                        callback.onDisconnected();
                    }
                }
            });
            
            // 执行连接(自动选择最优路径)
            boolean connected = connectionManager.connect();
            
            result.success = connected;
            result.mode = connectionManager.getCurrentMode();
            result.natInfo = connectionManager.getNatInfo();
            
            if (!connected) {
                result.errorMessage = "所有连接策略均失败";
            }
            
            return result;
            
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = e.getMessage();
            return result;
        }
    }
    
    /**
     * 获取服务器地址
     */
    private String getServerAddress() {
        // 如果配置了中继服务器,使用中继地址
        if (device.relayServer != null && !device.relayServer.isEmpty()) {
            return device.relayServer;
        }
        // 否则使用信令服务器地址
        return device.signalingServer;
    }
    
    /**
     * 发送视频帧(UDP)
     */
    public void sendVideoFrame(ByteBuffer frame) throws IOException {
        if (connectionManager != null) {
            connectionManager.sendVideo(frame);
        }
    }
    
    /**
     * 发送控制指令(UDP带确认)
     */
    public void sendControl(ByteBuffer control) throws IOException {
        if (connectionManager != null) {
            connectionManager.sendControl(control);
        }
    }
    
    /**
     * 接收视频数据
     */
    public ByteBuffer receiveVideo() throws IOException, InterruptedException {
        if (connectionManager != null) {
            return connectionManager.receiveVideo();
        }
        throw new IOException("Not connected");
    }
    
    /**
     * 接收控制数据
     */
    public ByteBuffer receiveControl() throws IOException, InterruptedException {
        if (connectionManager != null) {
            return connectionManager.receiveControl();
        }
        throw new IOException("Not connected");
    }
    
    /**
     * 获取当前连接模式
     */
    public ConnectionManager.ConnectionMode getConnectionMode() {
        return connectionManager != null ? connectionManager.getCurrentMode() : null;
    }
    
    /**
     * 是否使用UDP
     */
    public boolean isUdpMode() {
        return connectionManager != null && connectionManager.isUdpMode();
    }
    
    /**
     * 获取NAT信息
     */
    public StunClient.NatInfo getNatInfo() {
        return connectionManager != null ? connectionManager.getNatInfo() : null;
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        if (isClose) return;
        isClose = true;
        
        if (connectionManager != null) {
            connectionManager.close();
            connectionManager = null;
        }
    }
    
    /**
     * 流回调接口
     */
    public interface StreamCallback {
        void onConnected(ConnectionManager.ConnectionMode mode);
        void onConnectFailed(String reason);
        void onDisconnected();
    }
}
