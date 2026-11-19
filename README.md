# voice-logger · 手机端侧语音记录验证版

按需开启、持续监听、**只留文字不留音频**的 Android 采集器。
定位是「硬件方案的先行验证」——先用手机把「VAD 切句 + 本地转写 + 落库」这条链路跑通、量出准确率，
再决定要不要做独立硬件。

---

## 一、实测结论（2026-11-07，Redmi K60 至尊版）

| 项 | 实测值 | 说明 |
|---|---|---|
| 设备 | Redmi K60 至尊版（vermeer） | 天玑 9200+ / 12GB / Android 16 / HyperOS 3.0 |
| 模型 | SenseVoice-Small INT8（229MB）+ Silero VAD（629KB） | 全本地，断网可用 |
| **RTF** | **0.033 ~ 0.10** | 4064ms 音频 → 135ms；2080ms → 208ms |
| 内存占用 | PSS 477MB（Native 309MB） | 主要是模型常驻 |
| 识别质量 | 三条完整中文句子，含问号/逗号标点 | 见下 |
| 常驻状态 | 前台服务正常，通知栏显示「正在监听语音」 | serviceTypes=128（microphone） |

实测识别输出（真机、安静室内环境，示例句）：

```
[2080ms → 208ms] 今天天气不错，适合出去走走。
[4064ms → 135ms] 我们下午三点在图书馆门口见吧。
[4864ms → 183ms] 测试测试测试测试，看看效果如何。
```

**RTF 0.05 的含义**：转写 1 小时语音只需 3 分钟算力。一天 2 小时人声 ≈ 6 分钟，
算力完全不是瓶颈，功耗大头是「麦克风常开 + VAD 常驻」。

---

## 二、架构

```
AudioRecord (16kHz / mono / PCM16, 源=VOICE_RECOGNITION)
   ↓ 每 32ms 一个窗口
Silero VAD ── 只保留人声段，静音期零推理
   ↓ 说话停顿 250ms 即断句；单段最长 20s
阻塞队列（容量 64）
   ↓
SenseVoice INT8 单线程转写
   ↓
Room 落库 + SessionState 推给界面
```

设计取舍：

| 决策 | 理由 |
|---|---|
| 音频不落盘 | 验证阶段只关心文字；要留音频会让存储和隐私成本翻倍 |
| VAD 门控而非全程转写 | 静音期不推理，省 90% 以上算力 |
| 单线程 ASR | OfflineRecognizer 有状态，并发会崩；队列积压用 queueDepth 观测 |
| 内部存储放模型 | 见「踩坑记录」第 2 条 |

---

## 三、构建

```bash
export JAVA_HOME="/path/to/jdk17"
cd voice-logger
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 28MB）
```

依赖的 sherpa-onnx 预编译库在 `app/libs/sherpa-onnx.aar`（官方 v2.0.1，含 arm64-v8a）。
它体积 47MB，**不入库**，首次构建前需自行下载：

```bash
mkdir -p app/libs
curl -fL -o app/libs/sherpa-onnx.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
```

（`app/build.gradle.kts` 用的是 `implementation(files("libs/sherpa-onnx.aar"))`，
文件缺失时构建会直接失败。）

---

## 三之二、提交前检查（可选但建议）

这个工程处理的是「家里的说话声」，真实对话一旦进了提交历史，删文件也救不回来
（`git log -p` 仍能查到）。仓库带了一套本地检查：

```bash
git config core.hooksPath .githooks
cp .privcheck-words.example .privcheck-words   # 再按需填入你自己的敏感词
```

启用后每次 `git commit` 会拦下四类问题：不该入库的路径/扩展名（录音、数据库、
模型、密钥）、单文件超 20MB、命中 `.privcheck-words` 的词、疑似明文密钥。

词表刻意做成**不入库的本地文件**——把真实姓名写进公开的检查脚本，等于换了个
地方泄露。缺这个词表时脚本跳过词匹配，其余检查照常。

确属误报：`git commit --no-verify`。

---

## 四、安装与部署模型

模型 230MB 不打进 APK，安装后推入设备。

```bash
export MSYS_NO_PATHCONV=1          # ⚠️ Git Bash 必须，否则 /sdcard 会被转成本地路径
ADB=$ANDROID_HOME/platform-tools/adb
PKG=com.misaka.voicelog

# 1. 安装
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

# 2. 授权（省掉手动点击）
$ADB shell pm grant $PKG android.permission.RECORD_AUDIO
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS

# 3. 加入电池优化白名单（避免被系统杀）
$ADB shell "cmd deviceidle whitelist +$PKG"
$ADB shell "cmd appops set $PKG RUN_IN_BACKGROUND allow"

# 4. 推模型：先到 /data/local/tmp，再用 run-as 复制进 App 内部存储
$ADB push models/model.int8.onnx          /data/local/tmp/
$ADB push models/tokens.txt               /data/local/tmp/
$ADB push models/silero_vad.onnx          /data/local/tmp/
$ADB shell "run-as $PKG mkdir -p files/models"
$ADB shell "run-as $PKG cp /data/local/tmp/model.int8.onnx files/models/"
$ADB shell "run-as $PKG cp /data/local/tmp/tokens.txt      files/models/"
$ADB shell "run-as $PKG cp /data/local/tmp/silero_vad.onnx files/models/"
```

