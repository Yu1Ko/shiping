# Android 验证记录

## 1.1.2 腾讯会议同机音频（当前交付）

验证日期：2026-10-23；未 Root 的 Redmi 24117RK2CC / Android 16 / API 36。已覆盖安装，测试恢复原配置和历史，最终将音源选择为腾讯会议。

普通 AudioPlaybackCapture 无法采集 VOICE_COMMUNICATION 音轨。本版增加经 USB 激活的 shell AudioMix 音源，只匹配腾讯会议 UID，复制播放流并保留手机播放，输出 16 kHz / PCM16 / 单声道供原有监听与 ASR 流程使用。不使用麦克风补录。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| APK 构建、原生单元测试 | 通过，22 项 JVM 测试 | 原有协议与状态机、双向 HMAC 认证 |
| Android Lint | 0 错误、19 警告 | 新增 8 项反射/非公开系统接口兼容性警告，其余沿用 1.1.1 |
| 浏览器 E2E | 6 / 6 通过 | 原有界面回归、新音源选择/检测/保存 |
| 手机 instrumentation | 报告 `OK (5 tests)` | 4 项执行通过；需 `overlayCapture=true` 的截图测试本次按条件跳过 |
| 腾讯会议实际 PCM | 连续两轮各 5 秒通过 | RMS 分别为 0.056633、0.023357；采集期间控制通道可用 |
| 停止、重启、撤销 | 通过 | 断开后重新采集；撤销使流关闭；未激活时明确报错 |
| 正常应用界面与前台服务 | 两轮检测通过 | 通过界面选择腾讯会议音源，点击检测、停止、再次检测；音量峰值 0.313790、0.140722 |
| 停止后的状态 | 通过 | ListeningService 已退出；辅助进程保持激活但不录音 |

手机回归的 WebView 启动曾被阻塞；线程栈定位为 `Instrumentation.startActivitySync` 等待。通过 shell 将匹配 MAIN/LAUNCHER 的应用界面前置后继续完成，未终止测试进程，原设置由测试的 finally 正常恢复。此次没有重跑额外投影授权截图测试，其历史结果见 1.1.1。

激活脚本通过 stdin 配置每次随机生成的 256 位密钥，应用与辅助进程通过双方随机挑战和 HMAC 双向认证。音频仅在手机 127.0.0.1 传输；shell 控制通道核验 UID。激活有效期最多 8 小时，手机重启、应用升级或辅助进程被系统终止需重新激活。当前激活依赖 debug APK 的 run-as；并非无需电脑激活的通用 release 方案。使用步骤见 README。

本次真实音频来自用户主动播放的腾讯会议测试语音。应用检测及原生测试不调用云端 ASR；网络回归仍使用本机 WSS / HTTPS 和虚构密钥，**未验收真实云端识别准确率或模型回答质量**。其他品牌、低版本 Android、蓝牙路由以及持续 8 小时运行尚未实测；反射接口可能随系统升级变化。

实现按职责分离辅助采集、认证与客户端；复用已有 ASR 和监听状态机，避免复制识别流程。Java、界面映射与认证方案已完成独立审查。

证据：

- `artifacts/shiping-android-1.1.2-debug.apk`：13,372,333 字节。
- SHA-256：`d60bc20a405ad6f32f3c4dc74c0f9f077ba9f34494f865c8c8590ab3437e6309`。
- `artifacts/meeting-build.log`：最终原生构建、22 项单元测试与 Lint。
- `artifacts/meeting-e2e.log`：6 项浏览器回归。
- `artifacts/meeting-device-tests.log`：手机 instrumentation 结果。
- `artifacts/meeting-ui-device.json`：正常应用界面两轮检测的音量采样。

复现会议测试需先激活辅助进程、解锁手机，并让会议另一端持续播放测试语音：

```powershell
adb shell am instrument -w -e meetingAudio true -e class cn.shiping.app.MeetingAudioTest cn.shiping.app.test/androidx.test.runner.AndroidJUnitRunner
```

该测试最后会撤销激活；结束后如需继续使用，请重新运行激活脚本。

## 1.1.1 面试悬浮截图回答

验证日期：2026-10-22；24117RK2CC / Android 16 / API 36，覆盖安装并保留用户配置和记录。

