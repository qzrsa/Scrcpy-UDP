package qzrs.Scrcpy.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * STUN客户端 - 用于获取公网IP和端口,支持NAT穿透
 */
public class StunClient {
    
    // STUN服务器列表(按优先级排序)
    private static final String[] STUN_SERVERS = {
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302", 
        "stun2.l.google.com:19302",
        "stun.cloudflare.com:3478"
    };
    
    // STUN响应超时
    private static final int STUN_TIMEOUT = 3000;
    
    /**
     * NAT信息
     */
    public static class NatInfo {
        public String publicIp;
        public int publicPort;
        public String natType = "Unknown";
        public boolean success = false;
        public String errorMessage;
        
        @Override
        public String toString() {
            if (success) {
                return "NatInfo{ip=" + publicIp + ", port=" + publicPort + ", type=" + natType + "}";
            }
            return "NatInfo{failed: " + errorMessage + "}";
        }
    }
    
    /**
     * 查询公网IP地址(通过STUN)
     * @return NatInfo 包含公网IP和端口
     */
    public NatInfo queryPublicAddress() {
        NatInfo result = new NatInfo();
        
        for (String server : STUN_SERVERS) {
            try {
                String[] parts = server.split(":");
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                
                NatInfo info = queryStunServer(host, port);
                if (info.success) {
                    return info;
                }
                result.errorMessage = info.errorMessage;
            } catch (Exception e) {
                result.errorMessage = e.getMessage();
            }
        }
        
        result.success = false;
        return result;
    }
    
    /**
     * 查询单个STUN服务器
     */
    private NatInfo queryStunServer(String host, int port) throws IOException {
        NatInfo info = new NatInfo();
        
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(STUN_TIMEOUT);
        
        try {
            // 1. 发送STUN Binding Request
            byte[] request = buildBindingRequest();
            DatagramPacket requestPacket = new DatagramPacket(
                request, request.length,
                InetAddress.getByName(host), port
            );
            socket.send(requestPacket);
            
            // 2. 接收STUN Response
            byte[] response = new byte[512];
            DatagramPacket responsePacket = new DatagramPacket(response, response.length);
            socket.receive(responsePacket);
            
            // 3. 解析响应
            info = parseBindingResponse(responsePacket);
            info.success = true;
            
        } finally {
            socket.close();
        }
        
        return info;
    }
    
    /**
     * 构建STUN Binding Request
     */
    private byte[] buildBindingRequest() {
        ByteBuffer buffer = ByteBuffer.allocate(20);
        
        // STUN Message Header (20 bytes)
        buffer.putShort((short) 0x0001);     // Message Type: Binding Request (0x0001)
        buffer.putShort((short) 0x0000);     // Message Length: 0
        buffer.putInt(0x2112A442);           // Magic Cookie
        buffer.put(new byte[12]);            // Transaction ID (12 bytes)
        
        return buffer.array();
    }
    
    /**
     * 解析STUN Binding Response
     */
    private NatInfo parseBindingResponse(DatagramPacket packet) {
        NatInfo info = new NatInfo();
        ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
        
        // 跳过STUN header (20 bytes)
        buffer.position(20);
        
        // 遍历attributes查找MAPPED-ADDRESS
        while (buffer.remaining() >= 4) {
            int attrType = buffer.getShort() & 0xFFFF;
            int attrLen = buffer.getShort() & 0xFFFF;
            
            if (attrType == 0x0001) { // MAPPED-ADDRESS
                buffer.get(); // Address family (1 byte)
                info.publicPort = (buffer.getShort() & 0xFFFF);
                byte[] ip = new byte[4];
                buffer.get(ip);
                info.publicIp = String.format("%d.%d.%d.%d",
                    ip[0] & 0xFF, ip[1] & 0xFF, ip[2] & 0xFF, ip[3] & 0xFF);
                break;
            } else if (attrType == 0x8020) { // XOR-MAPPED-ADDRESS (新版STUN)
                buffer.get(); // Skip padding and family
                int xport = buffer.getShort() & 0xFFFF;
                info.publicPort = xport ^ (0x2112A442 >> 16);
                byte[] ip = new byte[4];
                buffer.get(ip);
                info.publicIp = String.format("%d.%d.%d.%d",
                    (ip[0] & 0xFF) ^ (0x21 & 0xFF),
                    (ip[1] & 0xFF) ^ (0x12 & 0xFF),
                    (ip[2] & 0xFF) ^ (0xA4 & 0xFF),
                    (ip[3] & 0xFF) ^ (0x42 & 0xFF));
                break;
            }
            
            // 移动到下一个attribute (4字节对齐)
            int paddedLen = (attrLen + 3) & ~3;
            if (buffer.remaining() < paddedLen) break;
            buffer.position(buffer.position() + paddedLen);
        }
        
        return info;
    }
    
    /**
     * 检测NAT类型
     * @return NAT类型描述
     */
    public String detectNatType() {
        // 简化实现: 使用端口变化检测NAT类型
        try {
            NatInfo info1 = queryPublicAddress();
            if (!info1.success) return "Unknown";
            
            // 这里应该发送多个请求检测端口变化
            // 简化返回
            return "Cone NAT (assumed)";
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    /**
     * 测试是否可以直接与远程地址通信(UDP打洞测试)
     */
    public boolean testConnectivity(String targetIp, int targetPort) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(1000);
            
            // 发送探测包
            byte[] probe = new byte[]{'P', 'I', 'N', 'G'};
            DatagramPacket sendPacket = new DatagramPacket(
                probe, probe.length,
                InetAddress.getByName(targetIp), targetPort
            );
            socket.send(sendPacket);
            
            // 尝试接收响应
            byte[] response = new byte[64];
            DatagramPacket recvPacket = new DatagramPacket(response, response.length);
            socket.receive(recvPacket);
            
            return true; // 收到响应说明可以直连
            
        } catch (Exception e) {
            return false; // 无法直连
        } finally {
            if (socket != null) socket.close();
        }
    }
    
    /**
     * 获取本地私有IP
     */
    public static String getLocalIpAddress() {
        try {
            java.net.Enumeration<java.net.NetworkInterface> interfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                
                // 跳过loopback和无效接口
                if (ni.isLoopback() || !ni.isUp()) continue;
                
                java.net.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    
                    // 只取IPv4地址
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        // 排除本地和组播地址
                        if (!ip.startsWith("127.") && !ip.startsWith("224.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return "127.0.0.1";
    }
}