模型文件来源：

| 文件 | 来源 |
|---|---|
| `model.int8.onnx` (229MB) | sherpa-onnx Release `asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2`（压缩包 1GB，内含 FP32 版，只需 INT8） |
| `tokens.txt` (309KB) | 同上 |
| `silero_vad.onnx` (629KB) | 同 Release 的 `asr-models/silero_vad.onnx`。**GitHub 直连常失败，用 `https://ghproxy.net/<原地址>` 镜像** |

---

## 五、踩坑记录

**1. sherpa-onnx 用绝对路径加载模型时，`assetManager` 必须传 `null`**

```
W sherpa-onnx: You are using an absolute path '.../silero_vad.onnx', but assetManager is NOT set to null.
F sherpa-onnx: Read binary file: Load failed
```

v2.0.1 的 `Vad(assetManager, config)` / `OfflineRecognizer(assetManager, config)` 构造函数，
传了非空 AssetManager 就会走 assets 分支去找文件。模型在文件系统上时必须传 `null`。
参见 sherpa-onnx issues/2562。

**2. adb push 到 `/sdcard/Android/data/<pkg>/files/` 后，应用侧读不到**

文件管理器里可见、`adb shell ls` 能看到，但 App 的 `File.exists()` 返回 false
（FUSE 视图未同步）。改用内部存储 `filesDir/models` 解决。

写入内部存储的两条可用路径（都已实测）：

```bash
# A. exec-in + stdin（可用）
$ADB exec-in "run-as $PKG sh -c 'cat > files/models/x'" < local_file

# B. /data/local/tmp 中转 + cp（推荐，大文件更快）
$ADB push local_file /data/local/tmp/
$ADB shell "run-as $PKG cp /data/local/tmp/local_file files/models/x"
```

注意：普通 `adb shell "... cat > file" < local_file` 只会写入 11 字节，stdin 没有正确转发。

**3. Git Bash 会篡改 `/sdcard` 路径**

报错形如 `failed to copy ... to 'C:/Users/.../PortableGit/sdcard/...'`。
解决：`export MSYS_NO_PATHCONV=1`。

**4. Android 14+ 的麦克风前台服务只能在有可见 Activity 时启动**

不能在 `BOOT_COMPLETED` 里拉起，也不能后台自启。**必须用户手动点按钮**。
这是系统硬性限制，不是实现问题。

---

## 六、让麦（打断）逻辑

要求：遇到微信语音/来电时主动让出麦克风，不硬抢。

| 检测手段 | 覆盖场景 | 动作 |
|---|---|---|
| `AudioManager.mode == IN_CALL / IN_COMMUNICATION` | 来电、微信语音通话 | 立即停止录音，状态改为「通话中，已让出麦克风」，每 1.5s 轮询恢复 |
| 连续 60 个窗口（约 2s）读到全零 PCM | 麦克风被其他 App 独占 | 释放 AudioRecord，退避重试 |
| `read()` 返回负值 | 硬件错误 | 重建 AudioRecord |
| 采集线程抛异常 | 未预期错误 | 落一条 interrupt 日志 + 界面提示，不崩溃 |

所有中断都会写入 `interrupt` 表（时间、类型、说明），事后能回答「这段为什么没录到」。

---

## 七、功能现状

| 模块 | 状态 | 说明 |
|---|---|---|
| 采集 + VAD + 本地转写 | 已实现 | 只留文字，音频不落盘 |
| 说话人区分 | 已实现 | 声纹档案（**按录音通路绑定**）+ 基频兜底 + 人工改判。F0 只作兜底、不作结论，**来源单独标记**，界面上能分辨「声纹判的」和「机器猜的」 |
| 复核界面 | 已实现 | 按「未判 / 机器猜 / 噪音 / AI 建议」筛选；支持批量删除、拆行并句、改判、撤销 |
| AI 校对 | 可选 | 需要自建后端。AI 只产出「建议」，必须人工点采纳才落库 |
| 云端同步 | 可选 | 需要自建 CloudBase 云函数。本地优先，断网可用 |
| 导出 | 已实现 | 按会话导出文本，时间轴有偏差时附说明行 |

### 仍然存在的限制

