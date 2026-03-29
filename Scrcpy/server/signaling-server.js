/**
 * Scrcpy UDP 信令服务器
 * 
 * 功能:
 * 1. 设备注册和管理
 * 2. P2P连接地址交换
 * 3. 中继服务(可选)
 * 
 * 端口分配:
 * - 8080: WebSocket信令服务
 * - 3478: STUN/TURN服务(需要coturn)
 * - 3479: UDP中继数据端口
 * - 3480: TCP中继备用端口
 */

const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

// ============ 配置 ============
const CONFIG = {
    port: 8888,
    heartbeatInterval: 30000,      // 心跳间隔(ms)
    heartbeatTimeout: 60000,       // 心跳超时(ms)
    maxDevices: 100,              // 最大设备数
    relayEnabled: true,           // 是否启用中继
    relayPort: 3479,              // 中继UDP端口
};

// ============ 全局状态 ============
const devices = new Map();         // deviceId -> DeviceInfo
const clients = new Map();         // ws -> ClientInfo
const connections = new Map();     // connectionId -> ConnectionInfo

let udpRelaySocket = null;         // UDP中继Socket
let relaySessions = new Map();    // sessionId -> RelaySession

// ============ 日志 ============
function log(type, ...args) {
    const timestamp = new Date().toISOString();
    console.log(`[${timestamp}] [${type}]`, ...args);
}

// ============ WebSocket服务器 ============
const server = http.createServer((req, res) => {
    // 健康检查
    if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'ok',
            devices: devices.size,
            clients: clients.size,
            connections: connections.size
        }));
        return;
    }
    
    // 统计页面
    if (req.url === '/stats') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(generateStatsPage());
        return;
    }
    
    res.writeHead(404);
    res.end('Not Found');
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
    const clientId = generateId();
    const clientInfo = {
        id: clientId,
        ws: ws,
        type: null,        // 'device' or 'client'
        peerId: null,
        deviceId: null,
        lastHeartbeat: Date.now(),
        natInfo: null
    };
    
    clients.set(clientId, clientInfo);
    log('CONNECTION', `New connection: ${clientId}`);
    
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            handleMessage(clientId, data);
        } catch (e) {
            log('ERROR', `Invalid message from ${clientId}:`, e.message);
        }
    });
    
    ws.on('close', () => {
        handleDisconnect(clientId);
        clients.delete(clientId);
        log('CONNECTION', `Connection closed: ${clientId}`);
    });
    
    ws.on('error', (e) => {
        log('ERROR', `WebSocket error for ${clientId}:`, e.message);
    });
    
    // 发送客户端ID
    ws.send(JSON.stringify({
        type: 'connected',
        clientId: clientId
    }));
});

// ============ 消息处理 ============
function handleMessage(clientId, data) {
    const client = clients.get(clientId);
    if (!client) return;
    
    client.lastHeartbeat = Date.now();
    
    switch (data.type) {
        case 'register-device':
            handleRegisterDevice(clientId, data);
            break;
            
        case 'register-client':
            handleRegisterClient(clientId, data);
            break;
            
        case 'get-devices':
            handleGetDevices(clientId);
            break;
            
        case 'request-connection':
            handleRequestConnection(clientId, data);
            break;
            
        case 'peer-info':
            handlePeerInfo(clientId, data);
            break;
            
        case 'ice-candidate':
            handleIceCandidate(clientId, data);
            break;
            
        case 'heartbeat':
            // 心跳响应
            break;
            
        case 'nat-report':
            // NAT类型报告
            client.natInfo = data.natInfo;
            log('NAT', `Device ${client.deviceId} NAT: ${JSON.stringify(data.natInfo)}`);
            break;
            
        default:
            log('WARNING', `Unknown message type: ${data.type}`);
    }
}

// ============ 设备注册 ============
function handleRegisterDevice(clientId, data) {
    const client = clients.get(clientId);
    const deviceId = data.deviceId || generateId();
    
    client.type = 'device';
    client.deviceId = deviceId;
    client.natInfo = data.natInfo || {};
    
    devices.set(deviceId, {
        deviceId: deviceId,
        clientId: clientId,
        name: data.name || 'Unknown Device',
        natInfo: data.natInfo || {},
        registeredAt: Date.now(),
        lastSeen: Date.now()
    });
    
    log('DEVICE', `Device registered: ${deviceId}`);
    
    // 发送确认
    client.ws.send(JSON.stringify({
        type: 'registered',
        deviceId: deviceId
    }));
    
    // 广播设备列表更新
    broadcastDeviceList();
}