- 悬浮窗按钮改为「提示 / 截图 / 停听」。点击截图直接发送给识屏视觉配置，回答在悬浮窗流式显示，并保存为独立的面试提示卡。
- `npm run android` 通过：TypeScript、Vite、APK、20 项 JVM 测试及 Android Lint（0 错误、11 警告）。新增验证截图与语音范围隔离、手动提示不覆盖截图、取消后迟到图片不发送、截图历史恢复与导出。
- 前端单元测试 1 项通过；原有浏览器 E2E 4 项通过；新增截图卡进度、回答、停止状态、仅截图记录可导出的 E2E 1 项通过。
- 完整手机 instrumentation 4 项通过，包含原有桥接 HTTPS、ASR WSS/原生提示/加密存储、文档读取，以及本次真实投影截图链路。
- 应用播放音频场景单独通过：已有投影授权直接截图，无二次授权，连续两次成功；停止监听自动释放专用截图服务和投影。
- 麦克风场景通过：首次截图取消授权不会停止监听或发出模型请求；再次授权后连续截图成功，悬浮按钮在请求期间禁用。
- 实际上传的 JPEG 为测试题页，720 × 1600；不含本应用悬浮窗，系统共享标识仍可能可见。悬浮答案与本地 HTTPS 流式返回一致；未跳回主界面，普通待确认截图队列未被占用。
- 只有截图、没有语音的会话可保存到面试历史并导出 Markdown；原图不写入加密历史。

网络测试只使用手机本机 HTTPS 服务、一次性证书和虚构密钥，回复是预设测试文字，**不代表真实视觉模型识图质量验收**。测试结束恢复原设置和面试记录。Lint 新增的静态引用警告涉及正在显示的悬浮窗和截图服务，均在关闭/销毁时清空；其余警告沿用 1.1.0 已核验类型。

实机截图测试需解锁手机、具备录音及悬浮窗权限。测试会操作系统可见授权界面，不绕过权限；当前自动化匹配该设备的中文系统文案：

```powershell
adb shell am instrument -w -e overlayCapture true cn.shiping.app.test/androidx.test.runner.AndroidJUnitRunner
# 另验证已有应用音频投影的复用：
adb shell am instrument -w -e overlayCapture true -e captureSource playback -e class cn.shiping.app.NativeInterviewTest#overlayScreenshotUsesActualDisplayAndLocalVision cn.shiping.app.test/androidx.test.runner.AndroidJUnitRunner
```

未传 `overlayCapture=true` 时跳过需要系统授权交互的截图测试。首次测试曾被厂商跨应用启动确认打断；改为由测试 shell 前置测试页后通过。截图像素断言曾误采灰色按钮，核对原图后改为题页空白背景取样。

证据：

- `artifacts/shiping-android-1.1.1-debug.apk`：13,291,237 字节。
- SHA-256：`8d662a49faab74957e42bf4fee0e629d456004c08920f554aa0be8c312fb3c29`。
- `artifacts/overlay-capture-build.log`、`overlay-capture-test-build.log`：构建记录。
- `artifacts/overlay-capture-regression.log`：`OK (4 tests)`。
- `artifacts/overlay-capture-playback.log`：应用音频投影复用 `OK (1 test)`。
- `artifacts/overlay-capture-e2e.log`、`overlay-capture-ui.log`：浏览器回归。
- `artifacts/overlay-capture-0.jpg`、`overlay-capture-1.jpg`：实际请求图片。
- `artifacts/overlay-answer.png`：手机悬浮截图答案与三个按钮。

## 1.1.0 对话模式与连续 ASR

验证日期：2026-10-22；同一台 24117RK2CC / Android 16 / API 36。已覆盖安装到手机，保留原有应用数据。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| `npm run android` | 通过 | TypeScript、Vite、APK、JVM、Lint |
| JVM 测试 | 18 / 18 | WSS 握手、PCM gate、句子去重、末句收尾、断线后停止；自动/手动提示、撤回重写、旧 token 隔离、并发卡片、输入保护、转录消费；原有 SSE 与 HTTPS |
| 前端单元测试 | 1 / 1 | 请求头规则 |
| 浏览器 E2E | 4 / 4 | 原有识屏/交流/资料回归；面试补题失败保留、手动卡片、原句定位、场景编辑切换、模型入口、窄屏 |
| 提示可见性追加回归 | 1 / 1 | 新卡自动进入视口，固定操作栏，提示分页 |
| 手机 instrumentation | 3 / 3 | 原有 WebView→原生→HTTPS；WSS 模拟 ASR→原生状态机→HTTPS 提示→Keystore→Markdown；PDF/DOCX、UTF-16、GB18030 |
| APK 签名 | 通过 | 本机 Android debug key，v2 签名 |

