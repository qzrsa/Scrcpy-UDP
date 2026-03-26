package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 连接管理器 - 统一管理TCP/UDP/P2P/中继连接
 * 自动选择最优连接方式
 */
public class ConnectionManager {
    
    /**
     * 连接模式
     */
    public enum ConnectionMode {
        TCP_DIRECT("TCP直连"),
        UDP_P2P("UDP P2P直连"),
        UDP_RELAY("UDP中继"),
        TCP_RELAY("TCP中继");
        
        private final String description;
        
        ConnectionMode(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    // 服务器信息
    private String serverHost;
    private int serverPort;
    private String relayHost;
    private int relayPort;
    
    // 当前连接模式
    private ConnectionMode currentMode = ConnectionMode.TCP_DIRECT;
    
    // 通道
    private UdpChannel videoChannel;
    private UdpChannelWithAck controlChannel;
    private ConnectionManagerFallback tcpFallback;
    
    // NAT信息
    private StunClient.NatInfo localNatInfo;
    
    // 监听器
    private ConnectionListener listener;
    
    // 调试标志
    private static final boolean DEBUG = true;
    
    public ConnectionManager(String serverHost, int serverPort) {
        this(serverHost, serverPort, serverHost, serverPort + 1);
    }
    
    public ConnectionManager(String serverHost, int serverPort, 
                           String relayHost, int relayPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
    }
    
    /**
     * 建立连接 - 尝试最优路径
     * @return 是否连接成功
     */
    public boolean connect() {
        // 先获取本地NAT信息
        queryNatInfo();
        
        // 策略1: 尝试P2P UDP直连
        if (tryP2pUdp()) {
            currentMode = ConnectionMode.UDP_P2P;
            notifyConnectionSuccess();
            return true;
        }
        
        // 策略2: 尝试服务器UDP中继
        if (tryUdpRelay()) {
            currentMode = ConnectionMode.UDP_RELAY;
            notifyConnectionSuccess();
            return true;
        }
        
        // 策略3: TCP直连(原方案)
        if (tryTcpDirect()) {
            currentMode = ConnectionMode.TCP_DIRECT;
            notifyConnectionSuccess();
            return true;
        }
        
        // 策略4: TCP中继(保底)
        if (tryTcpRelay()) {
            currentMode = ConnectionMode.TCP_RELAY;
            notifyConnectionSuccess();
            return true;
        }
        
        notifyConnectionFailed("所有连接策略均失败");
        return false;
    }
    
    /**
     * 查询NAT信息
     */
    private void queryNatInfo() {
        StunClient stun = new StunClient();
        localNatInfo = stun.queryPublicAddress();
        if (DEBUG) {
            System.out.println("[ConnectionManager] NAT Info: " + localNatInfo);
        }
    }
    
    /**
     * 策略1: P2P UDP直连 - 核心功能
     */
    private boolean tryP2pUdp() {
        try {
            debug("尝试 P2P UDP 直连...");
            
            if (localNatInfo == null || !localNatInfo.success) {
                debug("无法获取本地NAT信息,跳过P2P");
                return false;
            }
            
            // 1. 获取对方地址(从信令服务器)
            RemotePeerInfo remote = fetchRemotePeerInfo();
            if (remote == null) {
                debug("无法获取远程对等信息,跳过P2P");
                return false;
            }
            
            // 2. 尝试UDP打洞
            boolean holePunchSuccess = performHolePunching(remote);
            if (!holePunchSuccess) {
                debug("UDP打洞失败");
                return false;
            }
            
            // 3. 建立UDP连接
            videoChannel = new UdpChannel(remote.ip, remote.port);
            controlChannel = new UdpChannelWithAck(remote.ip, remote.port);
            
            // 4. 测试连接
            if (testConnection()) {
                debug("P2P UDP 直连成功!");
                return true;
            }
            
            // 测试失败,清理
            closeChannels();
            return false;
            
        } catch (Exception e) {
            debug("P2P UDP 失败: " + e.getMessage());
            closeChannels();
            return false;
        }
    }
    
    /**
     * 策略2: UDP中继模式
     */
    private boolean tryUdpRelay() {
        try {
            debug("尝试 UDP 中继...");
            
            RelayClient relay = new RelayClient(relayHost, relayPort);
            if (!relay.connect()) {
                debug("UDP中继连接失败");
                return false;
            }
            
            videoChannel = relay.createVideoChannel();
            controlChannel = relay.createControlChannel();
            
            debug("UDP 中继成功!");
            return true;
            
        } catch (Exception e) {
            debug("UDP中继失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 策略3: TCP直连(原方案)
     */
    private boolean tryTcpDirect() {
        try {
            debug("尝试 TCP 直连...");
            
            tcpFallback = new ConnectionManagerFallback();
            boolean connected = tcpFallback.connectTcp(serverHost, serverPort);
            
            if (connected) {
                debug("TCP 直连成功!");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            debug("TCP直连失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 策略4: TCP中继(保底)
     */
    private boolean tryTcpRelay() {
        try {
            debug("尝试 TCP 中继...");
            
            tcpFallback = new ConnectionManagerFallback();
            boolean connected = tcpFallback.connectTcp(relayHost, relayPort);
            
            if (connected) {
                debug("TCP 中继成功!");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            debug("TCP中继失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 执行UDP打洞
     */
    private boolean performHolePunching(RemotePeerInfo remote) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setReuseAddress(true);
            
            InetAddress targetAddr = InetAddress.getByName(remote.ip);
            
            // 发送多个打洞包
            byte[] holePunch = new byte[]{'H', 'O', 'L', 'E'};
            for (int i = 0; i < 10; i++) {
                DatagramPacket packet = new DatagramPacket(
                    holePunch, holePunch.length,
                    targetAddr, remote.port
                );
                socket.send(packet);
                Thread.sleep(50);
            }
            
            // 等待对方响应(给对方时间回复)
            socket.setSoTimeout(3000);
            byte[] response = new byte[64];
            DatagramPacket responsePacket = new DatagramPacket(response, response.length);
            
            try {
                socket.receive(responsePacket);
                debug("收到对方打洞响应!");
                socket.close();
                return true;
            } catch (Exception e) {
                debug("等待打洞响应超时");
                socket.close();
                return false;
            }
            
        } catch (Exception e) {
            debug("打洞异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 从信令服务器获取远程对等信息
     * TODO: 需要实现WebSocket信令
     */
    private RemotePeerInfo fetchRemotePeerInfo() {
        // 这里应该连接信令服务器获取对方地址
        // 暂时返回null,实际使用时需要实现信令协议
        debug("信令服务器获取对等信息 - 暂未实现");
        return null;
    }
    
    /**
     * 测试连接
     */
    private boolean testConnection() {
        try {
            // 发送测试包
            ByteBuffer test = ByteBuffer.wrap(new byte[]{'T', 'E', 'S', 'T'});
            videoChannel.sendRaw(test);
            
            // 等待响应
            Thread.sleep(500);
            
            return true; // 如果没抛异常认为成功
            
        } catch (Exception e) {
            debug("连接测试失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 关闭所有通道
     */
    private void closeChannels() {
        if (videoChannel != null) {
            try {
                videoChannel.close();
            } catch (Exception ignored) {}
            videoChannel = null;
        }
        
        if (controlChannel != null) {
            try {
                controlChannel.close();
            } catch (Exception ignored) {}
            controlChannel = null;
        }
    }
    
    /**
     * 发送视频数据
     */
    public void sendVideo(ByteBuffer data) throws IOException {
        if (videoChannel != null) {
            videoChannel.write(data);
        } else if (tcpFallback != null) {
            tcpFallback.sendVideo(data);
        }
    }
    
    /**
     * 发送控制指令
     */
    public void sendControl(ByteBuffer data) throws IOException {
        if (controlChannel != null) {
            controlChannel.write(data);
        } else if (tcpFallback != null) {
            tcpFallback.sendControl(data);
        }
    }
    
    /**
     * 接收视频数据
     */
    public ByteBuffer receiveVideo() throws IOException, InterruptedException {
        if (videoChannel != null) {
            // 使用接收队列
            return videoChannel.read(65535);
        } else if (tcpFallback != null) {
            return tcpFallback.receiveVideo();
        }
        throw new IOException("No video channel available");
    }
    
    /**
     * 接收控制数据
     */
    public ByteBuffer receiveControl() throws IOException, InterruptedException {
        if (controlChannel != null) {
            return controlChannel.read(1024);
        } else if (tcpFallback != null) {
            return tcpFallback.receiveControl();
        }
        throw new IOException("No control channel available");
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        closeChannels();
        
        if (tcpFallback != null) {
            tcpFallback.close();
            tcpFallback = null;
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
        return localNatInfo;
    }
    
    /**
     * 是否使用UDP
     */
    public boolean isUdpMode() {
        return currentMode == ConnectionMode.UDP_P2P || 
               currentMode == ConnectionMode.UDP_RELAY;
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
     * 远程对等信息
     */
    private static class RemotePeerInfo {
        String ip;
        int port;
    }
    
    /**
     * TCP回退实现
     */
    private static class ConnectionManagerFallback {
        // 这里复用原有的TCP连接代码
        // 实际使用时应该引用ClientStream的连接
        
        private Object tcpConnection; // 实际应该是Socket
        
        boolean connectTcp(String host, int port) throws IOException {
            // TODO: 实现TCP连接
            return false;
        }
        
        void sendVideo(ByteBuffer data) throws IOException {
            // TODO: 实现TCP发送
        }
        
        void sendControl(ByteBuffer data) throws IOException {
            // TODO: 实现TCP发送
        }
        
        ByteBuffer receiveVideo() throws IOException, InterruptedException {
            // TODO: 实现TCP接收
            return null;
        }
        
        ByteBuffer receiveControl() throws IOException, InterruptedException {
            // TODO: 实现TCP接收
            return null;
        }
        
        void close() {
            // TODO: 关闭TCP连接
        }
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
