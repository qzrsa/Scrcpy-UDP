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
 */
public class UdpVideoReceiver {

    private static final int MAX_UDP_PACKET = 65536;
    private DatagramSocket socket;
    private Thread receiveThread;
    private boolean running = false;
    private final BlockingQueue<ByteBuffer> frameQueue = new LinkedBlockingQueue<>(30);

    // 分片重组缓冲区: seq -> 分片数据
    private final Map<Integer, byte[][]> fragmentBuffers = new HashMap<>();
    private final Map<Integer, int[]> fragmentMeta = new HashMap<>(); // [totalLen, totalFrags, receivedFrags]

    public UdpVideoReceiver(DatagramSocket socket) {
        this.socket = socket;
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
        if (len < 12) return;

        // 解析包头: [seq:4][totalLen:4][fragIndex:2][totalFrags:2][data:N]
        int seq = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int totalLen = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        int fragIndex = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        int totalFrags = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
        int payloadLen = len - 12;

        // 初始化分片缓冲区
        if (!fragmentBuffers.containsKey(seq)) {
            fragmentBuffers.put(seq, new byte[totalFrags][]);
            fragmentMeta.put(seq, new int[]{totalLen, totalFrags, 0});
        }

        byte[][] frags = fragmentBuffers.get(seq);
        int[] meta = fragmentMeta.get(seq);

        if (frags[fragIndex] == null) {
            frags[fragIndex] = new byte[payloadLen];
            System.arraycopy(data, 12, frags[fragIndex], 0, payloadLen);
            meta[2]++;
        }

        // 检查是否所有分片都收到了
        if (meta[2] == meta[1]) {
            // 重组帧
            byte[] frame = new byte[meta[0]];
            int offset = 0;
            for (byte[] frag : frags) {
                if (frag != null) {
                    System.arraycopy(frag, 0, frame, offset, frag.length);
                    offset += frag.length;
                }
            }
            fragmentBuffers.remove(seq);
            fragmentMeta.remove(seq);

            // 放入队列
            try {
                frameQueue.offer(ByteBuffer.wrap(frame));
                Logger.d("UdpVideoReceiver", "帧重组完成: seq=" + seq + " size=" + frame.length);
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
