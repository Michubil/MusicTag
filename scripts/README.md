# 构建

PowerShell 7.6+、JDK 25，SDK/Build Tools 版本以 Gradle 为准。外部启动用 `pwsh -NoProfile -File <脚本>`，已在该会话中则直接调用。

| 命令 | 用途 |
| --- | --- |
| `.\scripts\test.ps1` | 脚本语法、GUI/SAF 边界、单元测试、Lint |
| `.\scripts\build-apk.ps1` | 上述检查 + R8 Release、验包，再复制到 apk/ |
| `.\scripts\verify-apk.ps1` | 独立验包 |
| `.\scripts\build-apk.ps1 -FullNativeValidation` | GUI/原生依赖或较大升级时另验 V2 和 ELF 结构 |
| `.\scripts\build-apk.ps1 -Mp3SamplePath 'C:\path\sample.mp3'` | 加入真实 MP3 的只读封面回归检查，然后测试、验包与交付 |

SDK 依次从 `-SdkPath`、ANDROID_HOME、ANDROID_SDK_ROOT、local.properties、默认目录定位。Gradle 入口为 `gradlew.ps1`，JVM 参数在 gradle.properties。

测试固定执行 app 与 designsystem 的 Debug 单元测试及两个模块的 Lint，汇总 XML 报告中的通过、跳过和失败数量。`test.ps1` 同样接受 `-Mp3SamplePath`；样本作为 Gradle 测试输入，读取标签并解码封面，前后核对 SHA-256。指定样本时缺失、失败或跳过均阻止交付；未指定时只跳过外部样本用例，合成回归用例照常执行。样本不复制到仓库或 APK。

验包检查版本、签名、非调试 Release、权限、ARM64、普通 ZIP 对齐、许可与开发文件排除。通过后才复制 APK 到 `apk/`，核对交付副本摘要并生成同名 `.apk.sha256` 文件。打包入口已包含测试，无需先重复调用 test.ps1。BuildSupport.ps1 和两个边界脚本均由上述入口调用。

签名必须使用 USERPROFILE 下已有 `.android/debug.keystore`，缺失即停止，不生成替代密钥或绕过证书校验。保留 SDK、local.properties 和 Wrapper；APK 不提交 Git，脚本/密钥不进入 APK。自动化通过不等于真机验收。