// ============ 客户端注册 ============
function handleRegisterClient(clientId, data) {
    const client = clients.get(clientId);
    
    client.type = 'client';
    client.name = data.name || 'Unknown Client';
    
    log('CLIENT', `Client registered: ${clientId}`);
    
    // 发送设备列表
    handleGetDevices(clientId);
}

// ============ 获取设备列表 ============
function handleGetDevices(clientId) {
    const client = clients.get(clientId);
    
    const deviceList = Array.from(devices.values()).map(d => ({
        deviceId: d.deviceId,
        name: d.name,
        natInfo: d.natInfo
    }));
    
    client.ws.send(JSON.stringify({
        type: 'device-list',
        devices: deviceList
    }));
}

// ============ 请求连接 ============
function handleRequestConnection(clientId, data) {
    const requester = clients.get(clientId);
    const targetDeviceId = data.deviceId;
    
    const targetDevice = devices.get(targetDeviceId);
    if (!targetDevice) {
        requester.ws.send(JSON.stringify({
            type: 'error',
            message: 'Device not found'
        }));
        return;
    }
    
    const targetClient = clients.get(targetDevice.clientId);
    if (!targetClient || !targetClient.ws) {
        requester.ws.send(JSON.stringify({
            type: 'error',
            message: 'Device is offline'
        }));
        return;
    }
    
    // 创建连接记录
    const connectionId = generateId();
    const connection = {
        id: connectionId,
        deviceId: targetDeviceId,
        deviceClientId: targetDevice.clientId,
        clientId: clientId,
        createdAt: Date.now()
    };
    connections.set(connectionId, connection);
    
    // 设置对等关系
    requester.peerId = targetDevice.clientId;
    targetClient.peerId = clientId;
    
    // 通知设备有连接请求
    targetClient.ws.send(JSON.stringify({
        type: 'connection-request',
        connectionId: connectionId,
        clientId: clientId,
        clientName: requester.name || 'Unknown'
    }));
    
    // 通知请求者正在等待
    requester.ws.send(JSON.stringify({
        type: 'waiting-for-approval',
        deviceId: targetDeviceId,
        deviceName: targetDevice.name
    }));
    
    log('CONNECTION', `Connection requested: ${clientId} -> ${targetDeviceId}`);
}

// ============ 对等信息交换 ============
function handlePeerInfo(clientId, data) {
    const client = clients.get(clientId);
    
    // 转发给对方
    if (client.peerId) {
        const peer = clients.get(client.peerId);
        if (peer && peer.ws) {
            peer.ws.send(JSON.stringify({
                type: 'peer-info',
                ip: data.ip,
                port: data.port,
                natInfo: data.natInfo
            }));
        }
    }
}

// ============ ICE候选 ============
function handleIceCandidate(clientId, data) {
    const client = clients.get(clientId);
    
    if (client.peerId) {
        const peer = clients.get(client.peerId);
        if (peer && peer.ws) {
            peer.ws.send(JSON.stringify({
                type: 'ice-candidate',
                candidate: data.candidate
            }));
        }
    }
}

// ============ 断开处理 ============
function handleDisconnect(clientId) {
    const client = clients.get(clientId);
    
    if (client.type === 'device' && client.deviceId) {
        devices.delete(client.deviceId);
        broadcastDeviceList();
        log('DEVICE', `Device disconnected: ${client.deviceId}`);
    }
    
    // 通知对方断开
    if (client.peerId) {
        const peer = clients.get(client.peerId);
        if (peer && peer.ws) {
            peer.ws.send(JSON.stringify({
                type: 'peer-disconnected'
            }));
            peer.peerId = null;
        }
    }
}

// ============ 中继服务 ============
function startRelayServer() {
    if (!CONFIG.relayEnabled) return;
    
    try {
        udpRelaySocket = require('dgram').createSocket('udp4');
        udpRelaySocket.bind(CONFIG.relayPort);
        
        udpRelaySocket.on('listening', () => {
            log('RELAY', `UDP relay listening on port ${CONFIG.relayPort}`);
        });
        
        udpRelaySocket.on('message', (msg, rinfo) => {
            handleRelayMessage(msg, rinfo);
        });
        
        udpRelaySocket.on('error', (e) => {
            log('ERROR', `Relay socket error: ${e.message}`);
        });
        
    } catch (e) {
        log('ERROR', `Failed to start relay server: ${e.message}`);
    }
}