所有网络测试只连接本机临时 WSS / HTTPS 服务，使用一次性证书和虚构密钥，未调用用户的云端 API。ASR 测试服务返回预定中文转录，验证协议和功能链路，**不代表真实语音识别准确率验收**。测试会备份、恢复原设置和面试记录。

Android Lint：0 错误、8 警告。包括本地 WebView JavaScript、仅持有 Application Context 的进程单例、两处已调用 `performClick()` 的触摸拖动、中文界面字符串，以及 PDF 依赖 BouncyCastle 中三处未被网络客户端使用的 TrustManager。生产网络仍使用 OkHttp 正常证书验证；测试信任仅存在于测试对象中。未声称完成 TalkBack 全面验收。

### 实机音频与生命周期

用仅存在于测试 APK 的 AudioTrack 测试页播放相同 440 Hz PCM 测试音，未加入真实会议、未读取私人媒体或发送消息：

| 实测项目 | 结果 |
| --- | --- |
| MEDIA + ALLOW_CAPTURE_BY_ALL | 成功，音量约 0.414（界面 41%） |
| VOICE_COMMUNICATION + 允许策略 | 捕获音量为 0；8 秒后提示可能为通话音轨或禁止捕获 |
| MEDIA + ALLOW_CAPTURE_BY_NONE | 捕获音量为 0；静音告警正常 |
| 切到测试应用 | 原生采集继续；回到 App 可读到最新状态 |
| 60 秒本地检测 | 自动结束，音量归零 |
| 截屏与音频共享投影 | 同一次音频授权下连续两次单次截屏成功，音频继续；未要求二次投影授权 |
| 悬浮面试窗 | 显示、收起、拖动通过；点击「停听」后前台服务退出 |
| 麦克风检测 | 授权、启动、停止通过；未验收与真实通话同时占用麦克风 |
| 对话页面 | 384 CSS px 无横向溢出；原生面试记录、场景、模型入口可用 |

### 未通过或未覆盖的范围

- 腾讯会议和微信已安装，但没有加入真实通话或操作小程序进行会议验收。不能声称这两款软件的通话声音可被捕获；通话音轨实测不可捕获。
- 未配置真实百炼服务进行连续讲话识别，也未验证真实 AI 回答质量、服务额度、长时断线和耗电。
- 系统可撤回投影或限制后台；不承诺共享隐身。低版本 Android、其他品牌和蓝牙音频未实机覆盖。
- 资料提取支持文字 PDF 和 DOCX，不含扫描 OCR、旧 `.doc`；单文件 / 文字长度有限制，详见 README。

### 当前产物与证据

- APK：`artifacts/shiping-android-1.1.0-debug.apk`，13,707,833 字节。
- SHA-256：`ac32ac79c452670352350d62f8a9fa99ad06e5188f810a0824c8ce32e045ff2d`。
- `artifacts/asr-final-build.log`：最终统一构建。
- `artifacts/asr-e2e-final.log`、`artifacts/interview-scroll-e2e.log`：浏览器回归。
- `artifacts/asr-device-final.log`：最终手机测试 `OK (3 tests)`。
- `artifacts/audio-capture-evidence.json`：音轨音量与告警。
- `artifacts/interview-phone.png`、`artifacts/interview-overlay.png`：手机对话界面和已收起、拖动后的悬浮窗。

## 1.0.0 历史基线

以下是前一版本的原始验收记录；其「未移植」项目由上面的 1.1.0 说明取代。

验证日期：2026-10-22。交付版本：1.0.0，包名 `cn.shiping.app`。

## 验证环境

- 实机：24117RK2CC，Android 16 / API 36，澎湃系统。
- 显示：1080 × 2400，450 dpi，WebView 可视宽度约 384 CSS px。
- 构建：Node 24、JDK 17、Gradle 8.11.1、AGP 8.9.2、Android SDK 35。
- 安装下限：Android 8.0 / API 26；此次没有对低版本设备做实机回归。