| 限制 | 影响 |
|---|---|
| 未验证 9 小时以上长时间稳定性 | 国产 ROM 杀后台仍是最大风险 |
| 未测电量曲线 | 估 15~30%/9h，未实测 |
| 声纹换麦必须重录 | 同一人换录音通路后相似度掉到 0.34（低于跨人的 0.369），这是物理约束，不是实现缺陷 |
| 不保留音频 | 转错字无法回听核对 |
| 单麦，无方向信息 | 无法判断声源方向；多人重叠时整段只能判给一个人 |

---

## 八、可选后端（同步 + AI 校对）

**代码里不带任何后端地址**，两个功能默认关闭。要启用得自建一个 CloudBase 云函数：

| 能力 | 客户端实现在 | 说明 |
|---|---|---|
| 云端同步 | `core/SyncClient.kt` | 按会话上传/下载转写；本地优先，同步失败不影响采集 |
| AI 校对 | `core/ProofreadClient.kt` | 转发到 OpenAI 兼容接口（作者用的是 DeepSeek），返回只进「建议」字段 |

两者都用 URL 上的 `token` 参数鉴权 —— **这只是挡住扫描器，不是安全设计**。
公网部署请自行加限流、签名和 HTTPS。

---

## 九、后续路径

| 阶段 | 内容 | 前置条件 |
|---|---|---|
| 1 | 长稳态验证：跑满 9 小时以上，测存活率与电量曲线 | 现在就能做 |
| 2 | 转写准确率：换模型 / 短句合并后再送 ASR（当前字错率约 28%） | 需要离线评测集 |
| 3 | 硬件化（ESP32-S3 做 VAD+录音+上传；或 RK3566 直接跑本地 ASR） | 阶段 1~2 通过 |

**关于第 3 步的平台选择**：sherpa-onnx 官方提供 `-rknn.aar`（RK NPU 版），
同一套 ONNX 模型在手机和 RK3566 之间通用，算法层不用重写。
ESP32-S3 只能做 VAD + 录音 + 上传，跑不了中文 ASR。

---

## 十、不修改既有工程

本工程独立于另一个 Android 记账项目（DayLedger），只复用了它的 `gradlew`
与 Gradle 镜像配置，未改动其任何文件。

---

## 十一、隐私设计

这个项目本质上处理的是「家里的说话声」，所以隐私是设计约束而不是附加功能：

| 设计 | 实现 |
|---|---|
| 音频不落盘 | VAD 切出的音频段只在内存里走一趟转写，写完就丢 |
| 模型全本地 | SenseVoice + Silero VAD 都在设备上跑，断网可用，无任何上传 |
| 云端默认关闭 | 同步与 AI 校对都要用户自己填地址和 token，代码里不预置任何后端 |
| 声纹只存向量 | 声纹档案存的是说话人嵌入（每份约 3KB），不是录音 |
| AI 校对必须人工确认 | AI 输出只进「建议」字段，不点采纳绝不覆盖原文 |

---

## 十二、自动构建

`.github/workflows/android.yml` 在 GitHub Actions 上出包，两条路径：

| 触发 | 产物 | 用途 |
|---|---|---|
| push 到 `main` | 本次运行的 artifact（保留 30 天） | 每次改动都能拿到一个可装包 |
| push 一个 `v*` 标签 | 额外创建 Release，附件 `voice-logger-vX.Y.Z.apk` | 手机浏览器直接点下载 |

手动触发也行（Actions → Build APK → Run workflow）。

**关于签名**：CI runner 默认生成的是随机调试密钥，用它出的包**装不上**已装的版本
（签名冲突，必须先卸载，本地数据库会一起丢）。所以 workflow 会从
`DEBUG_KEYSTORE_B64` 这个 secret 还原固定密钥，再校验产物指纹与
`DEBUG_KEYSTORE_SHA256` 是否一致——不一致直接失败，不让一个装不上的包流出去。

自建仓库时要配这两个 secret（Settings → Secrets and variables → Actions）：

```bash
# 取当前已装版本的签名指纹
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk | grep 'SHA-256'
# 生成 base64（Linux/macOS）
base64 -w0 ~/.android/debug.keystore      # macOS 用 base64 -i
```

Windows 上取 base64：`certutil -encodehex -f "%USERPROFILE%\.android\debug.keystore" ks.txt 4`
然后去掉首尾标记行。不配这两个 secret 也能构建，只是包不能覆盖安装。

---

## 十三、许可

代码采用 [MIT License](LICENSE)。

第三方组件：

| 组件 | 许可 |
|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 |
| SenseVoice 模型权重（FunAudioLLM） | Apache-2.0 |
| Silero VAD | MIT |

模型权重不随仓库分发，按上文「四、安装与部署模型」自行下载。
