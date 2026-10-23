# 识屏 Android

独立运行的 Android 客户端。手机直接访问自己配置的 OpenAI-compatible API，不需要启动电脑、Electron 或中转服务器。

## 安装与使用

本地构建产物在 `artifacts/`。Android 8.0（API 26）及以上可安装；主要实机验证设备为 Android 16 / API 36。

1. 安装 APK，打开「识屏 → 设置」，填写 HTTPS Base URL、API Key、模型名称。识屏需要支持图片输入的视觉模型。
2. 点击「测试连接并获取模型」检查配置；部分平台没有 `/models` 接口，404 不代表聊天一定不可用。
3. 选择使用方式：
   - **悬浮截屏**：点击「截取屏幕」，授权显示在其他应用上层和系统截屏权限。部分系统会先显示应用列表，需要找到「识屏」开启权限，然后返回。授权时选择「共享整个屏幕」可在不同应用之间切换；单应用共享只捕获选中的应用。
   - **单次截屏**：无需悬浮窗，授权后有 5 秒切换目标画面。系统若限制自动返回，请手动打开识屏，截图会恢复到待发送区。
   - **图片导入**：通过系统文件选择器导入图片，或在相册里「分享 → 识屏」，支持多图。
4. 识屏页的截图进入待发送区，点图片可裁剪，输入问题后主动发送。面试悬浮窗的「截图」是独立快捷入口：点击即截取并发送给视觉模型，直接在悬浮窗显示回答。
5. 可以追加图片与追问、停止生成、复制回答或导出文字记录；右上角查看最近 16 段历史。

「交流」是普通文字聊天；「面试」页的「对话模式」对应桌面端连续转录与实时提示。资料库支持 PDF、DOCX、TXT、Markdown、UTF-8 / UTF-16 / GB18030 和直接粘贴，可分别启用于识屏或面试。文档在本机提取文字，PDF / DOCX 上限 5 MB，单份提取文字上限 3 万字；扫描 PDF 需要先转文字或作为截图识别。

## 对话模式与连续 ASR

1. 在设置填写回答模型，以及独立的「百炼 Fun-ASR」API Key。默认实时模型为 `fun-asr-realtime`，可配置业务空间专属 WSS 地址、地域、识别语言和热词表 ID。
2. 打开「面试」，腾讯会议同机通话选择「腾讯会议（USB 激活）」并按下节激活；普通媒体选择「应用播放音频」。先点「检测音频」，完成所需授权，然后切到目标应用播放声音。检测不联网、不存录音，60 秒自动停止。
3. 音量有变化后停止检测，再开始连续识别。音频会持续上传至配置的 ASR；自动模式在句末生成提示，手动模式按需生成。默认断句静默 800 ms、最短 4 个有效字符。
4. 对方继续说话时，正在生成的自动卡片等待句末并在原卡重写。手动提示会更新进行中的卡、覆盖尚未提示的转录，或对最后一张卡换角度；「停止提示」与「停止监听」独立。
5. 可直接切换模型、技术 / 行为 / 英文面试场景，也可在设置新建、编辑、重命名和删除场景。场景提示词复用桌面原文。
6. 授权悬浮窗后，切到其他应用可读提示、生成提示、截图解题或停止监听。「截图」使用设置中的「识屏使用的配置」，需支持图片输入；发送当前画面及启用的识屏资料。截图时临时隐藏本应用悬浮窗，答案在窗内流式显示，点击「提示」切回语音提示。截图回答单独保存，不占用语音句子范围，可随面试记录导出。提示卡支持思考折叠、复制、关联原句、分页阅读；手动滚动后不会被流式输出强行拉走。
7. 转录和提示由原生进程管理，界面切换不丢失；自动加密保存，最多 16 段历史，可导出 Markdown。退出进程不会自动恢复录音或付费请求。

应用音频监听已有共享授权时，悬浮截图复用该授权；仅使用麦克风时，第一次截图会申请系统共享权限，建议选择整个屏幕。取消截图授权不会停止监听。一次授权可连续截图，停听时自动关闭面试专用截屏会话。截图原图仅在本次请求内使用，不写入面试历史；需要重试时再次截图。音频检测本身不联网，主动点击「截图」仍会发送图片给所选模型。

**普通播放捕获的边界：** Android 的 MediaProjection 只允许捕获愿意被录制的媒体 / 游戏音轨，不能采集通话用途音轨。腾讯会议需要下面的独立音源；微信小程序尚未适配该音源。麦克风不会自动切换，同机通话也可能占用它。

