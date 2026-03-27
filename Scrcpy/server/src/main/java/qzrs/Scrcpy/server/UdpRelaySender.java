package qzrs.Scrcpy.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 服务端UDP中继发送器
 * 负责将视频数据通过UDP中继服务器发送给客户端
 */
public class UdpRelaySender {

    private static final String RELAY_HOST = "47.105.67.198";
    private static final int RELAY_PORT = 3479;
    private static final int MAX_UDP_PACKET = 65000; // UDP最大包大小
    private static final int HEADER_SIZE = 8; // 包头：4字节序号 + 4字节总长度

    private DatagramSocket socket;
    private InetAddress relayAddress;
    private String sessionId;
    private int packetSeq = 0;
    private boolean connected = false;

    public UdpRelaySender() {
    }

    /**
     * 连接到UDP中继服务器
     */
    public boolean connect(String deviceId) {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(5000);
            relayAddress = InetAddress.getByName(RELAY_HOST);

            // 发送注册包: [0x01][deviceId:32字节][预留:31字节]
            byte[] regPacket = new byte[64];
            regPacket[0] = 0x01;
            byte[] idBytes = deviceId.getBytes();
            int copyLen = Math.min(idBytes.length, 32);
            System.arraycopy(idBytes, 0, regPacket, 1, copyLen);

            DatagramPacket sendPkt = new DatagramPacket(regPacket, regPacket.length, relayAddress, RELAY_PORT);
            socket.send(sendPkt);

            // 等待确认: [0x02][sessionId:16字节]
            byte[] respBuf = new byte[17];
            DatagramPacket respPkt = new DatagramPacket(respBuf, respBuf.length);
            socket.receive(respPkt);

            if (respBuf[0] == 0x02) {
                sessionId = new String(respBuf, 1, 16).trim();
                connected = true;
                socket.setSoTimeout(0); // 取消超时
                System.out.println("[UDP] 注册成功, sessionId=" + sessionId);
                return true;
            }
        } catch (Exception e) {
            System.out.println("[UDP] 连接失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 发送视频数据（自动分片）
     */
    public void sendVideo(ByteBuffer data) throws IOException {
        if (!connected || socket == null) return;

        byte[] bytes = data.array();
        int totalLen = data.remaining();
        int offset = data.position();

        // 计算分片数
        int payloadSize = MAX_UDP_PACKET - HEADER_SIZE;
        int totalFragments = (totalLen + payloadSize - 1) / payloadSize;

        for (int i = 0; i < totalFragments; i++) {
            int fragOffset = offset + i * payloadSize;
            int fragLen = Math.min(payloadSize, totalLen - i * payloadSize);

            // 包格式: [seq:4][totalLen:4][fragIndex:2][totalFrags:2][data:N]
            byte[] packet = new byte[12 + fragLen];
            // 序号
            packet[0] = (byte)(packetSeq >> 24);
            packet[1] = (byte)(packetSeq >> 16);
            packet[2] = (byte)(packetSeq >> 8);
            packet[3] = (byte)(packetSeq);
            // 总长度
            packet[4] = (byte)(totalLen >> 24);
            packet[5] = (byte)(totalLen >> 16);
            packet[6] = (byte)(totalLen >> 8);
            packet[7] = (byte)(totalLen);
            // 分片索引
            packet[8] = (byte)(i >> 8);
            packet[9] = (byte)(i);
            // 总分片数
            packet[10] = (byte)(totalFragments >> 8);
            packet[11] = (byte)(totalFragments);
            // 数据
            System.arraycopy(bytes, fragOffset, packet, 12, fragLen);

            DatagramPacket udpPkt = new DatagramPacket(packet, packet.length, relayAddress, RELAY_PORT);
            socket.send(udpPkt);
        }
        packetSeq++;
    }

    /**
     * 关闭连接
     */
    public void close() {
        connected = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