function handleRelayMessage(msg, rinfo) {
    // 消息格式: [类型:1字节][数据:N字节]
    const type = msg[0];
    
    if (type === 0x01) {
        // 注册包: [0x01][设备ID:32字节]
        const deviceId = msg.slice(1, 33).toString('ascii').trim();
        log('RELAY', `UDP registration from ${rinfo.address}:${rinfo.port} - Device: "${deviceId}"`);
        
        // 检查是否已有相同deviceId的session
        let existingSession = null;
        for (const [sid, session] of relaySessions) {
            if (session.deviceId === deviceId) {
                existingSession = { sid, session };
                break;
            }
        }
        
        if (existingSession && !existingSession.session.clientPort) {
            // 已有服务端session，客户端注册加入
            existingSession.session.clientIp = rinfo.address;
            existingSession.session.clientPort = rinfo.port;
            
            // 发送确认，使用16字节sessionId
            const response = Buffer.alloc(17);
            response[0] = 0x02;
            const sessionIdBytes = Buffer.alloc(16);
            sessionIdBytes.write(existingSession.sid);
            sessionIdBytes.copy(response, 1);
            udpRelaySocket.send(response, rinfo.port, rinfo.address);
            log('RELAY', `Client joined session: ${existingSession.sid} (total sessions: ${relaySessions.size})`);
        } else if (existingSession && existingSession.session.clientPort) {
            // 已有客户端session，服务端注册加入
            existingSession.session.deviceIp = rinfo.address;
            existingSession.session.devicePort = rinfo.port;
            
            // 发送确认，使用16字节sessionId
            const response = Buffer.alloc(17);
            response[0] = 0x02;
            const sessionIdBytes = Buffer.alloc(16);
            sessionIdBytes.write(existingSession.sid);
            sessionIdBytes.copy(response, 1);
            udpRelaySocket.send(response, rinfo.port, rinfo.address);
            log('RELAY', `Server joined session: ${existingSession.sid} (total sessions: ${relaySessions.size})`);
        } else {
            // 没有session，创建新的
            const sessionId = generateId();
            // 存储时清理null字符
            const cleanSessionId = sessionId.replace(/\0+$/, '').trim();
            relaySessions.set(cleanSessionId, {
                deviceId: deviceId,
                clientIp: null,
                clientPort: null,
                deviceIp: rinfo.address,
                devicePort: rinfo.port,
                createdAt: Date.now()
            });
            
            // 发送确认，使用16字节sessionId（不足的用null填充）
            const response = Buffer.alloc(17);
            response[0] = 0x02;
            const sessionIdBytes = Buffer.alloc(16);
            sessionIdBytes.write(cleanSessionId);
            sessionIdBytes.copy(response, 1);
            udpRelaySocket.send(response, rinfo.port, rinfo.address);
            log('RELAY', `Registered new session: "${cleanSessionId}" (deviceId="${deviceId}", total sessions: ${relaySessions.size})`);
        }
        return;
    }
    
    // 转发数据包: [类型:1字节][会话ID:16字节][数据:N字节]
    // 注意：sessionId可能包含null字符，需要清理
    const sessionId = msg.slice(1, 17).toString('ascii').replace(/\0+$/, '').trim();
    log('RELAY', `Forwarding packet: type=${type}, sessionId="${sessionId}", sessions=${relaySessions.size}`);
    
    const session = relaySessions.get(sessionId);
    if (!session) {
        log('WARNING', `Unknown relay session: "${sessionId}"`);
        return;
    }
    
    const data = msg.slice(17);
    
    // 转发给对方
    if (type === 0x03) { // 设备 -> 客户端
        if (session.clientIp && session.clientPort) {
            udpRelaySocket.send(data, session.clientPort, session.clientIp);
        } else {
            log('WARNING', `No client address for session ${sessionId}`);
        }
    } else if (type === 0x04) { // 客户端 -> 设备
        udpRelaySocket.send(data, session.devicePort, session.deviceIp);
    }
}

// ============ 工具函数 ============
function generateId() {
    return Math.random().toString(36).substring(2, 18);
}

function broadcastDeviceList() {
    const deviceList = Array.from(devices.values()).map(d => ({
        deviceId: d.deviceId,
        name: d.name,
        natInfo: d.natInfo
    }));
    
    clients.forEach((client) => {
        if (client.type === 'client' && client.ws.readyState === WebSocket.OPEN) {
            client.ws.send(JSON.stringify({
                type: 'device-list',
                devices: deviceList
            }));
        }
    });
}

