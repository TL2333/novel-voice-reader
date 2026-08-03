# Novel Voice Reader

面向 Android ARM64 的完全离线 EPUB 2/3 阅读器，使用 Readium Kotlin Toolkit 显示书籍，并通过 sherpa-onnx 与 Kokoro 在设备上合成中文语音。

## 功能

- Storage Access Framework 导入本地 EPUB，并验证 ZIP 路径、EPUB 结构、大小与可用空间。
- Readium 阅读器、目录、书签、完整 Locator 进度恢复和 Fixed Layout 显示。
- 内置 Kokoro 中文模型、SID 3–102、语速、轻量朗读风格和句级高亮。
- Media3 后台与锁屏控制、Room/DataStore 持久化、受保护的 WAV LRU 缓存。
- 首次启动模型校验、离线语音诊断和可读错误代码。
- 最终 Manifest 不包含 `INTERNET` 或 `ACCESS_NETWORK_STATE` 权限。

## 构建

Windows 环境需要 JDK 17、Python 3、Android command-line tools 和可用的 `sdkmanager`。构建脚本会安装/确认 Android 36 组件，下载并校验锁定的官方资源，然后依次执行测试、Lint、打包和静态验收：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-deliverable.ps1
```

验证成功后，本地 APK 位于：

```text
dist/NovelVoiceReader-arm64-debug.apk
```

模型、sherpa AAR 和 APK 均不提交 Git。GitHub Actions 会从锁文件中的官方 URL 重新获取并校验它们。

## 锁定版本

- Readium Kotlin Toolkit 3.2.0
- sherpa-onnx 1.13.4
- Kokoro-82M-v1.1-zh INT8（HF commit `155831f1b4ba23b1f5c058be6a61df90cefb2a37`）
- Android Gradle Plugin 9.0.0 / Gradle 9.1.0 / Kotlin 2.3.20

当前构建状态为 `BUILD_VERIFIED`。物理设备上的安装、发声质量、后台播放、性能与温度测试仍标记为 `DEVICE_RUNTIME_PENDING_USER_TEST`，请按 `dist/MANUAL_TEST_CHECKLIST.md` 验收。