## 自动验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| `npm run android` | 通过 | TypeScript、Vite、APK、JVM 测试、Android Lint |
| `npm test` | 1 / 1 通过 | 与桌面一致的自定义请求头校验 |
| `npm run test:e2e` | 3 / 3 通过 | 手机布局、配置、公式与流式 UI、停止、追问、历史、跨模式图片、资料、拒绝 HTTP |
| `testDebugUnitTest` | 7 / 7 通过 | 中文 SSE、思考、DONE、截断响应、端点、思考参数兼容、读取上限、本地 HTTPS 与鉴权/图片请求 |
| 真实设备 instrumentation | 1 / 1 通过 | 打包 WebView → 原生桥 → 本地 HTTPS → 中文流式回答 → Keystore 历史写入 → Activity 关闭 |
| `apksigner verify` | 通过 | 本机 Android Debug 签名 |

Android Lint 为 0 个错误、2 个已核验警告：本地 React 页面需要启用 JavaScript；悬浮 Button 的触摸监听器在点击时调用 `performClick()`，同时提供点击处理与内容描述。未声称完成 TalkBack 全面验收。

真机网络测试使用手机本机 MockWebServer 和一次性证书。只有测试对象信任该证书，没有修改生产 TLS 或设备信任库，没有连接付费模型。首次测试发现关闭 TLS 连接触发主线程网络异常，已将取消与关闭移至独立后台线程，重跑通过。

## 手机手工与自动交互

| 操作 | 结果 |
| --- | --- |
| 安装与冷启动 | 成功；修复了初始化 load/ready 响应互等问题 |
| 单次截屏 | 两次真实捕获系统显示设置页；返回后进入待发送区 |
| 图片裁剪 | 成功，样本裁剪结果 504 × 800 |
| 键盘适配 | 弹出键盘后输入区仍位于可视范围内 |
| 悬浮授权与显示 | 成功，系统显示共享状态与前台服务 |
| 拖动悬浮条 | 成功，位置随拖动更新 |
| 静态画面悬浮截屏 | 成功，结果 720 × 1600；截图中不含应用悬浮条 |
| 悬浮条关闭 | 成功，服务结束，App 同步停止状态 |
| 系统图片选择器 | 使用本次生成的 JPEG，成功导入并显示缩略图 |
| 分享接收 | 带临时 URI 读授权的 ACTION_SEND 在热启动和冷启动均成功 |
| 资料与设置 | 保存后强制结束进程并重开，内容与空密钥设置正确恢复 |
| 系统确认弹窗 | 删除本次测试资料、测试对话正常完成 |

测试没有打开用户私人照片内容。完成后清理本次测试配置、资料与对话，关闭截屏会话，留下默认空密钥配置。

## 系统限制与未验收范围

- 当前设备的系统设置页会强制隐藏第三方悬浮窗；回到普通应用会恢复。可改用单次截屏。
- 厂商后台启动策略可能阻止截图后自动回到 App；手动打开 App 可接收待处理截图。
- 系统的屏幕共享标识可能进入截图；受保护窗口可能输出黑屏。没有安卓共享隐身承诺。
- 真实 AI 服务、额度、视觉识别质量尚未验收；用户选择先完成本地与实机功能验证。
- 没有实测其他品牌/Android 低版本、持续录屏耗电、横屏全场景、系统语音服务、多图分享来源的全部变体。
- 连续 ASR、通话内部音频、PDF/Word 解析不在本版能力中。
- 这是可安装调试版；公开分发需使用长期 release 签名。

## 产物与复核

APK：`artifacts/shiping-android-1.0.0-debug.apk`，6,039,574 字节（约 5.76 MiB）。

SHA-256：`c8eca1487b09ee5c0299f9755c916649c848b46b77601e5e12a91522c1e6a5e0`。

本地证据（构建目录被 Git 忽略）：

- `artifacts/final-build.log`：统一构建结果。
- `artifacts/device-test.log`：手机 instrumentation，`OK (1 test)`。
- `android/app/build/reports/tests/testDebugUnitTest/index.html`：原生测试报告。
- `android/app/build/reports/lint-results-debug.html`：Lint 报告。
- `artifacts/device-home.png`：实机首页。
- `artifacts/overlay-capture-result.jpg`：悬浮截屏结果。

Windows 上曾遇到 Gradle 8.11.1 临时缓存无法重命名；保留原缓存并恢复已完成的缓存条目后构建成功。构建脚本未加入自动修改缓存的绕过逻辑。
