package qzrs.Scrcpy.client.tools;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 视频通道 UDP 接收器（仅用于直连模式）：
 * - 通过 UDP 直连接收服务端分片发送的视频记录（每条记录对应服务端一次 writeVideo）。
 * - 按帧序号重组分片，并按序向上层提供与 TCP 一致的连续字节流（readByte / readInt / readFrame）。
 * - 检测丢帧（序号缺口或重组超时）后通过 onPacketLoss 回调请求服务端输出关键帧(IDR)。
 *
 * 上层（ClientPlayer.videoStreamIn）无需任何改动，仍按固定顺序消费
 * [1字节H265][宽][高] -> csd0 -> csd1 -> 连续帧。
 *
 * 帧格式（每个 UDP 包 payload）：
 *   type(1) | frameSeq(4, 大端) | fragIdx(2) | fragTotal(2) | chunk(<=MAX_CHUNK)
 */
public class UdpVideoReceiver {
  // 单分片最大载荷（预留 IP/UDP 头空间，远小于常见 MTU 1472）
  private static final int MAX_CHUNK = 1400;
  // 重组超时：超过该时间某帧仍未收齐则判定丢帧
  private static final long REASSEMBLE_TIMEOUT_MS = 200;
  // 握手包重试次数（UDP 不可靠，多发几次提高成功率）
  private static final int HANDSHAKE_RETRIES = 5;

  private final DatagramSocket socket;
  private final SocketAddress serverAddress;
  private final Runnable onPacketLoss;
  private final LinkedBlockingQueue<byte[]> recordQueue = new LinkedBlockingQueue<>();
  private final Map<Integer, FrameFrag> frags = new HashMap<>();
  private final Map<Integer, byte[]> readyRecords = new HashMap<>();
  private int nextExpectedSeq = 0;

  private volatile boolean closed = false;
  private Thread recvThread;

  // 当前正在读取的记录与游标（拼接为连续字节流）
  private byte[] curRecord = null;
  private int curPos = 0;

  public UdpVideoReceiver(DatagramSocket socket, SocketAddress serverAddress, Runnable onPacketLoss) {
    this.socket = socket;
    this.serverAddress = serverAddress;
    this.onPacketLoss = onPacketLoss;
    start();
  }

  private void start() {
    // 发送握手包，让服务端记录客户端 UDP 地址（UDP 不可靠，多发几次）
    byte[] handshake = new byte[]{1};
    for (int i = 0; i < HANDSHAKE_RETRIES; i++) {
      try {
        socket.send(new DatagramPacket(handshake, 1, serverAddress));
        Thread.sleep(50);
      } catch (Exception ignored) {
        break;
      }
    }
    recvThread = new Thread(this::recvLoop);
    recvThread.setName("udp-video-recv");
    recvThread.start();
  }