function generateStatsPage() {
    const now = Date.now();
    return `
<!DOCTYPE html>
<html>
<head>
    <title>Scrcpy UDP 信令服务器</title>
    <meta charset="utf-8">
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; background: #1a1a2e; color: #eee; }
        h1 { color: #00d4ff; }
        h2 { color: #00d4ff; margin-top: 30px; }
        .stats { display: flex; gap: 20px; flex-wrap: wrap; margin: 20px 0; }
        .stat-card { background: #16213e; padding: 20px; border-radius: 8px; min-width: 150px; text-align: center; border: 1px solid #0f3460; }
        .stat-num { font-size: 36px; font-weight: bold; color: #00d4ff; }
        .stat-label { color: #aaa; margin-top: 5px; }
        table { width: 100%; border-collapse: collapse; background: #16213e; border-radius: 8px; overflow: hidden; }
        th { background: #0f3460; padding: 12px; text-align: left; color: #00d4ff; }
        td { padding: 10px 12px; border-bottom: 1px solid #0f3460; }
        tr:hover td { background: #0f3460; }
        .badge { padding: 3px 8px; border-radius: 4px; font-size: 12px; }
        .badge-green { background: #00b894; color: white; }
        .badge-blue { background: #0984e3; color: white; }
        .badge-gray { background: #636e72; color: white; }
        .refresh { color: #aaa; font-size: 12px; margin-top: 20px; }
    </style>
</head>
<body>
    <h1>🖥️ Scrcpy UDP 信令服务器</h1>
    
    <div class="stats">
        <div class="stat-card">
            <div class="stat-num">${devices.size}</div>
            <div class="stat-label">在线设备</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${clients.size}</div>
            <div class="stat-label">WebSocket连接</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${relaySessions.size}</div>
            <div class="stat-label">UDP中继会话</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${formatUptime(process.uptime())}</div>
            <div class="stat-label">运行时间</div>
        </div>
    </div>

    <h2>📱 WebSocket 在线设备</h2>
    <table>
        <tr>
            <th>设备ID</th>
            <th>名称</th>
            <th>NAT类型</th>
            <th>注册时间</th>
        </tr>
        ${Array.from(devices.values()).map(d => `
            <tr>
                <td><code>${d.deviceId}</code></td>
                <td>${d.name}</td>
                <td><span class="badge badge-blue">${(d.natInfo && d.natInfo.natType) || 'Unknown'}</span></td>
                <td>${new Date(d.registeredAt).toLocaleString()}</td>
            </tr>
        `).join('') || '<tr><td colspan="4" style="color:#aaa;text-align:center">暂无设备</td></tr>'}
    </table>

    <h2>🔗 UDP 中继会话</h2>
    <table>
        <tr>
            <th>会话ID</th>
            <th>设备ID</th>
            <th>IP地址</th>
            <th>端口</th>
            <th>创建时间</th>
        </tr>
        ${Array.from(relaySessions.entries()).map(([sid, s]) => `
            <tr>
                <td><code>${sid}</code></td>
                <td>${s.deviceId || '-'}</td>
                <td>${s.clientIp}</td>
                <td>${s.clientPort}</td>
                <td>${new Date(s.createdAt).toLocaleString()}</td>
            </tr>
        `).join('') || '<tr><td colspan="5" style="color:#aaa;text-align:center">暂无UDP会话</td></tr>'}
    </table>

    <h2>🌐 WebSocket 连接列表</h2>
    <table>
        <tr>
            <th>连接ID</th>
            <th>类型</th>
            <th>设备ID</th>
            <th>最后心跳</th>
        </tr>
        ${Array.from(clients.entries()).map(([cid, c]) => `
            <tr>
                <td><code>${cid}</code></td>
                <td><span class="badge ${c.type === 'device' ? 'badge-green' : c.type === 'client' ? 'badge-blue' : 'badge-gray'}">${c.type || 'unknown'}</span></td>
                <td>${c.deviceId || '-'}</td>
                <td>${Math.round((now - c.lastHeartbeat) / 1000)}秒前</td>
            </tr>
        `).join('') || '<tr><td colspan="4" style="color:#aaa;text-align:center">暂无连接</td></tr>'}
    </table>

    <p class="refresh">⏱ 每5秒自动刷新</p>
    <script>setTimeout(() => location.reload(), 5000);</script>
</body>
</html>`;
}

function formatUptime(seconds) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    return `${h}h ${m}m ${s}s`;
}

// ============ 心跳检测 ============
setInterval(() => {
    const now = Date.now();
    
    clients.forEach((client, clientId) => {
        if (now - client.lastHeartbeat > CONFIG.heartbeatTimeout) {
            log('WARNING', `Client heartbeat timeout: ${clientId}`);
            client.ws.close();
        }
    });
}, 10000);

// ============ 启动 ============
server.listen(CONFIG.port, () => {
    log('SERVER', `Signaling server started on port ${CONFIG.port}`);
});

startRelayServer();

// 优雅退出
process.on('SIGINT', () => {
    log('SERVER', 'Shutting down...');
    wss.close();
    server.close();
    if (udpRelaySocket) udpRelaySocket.close();
    process.exit(0);
});

module.exports = { server, CONFIG };
