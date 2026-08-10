# Android 通话 PCM 采集与注入 Demo

这是一个面向 Android 系统开发和设备音频链路验证的实验应用，用于在 PSTN 电话通话中分别采集上下行 PCM，并测试上行音频注入和下行音频替换。

> 本项目不是面向普通三方应用的通话录音方案。核心能力依赖系统签名权限、设备 Telephony 音频能力及厂商系统实现，普通签名 APK 即使可以安装，也无法完整工作。

## 基本功能

### 1. 通话上下行自动分路采集

- 启动前台监听服务后，通过 `InCallService` 监听电话状态。
- 电话进入 `ACTIVE` 状态约 300 ms 后，自动同时采集：
  - 下行：对方发送到本机的声音，音源为 `VOICE_DOWNLINK`；
  - 上行：本机发送给对方的声音，音源为 `VOICE_UPLINK`。
- 通话结束后分别保存为原始 `.pcm` 文件，并在界面显示时长、字节数和峰值。
- 输出文件名示例：
  - `downlink_auto_20260810_120000_48000_mono_s16le.pcm`
  - `uplink_auto_20260810_120000_48000_mono_s16le.pcm`

### 2. 上行 PCM 注入

- 可在通话前从 PCM 文件列表预选音频。
- 电话接通后，将所选单声道 PCM 转成双声道并路由到 `TYPE_TELEPHONY`（Telephony Tx）。
- 注入期间真实麦克风会被静音，对方听到注入的 PCM。
- PCM 播放完后麦克风仍保持静音，点击“停止上行注入”才会恢复注入前的静音状态。

### 3. 下行 PCM 替换

- 仅支持 Android 14 及以上版本。
- 电话接通后进入 `MODE_CALL_REDIRECT`，提取并阻断真实通话下行，在本机当前通话输出设备播放所选 PCM。
- 替换期间，本机麦克风仍会桥接到通话上行，因此对方可以继续听到本机声音。
- 停止替换后恢复原始通话上下行路由，并恢复普通双路采集。

### 4. PCM 文件管理与回放

- 展示 `call_pcm` 目录中的全部 `.pcm` 文件，并支持上下行注入分别预选。
- 支持在无通话时试听和删除 PCM。
- 支持播放最近一次采集的上行或下行文件。
- 通话、采集、注入或试听期间会限制可能冲突的回放、删除和注入操作。

## PCM 格式

采集、注入和回放统一按以下无文件头原始格式处理：

| 参数 | 值 |
| --- | --- |
| 采样率 | 48 kHz |
| 采样格式 | 16-bit signed PCM |
| 字节序 | Little-endian |
| 声道 | 单声道 |
| 文件格式 | 原始 `.pcm`，无 WAV 头 |

外部导入的 PCM 必须直接放在应用的 `call_pcm` 目录中，文件名以 `.pcm` 结尾、内容非空且字节数为偶数。应用只校验路径、扩展名和 PCM16 字节对齐，无法自动判断文件实际采样率或声道数，因此格式不符时会出现变速、噪声或声道异常。

默认目录通常为：

```text
/storage/emulated/0/Android/data/com.android.example.myapplication/files/Music/call_pcm/
```

如果外部应用目录不可用，代码会回退到应用内部文件目录下的 `call_pcm`。卸载应用时，这些应用专属目录中的 PCM 文件也会被删除。

## 运行要求

### 设备与系统

- Android 11（API 30）或更高版本；下行替换要求 Android 14（API 34）或更高版本。
- 设备具备电话能力并可进行 PSTN 通话。
- 系统能够为该包绑定声明的非 UI `InCallService`。
- 上行注入要求设备声明 `TYPE_TELEPHONY` 输出（Telephony Tx）。
- 下行替换要求设备同时支持 Telephony Rx/Tx，并实现以下 Android 14+ PSTN 通话重定向能力：
  - `AudioManager.isPstnCallAudioInterceptable()`；
  - `AudioManager.getCallDownlinkExtractionAudioRecord()`；
  - `AudioManager.getCallUplinkInjectionAudioTrack()`；
  - `AudioManager.MODE_CALL_REDIRECT`。

不同厂商的 Audio HAL、Telecom 和 AudioPolicy 实现可能不支持上述路由；仅满足 Android 版本号并不代表功能一定可用。

### 权限与签名

应用声明并使用以下权限：

| 权限 | 用途 | 类型 |
| --- | --- | --- |
| `RECORD_AUDIO` | 麦克风及通话音频采集 | 运行时权限 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 | 运行时权限 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | 后台监听与采集 | 普通/前台服务权限 |
| `CAPTURE_AUDIO_OUTPUT` | `VOICE_DOWNLINK` 等系统输出采集 | 系统级权限 |
| `CONTROL_INCALL_EXPERIENCE` | 作为非 UI `InCallService` 观察和控制通话音频状态 | 系统级权限 |
| `MODIFY_PHONE_STATE` | Telephony Tx 路由等电话状态能力 | 系统级权限 |
| `CALL_AUDIO_INTERCEPTION` | PSTN 通话上下行提取和重定向 | 系统级权限 |
| `MODIFY_AUDIO_SETTINGS` | 调整通话音频模式与路由 | 普通权限 |

其中多项权限属于 `signature` 或 `signature|privileged` 级别，不能由用户在设置页手动授予。完整功能通常要求：

1. 使用目标系统的 platform key 对 APK 签名，或将其作为受信任的系统/特权应用集成；
2. 根据目标系统权限策略配置相应的 privapp allowlist；
3. 确保 Telecom、AudioService、AudioPolicy 和 Audio HAL 没有额外拦截该包或相关音频源。

应用首页会显示关键权限的当前授予状态，便于排查环境配置。

## 构建要求

- JDK 21（项目 Gradle Daemon toolchain 配置为 21）；
- Android SDK Platform 36.1；
- Android Gradle Plugin 9.1.0；
- Gradle Wrapper 9.3.1。

构建 Debug APK：

```bash
./gradlew assembleDebug
```

产物默认位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Gradle 默认生成的 Debug APK 不具备目标设备的系统签名权限。需要完整验证时，应通过目标 Android 平台的签名或系统镜像集成流程生成并安装 APK。

## 基本使用流程

1. 安装经过正确签名和系统权限配置的 APK。
2. 打开应用，确认首页列出的关键权限均已授予，并允许录音和通知权限。
3. 点击“启动自动监听”，保持前台服务运行。
4. 拨出或接听电话；接通后应用会自动开始上下行分路采集。
5. 如需注入，可提前在 PCM 列表中选择文件，通话接通后启动上行注入或下行替换。
6. 停止注入或结束通话后，在首页回放最近采集文件，或进入 PCM 列表试听、选择和删除文件。

上行注入与下行替换不能同时进行；下行替换启动时会暂停普通双路采集。请仅在获得通话参与者授权并符合当地法律法规的场景中使用通话采集和注入功能。

## 主要代码结构

- `MainActivity`：权限状态、监听控制、注入控制和最近文件回放。
- `CallStateInCallService`：监听 Telecom 通话状态，并负责切换麦克风静音状态。
- `CallCaptureService`：前台服务、双路采集、Telephony Tx 注入和 Call Redirect 下行替换。
- `PcmInjectionConfigActivity`：PCM 列表、预选、试听和删除。
- `PcmFileStore`：PCM 目录、文件校验及选择状态持久化。
