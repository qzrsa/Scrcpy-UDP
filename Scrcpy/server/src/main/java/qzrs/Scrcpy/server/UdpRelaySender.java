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
        int payloadSize = MAX_UDP_PACKET - 20; // 减去包头大小
        int totalFragments = (totalLen + payloadSize - 1) / payloadSize;

        for (int i = 0; i < totalFragments; i++) {
            int fragOffset = offset + i * payloadSize;
            int fragLen = Math.min(payloadSize, totalLen - i * payloadSize);

            // 包格式: [类型:1][会话ID:16][seq:4][fragIndex:2][totalFrags:2][data:N]
            byte[] packet = new byte[1 + 16 + 4 + 2 + 2 + fragLen];
            int pos = 0;
            
            // 类型: 0x03 (设备->客户端)
            packet[pos++] = 0x03;
            
            // 会话ID (16字节)
            byte[] sessionIdBytes = sessionId.getBytes();
            int copyLen = Math.min(sessionIdBytes.length, 16);
            System.arraycopy(sessionIdBytes, 0, packet, pos, copyLen);
            pos += 16;
            
            // 序号 (4字节)
            packet[pos++] = (byte)(packetSeq >> 24);
            packet[pos++] = (byte)(packetSeq >> 16);
            packet[pos++] = (byte)(packetSeq >> 8);
            packet[pos++] = (byte)(packetSeq);
            
            // 分片索引 (2字节)
            packet[pos++] = (byte)(i >> 8);
            packet[pos++] = (byte)(i);
            
            // 总分片数 (2字节)
            packet[pos++] = (byte)(totalFragments >> 8);
            packet[pos++] = (byte)(totalFragments);
            
            // 数据
            System.arraycopy(bytes, fragOffset, packet, pos, fragLen);

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
