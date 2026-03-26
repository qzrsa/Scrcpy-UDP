package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 中继客户端 - 当P2P直连失败时使用服务器中继
 */
public class RelayClient {
    
    // 中继服务器地址
    private String relayHost;
    private int relayPort;
    
    // UDP socket
    private DatagramSocket socket;
    private InetAddress relayAddress;
    
    // 运行状态
    private volatile boolean running = false;
    private Thread receiverThread;
    
    // 接收队列
    private final BlockingQueue<ByteBuffer> receiveQueue = new LinkedBlockingQueue<>();
    
    // 会话ID
    private String sessionId;
    
    // 心跳间隔(秒)
    private static final int HEARTBEAT_INTERVAL = 30;
    
    /**
     * 连接类型
     */
    public enum RelayType {
        UDP_RELAY,      // UDP中继
        TCP_RELAY       // TCP中继(保底)
    }
    
    public RelayClient(String host, int port) {
        this.relayHost = host;
        this.relayPort = port;
    }
    
    /**
     * 连接到中继服务器
     */
    public boolean connect() throws IOException {
        socket = new DatagramSocket();
        socket.setReuseAddress(true);
        relayAddress = InetAddress.getByName(relayHost);
        
        // 发送注册请求
        ByteBuffer registerRequest = ByteBuffer.allocate(64);
        registerRequest.put((byte) 0x01); // 注册包类型
        registerRequest.putInt(0); // 时间戳
        registerRequest.put("SCRCPY".getBytes());
        
        DatagramPacket packet = new DatagramPacket(
            registerRequest.array(), registerRequest.position(),
            relayAddress, relayPort
        );
        socket.send(packet);
        
        // 等待注册响应
        socket.setSoTimeout(5000);
        byte[] response = new byte[256];
        DatagramPacket responsePacket = new DatagramPacket(response, response.length);
        socket.receive(responsePacket);
        
        ByteBuffer responseBuffer = ByteBuffer.wrap(responsePacket.getData());
        byte type = responseBuffer.get();
        
        if (type == 0x02) { // 注册成功
            sessionId = new String(responsePacket.getData(), 1, 16).trim();
            running = true;
            
            // 启动接收线程
            startReceiver();
            
            // 启动心跳
            startHeartbeat();
            
            return true;
        }
        
        socket.close();
        return false;
    }
    
    /**
     * 创建UDP通道用于视频流
     */
    public UdpChannel createVideoChannel() throws IOException {
        return new UdpChannel(relayHost, relayPort);
    }
    
    /**
     * 创建带确认的UDP通道用于控制
     */
    public UdpChannelWithAck createControlChannel() throws IOException {
        return new UdpChannelWithAck(relayHost, relayPort);
    }
    
    /**
     * 接收线程
     */
    private void startReceiver() {
        receiverThread = new Thread(() -> {
            byte[] buffer = new byte[65535];
            
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    ByteBuffer data = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                    byte type = data.get();
                    
                    if (type == 0x03) { // 数据包
                        receiveQueue.offer(data);
                    }
                    
                } catch (Exception e) {
                    if (running) {
                        // 忽略错误继续
                    }
                }
            }
        });
        receiverThread.setName("RelayClient-Receiver");
        receiverThread.start();
    }
    
    /**
     * 心跳线程
     */
    private void startHeartbeat() {
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL * 1000);
                    
                    ByteBuffer heartbeat = ByteBuffer.allocate(32);
                    heartbeat.put((byte) 0x04); // 心跳包类型
                    heartbeat.putLong(System.currentTimeMillis());
                    
                    DatagramPacket packet = new DatagramPacket(
                        heartbeat.array(), heartbeat.position(),
                        relayAddress, relayPort
                    );
                    socket.send(packet);
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // 心跳失败
                }
            }
        }).start();
    }
    
    /**
     * 获取接收数据
     */
    public ByteBuffer receive() throws InterruptedException {
        return receiveQueue.take();
    }
    
    /**
     * 获取接收数据(带超时)
     */
    public ByteBuffer receive(long timeoutMs) throws InterruptedException {
        return receiveQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * 发送数据到中继服务器
     */
    public void send(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        ByteBuffer packet = ByteBuffer.allocate(1 + 16 + bytes.length);
        packet.put((byte) 0x03); // 数据包类型
        packet.put(sessionId.getBytes(), 0, 16); // 会话ID
        packet.put(bytes);
        
        DatagramPacket dp = new DatagramPacket(
            packet.array(), packet.position(),
            relayAddress, relayPort
        );
        socket.send(dp);
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        running = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
        
        receiveQueue.clear();
    }
    
    /**
     * 检查是否连接
     */
    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }
    
    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return sessionId;
    }
}
