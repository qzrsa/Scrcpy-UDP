package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * UDP通道 - 用于视频流传输
 * 特点: 低延迟,但不保证可靠性
 */
public class UdpChannel {
    
    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;
    
    // 接收队列
    private final BlockingQueue<ByteBuffer> receiveQueue = new LinkedBlockingQueue<>();
    
    // 接收线程
    private volatile boolean running = true;
    private Thread receiverThread;
    
    // 最大UDP payload (MTU - IP头(20) - UDP头(8))
    private static final int MAX_PAYLOAD = 1400;
    private static final int BUFFER_SIZE = 65535;
    
    public UdpChannel(String host, int port) throws IOException {
        this.remoteAddress = InetAddress.getByName(host);
        this.remotePort = port;
        this.socket = new DatagramSocket();
        this.socket.setReuseAddress(true);
        this.socket.setSoTimeout(0); // 无限等待
        
        // 启动接收线程
        startReceiver();
    }
    
    /**
     * 发送数据(分包发送)
     */
    public synchronized void write(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        
        int offset = 0;
        int packetIndex = 0;
        
        while (offset < bytes.length) {
            int chunkSize = Math.min(MAX_PAYLOAD - 8, bytes.length - offset); // 8字节header
            
            // 构建数据包: [序列号:2字节][总包数:2字节][当前包:2字节][数据:N字节]
            ByteBuffer packet = ByteBuffer.allocate(6 + chunkSize);
            packet.putShort((short) 0); // 序列号(简化版)
            packet.putShort((short) ((bytes.length + MAX_PAYLOAD - 9) / (MAX_PAYLOAD - 8))); // 总包数
            packet.putShort((short) packetIndex); // 当前包索引
            packet.put(bytes, offset, chunkSize);
            
            DatagramPacket dp = new DatagramPacket(
                packet.array(), packet.limit(),
                remoteAddress, remotePort
            );
            socket.send(dp);
            
            offset += chunkSize;
            packetIndex++;
        }
    }
    
    /**
     * 直接发送原始数据包(用于控制指令)
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
     * 读取数据
     */
    public ByteBuffer read(int size) throws IOException, InterruptedException {
        ByteBuffer result = receiveQueue.take();
        
        if (result.remaining() < size) {
            // 数据不足,返回实际数据
            return result;
        }
        
        ByteBuffer ret = ByteBuffer.allocate(size);
        for (int i = 0; i < size; i++) {
            ret.put(result.get());
        }
        ret.flip();
        return ret;
    }
    
    /**
     * 启动接收线程
     */
    private void startReceiver() {
        receiverThread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    // 解析数据包
                    ByteBuffer data = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                    receiveQueue.offer(data);
                    
                } catch (IOException e) {
                    if (running) {
                        // 非正常关闭时忽略错误
                    }
                    break;
                }
            }
        });
        receiverThread.setName("UdpChannel-Receiver");
        receiverThread.start();
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
    }
    
    /**
     * 检查连接是否打开
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed();
    }
    
    /**
     * 获取本地端口
     */
    public int getLocalPort() {
        return socket != null ? socket.getLocalPort() : -1;
    }
    
    /**
     * 获取远程地址
     */
    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }
    
    /**
     * 获取远程端口
     */
    public int getRemotePort() {
        return remotePort;
    }
}
