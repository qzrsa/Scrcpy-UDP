package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带确认的UDP通道 - 用于控制指令
 * 特点: 结合UDP低延迟和TCP可靠性
 */
public class UdpChannelWithAck {
    
    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;
    
    // 序列号生成器
    private final AtomicInteger sequenceNumber = new AtomicInteger(0);
    
    // 等待确认的包
    private final ConcurrentHashMap<Integer, PendingPacket> pendingPackets = 
        new ConcurrentHashMap<>();
    
    // 接收线程运行标志
    private volatile boolean running = true;
    private Thread receiverThread;
    
    // 配置参数
    private static final int ACK_TIMEOUT = 500;      // 确认超时(ms)
    private static final int MAX_RETRIES = 3;       // 最大重试次数
    private static final int BUFFER_SIZE = 65535;
    
    // UDP头部类型
    private static final byte TYPE_DATA = 0x01;
    private static final byte TYPE_ACK = 0x02;
    
    /**
     * 待确认的数据包
     */
    private static class PendingPacket {
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] data;
        final int retries;
        volatile boolean acknowledged = false;
        
        PendingPacket(byte[] data, int retries) {
            this.data = data;
            this.retries = retries;
        }
        
        boolean await(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        void acknowledge() {
            if (!acknowledged) {
                acknowledged = true;
                latch.countDown();
            }
        }
    }
    
    public UdpChannelWithAck(String host, int port) throws IOException {
        this.remoteAddress = InetAddress.getByName(host);
        this.remotePort = port;
        this.socket = new DatagramSocket();
        this.socket.setReuseAddress(true);
        
        // 启动接收/确认处理线程
        startReceiver();
    }
    
    /**
     * 发送数据(带确认机制)
     */
    public synchronized void write(ByteBuffer data) throws IOException {
        int seq = sequenceNumber.incrementAndGet();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        // 构建数据包: [类型:1字节][序列号:4字节][数据:N字节]
        ByteBuffer packet = ByteBuffer.allocate(5 + bytes.length);
        packet.put(TYPE_DATA);
        packet.putInt(seq);
        packet.put(bytes);
        
        PendingPacket pending = new PendingPacket(packet.array(), MAX_RETRIES);
        pendingPackets.put(seq, pending);
        
        try {
            // 发送并等待确认
            sendWithRetry(pending);
        } finally {
            pendingPackets.remove(seq);
        }
    }
    
    /**
     * 发送并等待确认
     */
    private void sendWithRetry(PendingPacket pending) throws IOException {
        int attempts = 0;
        long startTime = System.currentTimeMillis();
        
        while (attempts < MAX_RETRIES) {
            try {
                // 检查是否超时(总超时3秒)
                if (System.currentTimeMillis() - startTime > 3000) {
                    throw new IOException("Send timeout");
                }
                
                DatagramPacket packet = new DatagramPacket(
                    pending.data, pending.data.length,
                    remoteAddress, remotePort
                );
                socket.send(packet);
                attempts++;
                
                // 等待确认
                boolean acknowledged = pending.await(ACK_TIMEOUT);
                if (acknowledged) {
                    return; // 发送成功
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Send interrupted", e);
            }
        }
        
        throw new IOException("Packet delivery failed after " + MAX_RETRIES + " retries");
    }
    
    /**
     * 直接发送(无确认,用于心跳等)
     */
    public void sendRaw(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        DatagramPacket packet = new DatagramPacket(
            bytes, bytes.length,
            remoteAddress, remotePort
        );
        socket.send(packet);
    }
    
    /**
     * 接收/确认处理线程
     */
    private void startReceiver() {
        receiverThread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    ByteBuffer data = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                    byte type = data.get();
                    
                    if (type == TYPE_ACK) {
                        // 确认包
                        int ackNum = data.getInt();
                        PendingPacket pending = pendingPackets.get(ackNum);
                        if (pending != null) {
                            pending.acknowledge();
                        }
                    }
                    // DATA类型在这里不处理(由调用方接收)
                    
                } catch (IOException e) {
                    if (running) {
                        // 忽略超时等非致命错误
                    }
                }
            }
        });
        receiverThread.setName("UdpChannelWithAck-Receiver");
        receiverThread.start();
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        running = false;
        
        // 中断所有等待中的包
        for (PendingPacket packet : pendingPackets.values()) {
            packet.acknowledge();
        }
        pendingPackets.clear();
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
    }
    
    /**
     * 检查连接是否打开
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed();
    }
}