### 腾讯会议同机音频（1.1.2）

适用于 Android 13+ 的当前调试 APK，不需要 Root。先安装 APK，在手机允许 USB 调试，连接电脑后从仓库根目录运行（需已安装 ADB 并加入 PATH，或已通过本地构建配置项目内 SDK）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "./mobile/scripts/activate-meeting-audio.ps1"
# 多台设备时追加 -Serial 设备序列号
```

显示 `READY` 后，在识屏选择「腾讯会议（USB 激活）」并点「检测音频」，让会议另一端讲话，确认音量变化后即可开始连续识别。激活后可以拔掉 USB，采集和 ASR 均在手机运行。激活最多持续 8 小时；手机重启、应用升级或系统终止辅助进程后，需要再次运行脚本。

该音源只采集 `com.tencent.wemeet.app` 的播放音频，使用系统混音复制保留手机播放，不开启麦克风。只有识屏主动开始检测或识别时才创建录音器；停止监听、应用退出或连接断开后释放录音器和混音策略。截图仍沿用独立的系统共享授权。

辅助进程以已获用户授权的 ADB shell 身份运行，不向普通 APK 授予系统权限。音频连接仅绑定 `127.0.0.1`，通过每次激活生成的 256 位密钥、双方随机挑战及 HMAC 双向认证；密钥分别保存在应用私有目录和 shell 私有文件中，不写入日志，不通过局域网传输音频。激活脚本使用 `run-as` 写入私有凭据，因此当前仅支持本工程调试包，不能直接用于不可调试的 release 包。

如需提前撤销激活，先停止监听，再连接电脑执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "./mobile/scripts/activate-meeting-audio.ps1" -Stop
```

已在本次 Redmi / Android 16 真机的腾讯会议中验证；其他设备、系统更新、蓝牙路由仍需本地检测，不保证所有厂商都向调试进程开放相同音频权限。使用的是 Android 系统 AudioMix 接口，系统升级可能改变兼容性。云端 ASR 的配置、准确率与费用仍取决于所选服务。

## 能力与边界

| 能力 | Android 实现 |
| --- | --- |
| 截屏与多图问答 | MediaProjection 系统授权；长边压缩至 1600 像素；每次最多 4 张 |
| 悬浮入口 | 可拖动截屏按钮，× 和系统通知均可停止 |
| 流式回答 | 原生 OkHttp HTTPS + SSE；中文、思考过程、取消、错误与断流检测 |
| AI 配置 | 多配置、按模式指定、模型列表、自定义鉴权头、关闭思考兼容重试 |
| 阅读 | Markdown、代码高亮、数学公式、表格、字号调整 |
| 本地资料与历史 | Android Keystore AES-GCM 加密，原子写入，禁用云备份和设备迁移 |
| 语音 | 原生连续 Fun-ASR：16 kHz PCM、句子修订、末句收尾、最多 3 次断线重连；另保留系统单次语音输入 |
| 网络 | 系统网络 / VPN；不提供桌面版的独立 HTTP / SOCKS5 代理设置 |
| 桌面共享隐身 | 不承诺安卓等效；受保护的目标应用可能输出黑屏 |
| 应用音频 | 普通媒体使用 Android 10+ AudioPlaybackCapture；腾讯会议增加 Android 13+ USB 激活的 shell AudioMix 音源 |
| PDF / Word 资料解析 | 本机 PDFBox 与 DOCX 文本提取；不含扫描 OCR 或旧 `.doc` 格式 |
| 桌面对话行为 | 自动 / 手动、继续说话撤回重写、上下文、换角度、停止、提示场景、资料、记录和悬浮操作 |

截屏前台服务会显示系统通知。厂商系统可能限制后台弹出，单次截屏可通过手动返回恢复。启用悬浮窗并不绕过系统截屏授权。

部分系统设置页、权限页或受保护应用会隐藏第三方悬浮窗；此时可使用单次截屏，或切回普通应用后再点悬浮按钮。屏幕共享的系统提示可能出现在截图中。

密钥、历史和资料只保存到当前应用私有目录。识屏和普通交流发送最近最多 10 轮；对话提示发送当前问题、最近最多 20 句 / 约 1500 字上下文及面试资料，不反复发送此前的 AI 答案。自动提示开启后会自动请求所选 AI 平台。断线期间音频不缓存、不补录；重连会明确提示。单段对话最多 2000 句、500 张卡。加密数据上限 24 MB，超过时明确提示。浏览器预览仅提供界面，不具备真实原生能力。

