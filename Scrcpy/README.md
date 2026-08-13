# Scrcpy-UDP

Android 端的 Scrcpy 投屏客户端（派生自 [EasyControl](https://github.com/mingmingdev/EasyControl)），**将屏幕镜像的视频流改为 UDP 传输以降低延迟**，控制指令、心跳、音频与事件仍走可靠的 TCP 通道；USB / adb 模式完整兼容、继续走原 TCP。

> ⚠️ 必须给悬浮窗权限！（原项目要求，UDP 化未改变此约束）

## 与原版的核心区别

| 通道 | 原版 | Scrcpy-UDP |
|---|---|---|
| 视频流（屏幕镜像） | TCP（ServerSocket + accept 两次） | **UDP 分片发送**（直连模式）/ TCP 回退（adb/USB 模式） |
| 控制 / 心跳 / 音频 / 事件 | TCP（main 通道） | TCP（不变） |
| USB / 无线 adb 模式 | TCP | TCP（不变，adb 不支持 UDP 转发） |

视频 UDP 化的关键机制（详见 [UDP_CHANGES.md](UDP_CHANGES.md)）：

- **分片 / 重组**：每条视频记录按 1400 字节切片，封装 `type|seq|fragIdx|fragTotal|chunk`，客户端按帧序号与分片索引重组后，对外仍提供与原 TCP **完全一致**的字节流，上层解码器零改动。
- **丢帧恢复**：UDP 丢包 → 检测序列号缺口 / 200ms 超时 → 经 main TCP 发送控制字节 `10` → 服务端 `VideoEncode.requestSync()` 立即输出关键帧（IDR），无需重传。
- **去队头阻塞**：弱网下不再因单包重传拖慢整条流，实时性更好。

## 目录结构

```
Scrcpy/
├── app/      # 客户端（Android 应用，内置 server 的 APK 作为资源）
└── server/   # 运行在目标设备上的服务端（被打包进 app 的 res/raw）
```

## 构建

需要：

- **JDK 17**（本项目 Gradle wrapper 为 8.2，不兼容 JDK 21）
- **Android SDK**（compileSdk 36，build-tools 34+）
- 在 `local.properties` 中配置 `sdk.dir`（不入库）

构建顺序（server 必须先编，它会把服务端 APK 复制进 app 的 `res/raw/scrcpy_server.jar`）：

```bash
# 1) 编译并内置 server
./gradlew :server:copyRelease
# 2) 编译客户端（debug 无需签名）
./gradlew :app:assembleDebug
```

产物：

- `server/build/outputs/apk/release/server-release-unsigned.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

> 说明：`app` 的 `release` 构建需要 `../keystore.properties` 签名；`debug` 可直接安装。

## 使用

1. 在手机上安装 `app-debug.apk` 并授予悬浮窗等权限。
2. 目标设备（被控端）会被自动推送并运行内置的 server。
3. 连接方式：
   - **网络 / IP 直连**：控制走 TCP，视频走 UDP（本次启用，延迟最低）。
   - **USB / 无线 adb**：控制与视频均走 TCP（兼容原行为）。

## 致谢

- 原项目 [EasyControl](https://github.com/mingmingdev/EasyControl) / Scrcpy 思路
- 本仓库在 `qzrsa/Scrcpy` 基础上改造