  private void recvLoop() {
    byte[] buf = new byte[MAX_CHUNK + 64];
    while (!closed) {
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        if (packet.getLength() < 9) continue; // 太短，忽略（握手回显等异常包）
        parsePacket(buf, packet.getLength());
      } catch (InterruptedException ignored) {
        break;
      } catch (Exception ignored) {
        if (closed) break;
      }
    }
  }

  private void parsePacket(byte[] data, int length) {
    ByteBuffer b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);
    b.get(); // type
    int seq = b.getInt();
    int fragIdx = b.getShort() & 0xFFFF;
    int fragTotal = b.getShort() & 0xFFFF;
    if (fragTotal <= 0 || fragIdx >= fragTotal) return;
    byte[] chunk = new byte[b.remaining()];
    b.get(chunk);

    if (seq < nextExpectedSeq) return; // 过期包

    FrameFrag f = frags.get(seq);
    if (f == null) {
      f = new FrameFrag(fragTotal);
      frags.put(seq, f);
    }
    if (f.chunks[fragIdx] == null) {
      f.chunks[fragIdx] = chunk;
      f.received++;
      f.lastUpdate = System.currentTimeMillis();
    }
    if (f.received == fragTotal) {
      frags.remove(seq);
      readyRecords.put(seq, reassemble(f));
    }
    cleanupAndDeliver();
  }

  private byte[] reassemble(FrameFrag f) {
    int total = 0;
    for (byte[] c : f.chunks) total += c.length;
    byte[] out = new byte[total];
    int off = 0;
    for (byte[] c : f.chunks) {
      System.arraycopy(c, 0, out, off, c.length);
      off += c.length;
    }
    return out;
  }

  // 检测丢帧（缺口/超时），推进期望序号，并尽可能按序交付完整记录
  private void cleanupAndDeliver() {
    long now = System.currentTimeMillis();
    // 超时清理
    boolean lost = false;
    Iterator<Map.Entry<Integer, FrameFrag>> it = frags.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, FrameFrag> e = it.next();
      if (e.getKey() < nextExpectedSeq
          || (e.getKey() == nextExpectedSeq && now - e.getValue().lastUpdate > REASSEMBLE_TIMEOUT_MS)) {
        it.remove();
        if (e.getKey() <= nextExpectedSeq) lost = true;
      }
    }
    // 缺口检测：已有更大序号的数据，但期望序号帧根本没开始接收 -> 丢帧
    int maxSeq = nextExpectedSeq;
    for (Integer k : frags.keySet()) maxSeq = Math.max(maxSeq, k);
    for (Integer k : readyRecords.keySet()) maxSeq = Math.max(maxSeq, k);
    if (maxSeq > nextExpectedSeq && !frags.containsKey(nextExpectedSeq) && !readyRecords.containsKey(nextExpectedSeq)) {
      lost = true;
    }
    if (lost) {
      int skipTo = maxSeq;
      for (Integer k : frags.keySet()) skipTo = Math.min(skipTo, k);
      for (Integer k : readyRecords.keySet()) skipTo = Math.min(skipTo, k);
      frags.keySet().removeIf(k -> k < skipTo);
      readyRecords.keySet().removeIf(k -> k < skipTo);
      nextExpectedSeq = skipTo;
      if (onPacketLoss != null) onPacketLoss.run();
    }
    // 按序交付
    while (readyRecords.containsKey(nextExpectedSeq)) {
      byte[] rec = readyRecords.remove(nextExpectedSeq);
      try {
        recordQueue.put(rec);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      nextExpectedSeq++;
    }
  }

  // ---- 上层读取接口（阻塞，模拟 TCP 字节流）----

  public byte readByte() throws InterruptedException {
    ensureData(1);
    return curRecord[curPos++];
  }

  public int readInt() throws InterruptedException {
    ensureData(4);
    int v = ((curRecord[curPos] & 0xFF) << 24)
        | ((curRecord[curPos + 1] & 0xFF) << 16)
        | ((curRecord[curPos + 2] & 0xFF) << 8)
        | (curRecord[curPos + 3] & 0xFF);
    curPos += 4;
    return v;
  }

  public ByteBuffer readByteArray(int size) throws InterruptedException {
    ensureData(size);
    byte[] out = new byte[size];
    System.arraycopy(curRecord, curPos, out, 0, size);
    curPos += size;
    return ByteBuffer.wrap(out);
  }

  public ByteBuffer readFrame() throws InterruptedException {
    int size = readInt();
    if (size < 0 || size > 50 * 1024 * 1024) {
      throw new InterruptedException("invalid frame size: " + size);
    }
    return readByteArray(size);
  }

  private void ensureData(int n) throws InterruptedException {
    while (curRecord == null || (curRecord.length - curPos) < n) {
      if (curRecord != null && curPos >= curRecord.length) curRecord = null;
      if (curRecord == null) {
        curRecord = recordQueue.take(); // 阻塞等待下一个完整记录
        curPos = 0;
      }
    }
  }

  public void close() {
    closed = true;
    if (recvThread != null) recvThread.interrupt();
    try {
      socket.close();
    } catch (Exception ignored) {
    }
  }

  private static final class FrameFrag {
    final byte[][] chunks;
    int received = 0;
    long lastUpdate = System.currentTimeMillis();

    FrameFrag(int total) {
      chunks = new byte[total][];
    }
  }
}
