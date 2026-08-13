# Scrcpy 视频通道 UDP 化改造说明

> 仓库：`qzrsa/Scrcpy`（派生自 EasyControl 的 Android 端 scrcpy 客户端/服务端）
> 目标：**视频流改走 UDP（允许丢包、低延迟），控制/心跳/事件仍走 TCP**，并保留 USB/adb 模式兼容。

## 一、架构变化

| 通道 | 改造前 | 改造后 |
|---|---|---|
| 控制 / 心跳 / 音频 / 剪贴板 / 视频尺寸事件（main 通道） | TCP | **不变（TCP）** |
| 视频流（直连模式，无线 IP 直连） | TCP | **UDP（分片 + 重组 + 丢帧跳过 + IDR 恢复）** |
| 视频流（USB / adb 模式） | TCP（adb tcp 转发） | **不变（TCP）** |

说明：adb 不支持 UDP 转发，所以 USB/adb 模式无法走 UDP；只有「无线 IP 直连」（`device.isLinkDevice() == false`）启用 UDP 视频，其余保持原 TCP，确保功能不回退。

## 二、文件改动清单

1. **`server/.../Server.java`**
   - `connectClient()`：main 仍走 TCP `accept()`；新增 `DatagramSocket(serverPort+1)` 监听视频 UDP；启动握手线程（记录客户端地址）与兼容用的 TCP 视频 `accept` 线程。
   - `writeVideo()`：若 UDP 客户端已握手 → 分片 `sendUdpVideo()`；否则回退原 TCP 写入；两者皆未就绪则丢弃该帧（由 IDR 机制恢复）。
   - 新增 `ensureUdpClient()`（握手等待，2s 超时，避免首帧丢失）、`sendUdpVideo()`（按 1400B 分片，封装帧头）。
   - `executeControlIn()` 新增 `case 10`：收到客户端丢帧通知 → 调用 `VideoEncode.requestSync()`。
   - `release()` 关闭 `videoUdpSocket` / `serverSocket`。

2. **`server/.../helper/VideoEncode.java`**
   - 新增 `requestSync()`：运行时请求输出关键帧（IDR）。API≥23 用 `MediaCodec.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)`；低版本降级为重建编码器。

3. **`client/.../tools/ClientStream.java`**
   - 直连分支（`!isLinkDevice()`）：main 仍连 TCP；视频改为 `new DatagramSocket()` + 握手到 `serverPort+1` + 新建 `UdpVideoReceiver`。新增 `useUdpVideo` 标志。
   - 视频读取方法（`readByteFromVideo`/`readIntFromVideo`/`readByteArrayFromVideo`/`readFrameFromVideo`）改为三态：UDP → `UdpVideoReceiver`；直连 TCP → 原 socket；adb → 原 `BufferStream`。
   - 新增 `requestVideoIdr()`：经 main TCP 通道发送控制字节 `10`。
   - `close()`：按模式分别关闭 UDP / TCP 视频与 main 通道。

4. **`client/.../tools/UdpVideoReceiver.java`（新建）**
   - 后台线程接收 UDP 包，按 `frameSeq` 重组分片为完整记录（与原 TCP 字节流一一对应）。
   - 检测丢帧：序号缺口或重组超时（200ms）→ 跳到最小可用序号并回调 `onPacketLoss`（即 `requestVideoIdr`）。
   - 向上层提供阻塞式 `readByte()/readInt()/readByteArray()/readFrame()`，对外语义与 TCP 字节流完全一致 → **`ClientPlayer.videoStreamIn` 零改动**。

## 三、UDP 帧格式

每个 UDP 包 payload：

```
type(1) | frameSeq(4, 大端) | fragIdx(2) | fragTotal(2) | chunk(≤1400)
```

- 每条 `Server.writeVideo` 记录（9 字节头 / `[size][pts][data]` 帧）对应一个 `frameSeq`，按 `UDP_CHUNK=1400` 切片。
- 客户端按 `frameSeq` 重组，按序吐出完整记录，供上层像读 TCP 流一样消费。

## 四、丢帧 → 关键帧恢复链路

```
UDP 丢包（缺口/超时）
  → UdpVideoReceiver.onPacketLoss
  → ClientStream.requestVideoIdr()  → main TCP 写字节 10
  → Server.executeControlIn case 10 → VideoEncode.requestSync()
  → 编码器立即输出 IDR  → 客户端解码恢复（无需重传）
```

## 五、兼容性

- **无线 IP 直连**：控制 TCP + 视频 UDP（本改造启用）。
- **USB / 无线 adb**：控制与视频均走原 TCP（adb 隧道），未受影响。
- 端口占用：TCP `serverPort`（控制）+ UDP `serverPort+1`（视频），无需额外配置。

## 六、使用与验证建议

- 在 Android Studio 中打开 `Scrcpy/` 模块编译（需 Android SDK）。本改造**未做编译验证**，仅完成逻辑与语法自检。
- 测试方法：用两台 Android 设备，「网络/无线直连」方式连接，观察投屏是否流畅；在弱网（限速/丢包）下对比改造前后卡顿与延迟（StatsOverlay 有 RTT 显示）。
- 排查要点：若视频黑屏，先确认 UDP 握手（`serverPort+1`）未被系统防火墙/SELinux 拦截；可在 `ensureUdpClient` 超时分支加日志确认握手是否到达。

## 七、已知边界

- 首帧（9 字节头）依赖握手先完成；时序上客户端握手在 main TCP 连上后立即发送，服务端 `accept` 后即启动握手线程，正常情况下首帧不丢。若握手失败（UDP 被拦），该模式视频不可用（建议此时回退 TCP，可作为后续增强）。
- UDP 无拥塞控制，弱网极端情况下仍可能出现花屏后由 IDR 恢复；可后续结合 RTT/丢包率动态下调 `maxVideoBit`。
