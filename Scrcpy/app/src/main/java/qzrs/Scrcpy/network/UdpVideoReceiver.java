package qzrs.Scrcpy.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import qzrs.Scrcpy.helper.Logger;

/**
 * UDP视频接收器
 * 接收服务端通过UDP中继发送的分片视频数据，重组后放入队列
 * 
 * 数据包格式（与服务器UdpRelaySender对应）：
 * [类型:1][会话ID:16][seq:4][fragIndex:2][totalFrags:2][data:N]
 */
public class UdpVideoReceiver {

    private static final int HEADER_SIZE = 25; // 1+16+4+2+2
    private static final int MAX_UDP_PACKET = 65536;
    
    private DatagramSocket socket;
    private Thread receiveThread;
    private boolean running = false;
    private final BlockingQueue<ByteBuffer> frameQueue = new LinkedBlockingQueue<>(30);
    private String expectedSessionId;

    // 分片重组缓冲区: seq -> 分片数据
    private final Map<Integer, byte[][]> fragmentBuffers = new HashMap<>();
    private final Map<Integer, int[]> fragmentMeta = new HashMap<>(); // [totalLen, totalFrags, receivedFrags]

    public UdpVideoReceiver(DatagramSocket socket) {
        this.socket = socket;
    }
    
    public void setExpectedSessionId(String sessionId) {
        this.expectedSessionId = sessionId;
    }

    public void start() {
        running = true;
        receiveThread = new Thread(this::receiveLoop);
        receiveThread.setDaemon(true);
        receiveThread.start();
        Logger.i("UdpVideoReceiver", "接收线程已启动");
    }

    private void receiveLoop() {
        byte[] buf = new byte[MAX_UDP_PACKET];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                processPacket(pkt.getData(), pkt.getLength());
            } catch (Exception e) {
                if (running) Logger.e("UdpVideoReceiver", "接收错误: " + e.getMessage());
            }
        }
    }

    private void processPacket(byte[] data, int len) {
        if (len < HEADER_SIZE) {
            Logger.w("UdpVideoReceiver", "包太小: " + len);
            return;
        }

        int pos = 0;
        
        // 解析包头:
        // [类型:1][会话ID:16][seq:4][fragIndex:2][totalFrags:2][data:N]
        
        byte type = data[pos++]; // 类型: 0x04 (服务器->客户端)
        
        // 会话ID (16字节) - 验证是否匹配
        String sessionId = new String(data, pos, 16).trim();
        pos += 16;
        
        // 如果设置了期望的会话ID，验证
        if (expectedSessionId != null && !expectedSessionId.equals(sessionId)) {
            Logger.w("UdpVideoReceiver", "会话ID不匹配: " + sessionId + " != " + expectedSessionId);
            return;
        }
        
        // 序号 (4字节)
        int seq = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        
        // 分片索引 (2字节)
        int fragIndex = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        
        // 总分片数 (2字节)
        int totalFrags = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        
        int payloadLen = len - HEADER_SIZE;

        Logger.d("UdpVideoReceiver", "收到UDP包: type=" + type + " session=" + sessionId + " seq=" + seq + " frag=" + fragIndex + "/" + totalFrags + " len=" + payloadLen);

        // 初始化分片缓冲区
        if (!fragmentBuffers.containsKey(seq)) {
            fragmentBuffers.put(seq, new byte[totalFrags][]);
            fragmentMeta.put(seq, new int[]{0, totalFrags, 0});
        }

        byte[][] frags = fragmentBuffers.get(seq);
        int[] meta = fragmentMeta.get(seq);

        if (frags[fragIndex] == null) {
            frags[fragIndex] = new byte[payloadLen];
            System.arraycopy(data, HEADER_SIZE, frags[fragIndex], 0, payloadLen);
            meta[2]++;
        }

        // 检查是否所有分片都收到了
        if (meta[2] == meta[1]) {
            // 计算总长度
            int totalLen = 0;
            for (byte[] frag : frags) {
                if (frag != null) totalLen += frag.length;
            }
            
            // 重组帧
            byte[] frame = new byte[totalLen];
            int offset = 0;
            for (int i = 0; i < frags.length; i++) {
                if (frags[i] != null) {
                    System.arraycopy(frags[i], 0, frame, offset, frags[i].length);
                    offset += frags[i].length;
                }
            }
            fragmentBuffers.remove(seq);
            fragmentMeta.remove(seq);

            // 放入队列
            try {
                if (frameQueue.offer(ByteBuffer.wrap(frame), 1, java.util.concurrent.TimeUnit.SECONDS)) {
                    Logger.d("UdpVideoReceiver", "帧重组完成: seq=" + seq + " size=" + frame.length);
                } else {
                    Logger.w("UdpVideoReceiver", "帧队列满，丢弃: seq=" + seq);
                }
            } catch (Exception e) {
                Logger.e("UdpVideoReceiver", "帧入队失败: " + e.getMessage());
            }
        }
    }

    /**
     * 读取下一帧（阻塞，带超时）
     */
    public ByteBuffer readFrame() throws InterruptedException {
        ByteBuffer frame = frameQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        if (frame == null) {
            Logger.w("UdpVideoReceiver", "读取帧超时，队列状态: " + frameQueue.size());
        }
        return frame;
    }

    public void stop() {
        running = false;
        if (receiveThread != null) receiveThread.interrupt();
    }
}
