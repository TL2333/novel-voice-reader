# Novel Voice Reader Android 15 JNI 崩溃修复报告

## 最终状态

`JNI_CALLBACK_CRASH_FIXED`

最终 APK 已在指定 POCO 2311DRK48G（Android 15 / SDK 35 / arm64-v8a）上安装并执行真实 Kokoro 诊断与 Readium EPUB 连续朗读。最终本地 APK 和设备已安装 `base.apk` 的 SHA-256 完全一致；清空 Logcat 后，`SIGABRT`、`NoSuchMethodError`、`JNI NewFloatArray called with pending exception`、`Fatal signal 6` 的匹配数均为 0。

## 原始崩溃根因

sherpa-onnx 的 JNI callback 路径硬编码查找 `invoke([F)Ljava/lang/Integer;`，但 Kotlin/D8 生成的 synthetic lambda 没有 JNI 所要求的精确方法签名。JNI 查找失败后遗留 pending `NoSuchMethodError`，随后继续调用 `NewFloatArray`，Android 15 CheckJNI 因此执行 `SIGABRT`。这不是 OOM、ABI、模型路径、设备性能、音频权限或 Readium 问题。

## 修复内容

- `app/src/main/java/com/tl2333/novelvoicereader/tts/kokoro/KokoroTtsEngine.kt`
  - 删除修复前约第 502 行的 `offlineTts.generateWithConfigAndCallback(...)` 及全部 callback 标签/分块处理。
  - 新入口位于当前第 599 行：`offlineTts.generateWithConfig(text = text, config = GenerationConfig(...))`。
  - Native 调用前后检查每个请求的 `AtomicBoolean cancelled`；停止时立即标记当前任务、停止/flush `AudioTrack`、清空未开始队列，Native 短句返回后丢弃已取消结果。
  - 增加 `EMPTY_TEXT`、`EMPTY_GENERATED_AUDIO`、`TEXT_TOO_LONG` 结构化错误。
  - 增加 `INITIALIZING / READY / SYNTHESIZING / STOPPING / RELEASED / FAILED` 引擎状态。
  - 朗读与诊断共用 callback-less 短句路径；生成有效 PCM16 后才播放并写缓存。
- `app/src/main/java/com/tl2333/novelvoicereader/tts/tokenizer/ChineseSentenceTokenizer.kt`
  - Native 短句硬上限 120 字符；优先在中英文标点和空白处切分，不在英文单词或数字内部切分。
  - 单个 ASCII token 本身超过 120 字符时保留完整 token，由 Native 调用前的 `TEXT_TOO_LONG` 保护拒绝，绝不把超限文本送入 Kokoro。
- `app/src/main/java/com/tl2333/novelvoicereader/tts/kokoro/KokoroTtsEngineProvider.kt`
  - 书籍 provider 与诊断 provider 共享 Native session mutex；同一时刻只存在一个 OfflineTts 会话，并保证 `generateWithConfig` 与 `release` 不并发。
- `app/src/test/java/com/tl2333/novelvoicereader/SherpaCallbackApiUsageTest.kt`
  - 仅扫描 `app/src/main/java`，发现 `generateWithConfigAndCallback(` 或 `generateWithCallback(` 即失败。
- `app/src/test/java/com/tl2333/novelvoicereader/ChineseSentenceTokenizerTest.kt`
  - 增加英文单词、数字边界和单个超长 ASCII token 回归用例。
- `app/src/debug/AndroidManifest.xml`、`app/src/debug/java/com/tl2333/novelvoicereader/debug/DeviceSmokeActivity.kt`
  - 增加仅 Debug 变体可用的 ADB 自检入口，用真实诊断控制器和 Readium TTS navigator 重复执行真机崩溃回归；不进入 release 变体。

## Callback API 静态检查

- 扫描范围：`app/src/main/java`
- `generateWithConfigAndCallback(`：0 个生产调用
- `generateWithCallback(`：0 个生产调用
- callback-less `generateWithConfig(`：`KokoroTtsEngine.kt:599`
- `SherpaCallbackApiUsageTest`：通过

## 构建与测试

最终源码按要求依次执行：

1. `.\gradlew.bat clean`：成功
2. `.\gradlew.bat testDebugUnitTest`：成功，16 个 suite、32 个测试、0 failure、0 error、0 skipped
3. `.\gradlew.bat lintDebug`：成功，0 Error（32 个既有 Warning）
4. `.\gradlew.bat assembleDebug`：成功

附加静态 APK 验收：`APK_STATIC_VERIFICATION_PASSED`。

## 真机安装与冒烟测试

- 设备序列号：`I7SGOJXWBMPBZ9AA`
- 型号：`2311DRK48G`（POCO X6 Pro 5G）
- 系统：Android 15 / SDK 35
- ABI：arm64-v8a
- 安装：成功，使用 `adb install --no-streaming -r -t` 覆盖安装；未发生签名不兼容，未卸载 App，既有书籍和阅读数据得到保留。
- 最终二进制一致性：本地 APK SHA-256 与设备 `base.apk` SHA-256 均为 `c479cc2ea9d51e237799ea4ba5e7e7ba68ae788f9a187e54ec49caaa64025adb`。
- 诊断语音：通过。真实 Kokoro 生成并完成 AudioTrack 播放，输出合法 WAV `cache/tts-diagnostics/kokoro-test-1.wav`（233402 bytes）。
- EPUB 连续朗读：通过。导入/打开内置《雨夜灯火：离线朗读测试》，清理的仅是该内置测试书的朗读缓存；连续朗读产生至少 5 个新的逐句 WAV。缓存只在对应句播放成功后写入，因此该证据同时覆盖合成与播放。
- 交互：通过暂停、下一句、停止后重新开始、关闭 navigator/Publication 后重新打开并开始朗读。
- 最终真机时间线（2026-08-04）：
  - 01:23:10 `DIAGNOSTIC_RUNNING`
  - 01:23:26 `DIAGNOSTIC_PASSED`
  - 01:23:29 `EPUB_NARRATION_RUNNING`
  - 01:24:01 `EPUB_FIVE_SENTENCES_PASSED`
  - 01:24:23 `PASS`
- 完成后 App PID：`25897`，进程存活。
- 目标崩溃模式：0 个匹配；不再存在本次 callback JNI `SIGABRT`。

说明：HyperOS 禁止 `adb shell input`，并拒绝首次安装独立 instrumentation APK（`INSTALL_FAILED_USER_RESTRICTED`），因此没有修改手机全局安全设置，而是使用最终 Debug APK 内的自检 Activity 调用相同生产引擎路径。AudioTrack 播放流程和 WAV 已验证完成；当前环境没有人在设备旁进行主观音质/响度听评，这不影响 JNI 崩溃修复结论。

## 交付物

- APK：`C:\code\novel-voice-reader\dist\NovelVoiceReader-arm64-debug-fixed.apk`
- 大小：249061044 bytes（约 237.52 MiB）
- SHA-256：`c479cc2ea9d51e237799ea4ba5e7e7ba68ae788f9a187e54ec49caaa64025adb`
- 校验文件：`C:\code\novel-voice-reader\dist\SHA256SUMS.txt`

## 未解决问题

- 本次已确认的 JNI callback 崩溃没有遗留问题。
- 未执行人工主观音质/响度评价；已验证真实音频生成、合法 WAV、AudioTrack 播放完成和连续 EPUB 朗读。