桌面快捷键由触控按钮和悬浮操作替代；不提供 Electron 的鼠标穿透、共享隐身和桌面代理配置。多张自动卡同时等待时，安卓会恢复全部卡片，避免桌面旧逻辑中部分卡片长期停留在等待状态。

## 构建

固定工具版本：Node 22.12+ 或 24，JDK 17，Gradle 8.11.1，AGP 8.9.2，Android SDK / Build Tools 35。

```powershell
# 从仓库根目录执行
cd "./mobile"
npm ci
npm run android
# 构建、原生测试和 Lint 通过后生成 artifacts/shiping-android-1.1.2-debug.apk
```

脚本优先使用项目内 `.tools/` 的便携 JDK、SDK、Gradle；不存在时使用当前进程的 `JAVA_HOME`、`ANDROID_HOME` 和 Gradle wrapper。不会修改全局环境变量。

有 Android Studio / 自己的 SDK 时：

```powershell
$env:JAVA_HOME = "C:/path/to/jdk-17"
$env:ANDROID_HOME = "C:/Users/you/AppData/Local/Android/Sdk"
npm run android
# 加 -Install 可以安装到已连接的唯一设备
powershell -NoProfile -File scripts/build-android.ps1 -Install
```

macOS / Linux：

```sh
cd mobile
npm ci
npm run build
cd android
bash gradlew assembleDebug testDebugUnitTest lintDebug
```

Android Studio 打开 `mobile/android` 前，先在 `mobile` 执行 `npm ci && npm run build`。前端资源被忽略、不提交到 Git，Gradle 会检查资源是否已生成。

默认调试 APK 使用本机 Android debug key，不适合公开发布。发布时请在 Android Studio 使用自己的长期签名生成 release APK / AAB；保留签名才能覆盖升级。不同机器的 debug key 不同，不能直接覆盖彼此的调试包。源码许可证沿用主工程 **CC BY-NC 4.0**。

## 开发与验证

```sh
npm run dev       # 浏览器界面预览：http://127.0.0.1:5178
npm test          # 共享鉴权头规则
npm run test:e2e  # Chrome 中的移动界面和桥接模拟测试
npm run build    # TypeScript + Vite
```

原生协议测试：`android/gradlew testDebugUnitTest`。

真实手机完整请求链路测试：`android/gradlew connectedDebugAndroidTest`。测试会在手机上创建临时 HTTPS 服务，使用一次性测试证书；仅测试对象信任它，不修改设备信任库或发布版 TLS。它验证冷启动、原生桥、中文流式响应、鉴权和 Keystore 历史保存，并在结束时恢复此前的加密应用数据。运行时请不要同时操作应用。

建议使用测试设备或独立测试数据；Gradle 的设备测试驱动可能在安装失败或收尾时卸载应用。部分厂商系统会弹出 USB 安装、测试应用启动确认，需要在手机上完成。也可以先构建 `assembleDebug assembleDebugAndroidTest`，通过 `adb install -r` 安装两份 APK，再执行 `adb shell am instrument -w cn.shiping.app.test/androidx.test.runner.AndroidJUnitRunner`，避免 Gradle 驱动接管安装与卸载。

测试结果与实机能力记录见 [TESTING.md](TESTING.md)。

## 工程结构

- `src/`：独立的触控 React 界面、模式/历史/资料、Markdown、裁剪、类型化桥接。
- `android/app/src/main/java/cn/shiping/app/`：Activity/WebView 边界、共享投影、截屏 / 音频前台服务、ASR 协议、面试状态机、悬浮提示、文档提取、加密存储、HTTPS/SSE。
- `android/app/src/test/`：JVM 协议与 HTTPS 集成测试。
- `android/app/src/androidTest/`：手机上运行的完整桥接测试。
- `tests/e2e/`：手机尺寸下的浏览器交互回归。
- `scripts/build-android.ps1`：Windows 可重复构建入口。

复用主工程的场景提示词和请求头规则。移动端没有导入 Electron 主进程；React 只通过固定原生桥调用能力。WebView 只加载 APK 自带资源，外部资源默认拒绝，外部链接确认后交给浏览器。桌面打包明确排除 `mobile/`。

平台约束参考：[MediaProjection](https://developer.android.com/media/grow/media-projection)、[后台启动限制](https://developer.android.com/guide/components/activities/background-starts)、[AGP 8.9 版本兼容](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。
