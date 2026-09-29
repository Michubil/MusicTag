# 构建与发布

GitHub Actions 是 APK 交付入口：`.github/workflows/ci.yml` 只在 `dev`→`main` 的发布 PR 上检查，`.github/workflows/release.yml` 验证标签并发布安装包。

## GitHub Actions

- 日常：开发在 `dev` 书签上进行并随时推送；推 `dev` 不触发检查，也不开 PR。
- PR：只有把 `dev` 合入 `main` 时才开一个 PR（由 `release.ps1` 创建或复用）。`ci.yml` 的 `build` 仅执行 `:app:assembleDebug`，验证候选的 APK 构建，不使用 Release 密钥。`main` 仅接收与最新主线同步、且 `build` 通过的 PR；该 PR 用 squash 合并。
- 标签：仅推送 `v*` 标签触发 `release.yml`，标签须等于 `v` 加 Gradle 的 `versionName`，且指向 `main` 历史中的提交。工作流恢复原密钥、构建并验包，成功后创建 GitHub Release 并附加 APK。
- 本地测试：SAF 边界检查、两个模块的单元测试、Debug 原生库编译和 Lint 由 `test.ps1` 执行；`release.ps1` 在推送候选前自动调用完整检查。PR 和标签工作流均不重复运行测试或 Lint；`checkReleaseBuilds = false` 关闭 Release 打包附带的 Lint，本地显式 Lint 仍执行。APK 签名、版本、权限等产物检查仍在 Actions 构建后执行。
- 权限与产物：构建 job 只读，publish job 才有 Release 写权限；APK artifact 保留 14 天。构建失败时上传已有的 `Build-reports`。已有 Release 缺少同名 APK 时补传，已有同名 APK 时停止并要求人工检查。

GitHub Actions 使用 Windows runner 和 PowerShell 7.6+。Java、Android SDK、Build Tools、NDK 与 CMake 的版本从 `app/build.gradle.kts` 读取；`prepare-ci.ps1` 安装所需 SDK 组件。

## 发布步骤

在 `dev` 上整理好本次交付内容，核对上一个实际交付的 Release 与本次提交范围，然后运行：

```powershell
pwsh -NoProfile -File .\scripts\release.ps1 -Bump patch
# 或明确指定目标版本
pwsh -NoProfile -File .\scripts\release.ps1 -Version 1.3.0
```

`release.ps1` 只负责递增 `versionCode`、准备版本提交、运行发布检查、推送书签、创建或复用 `dev`→`main` 的 PR、等待 GitHub 按分支保护合并、同步 `main` 并推送版本标签。日常提交不跑检查，完整测试在此次发布准备时本地执行，PR 的自动化门槛只验证构建。合并用 squash，所以脚本合并后把 `dev` 重置到 `main`（下一次发布的 PR 只包含新提交）；远端 `dev` 分支会在你下次推送时同步，建议在仓库设置里开启自动删除已合并分支。`-Bump` 还支持 `minor` 和 `major`；`-DryRun` 只读展示目标版本与候选书签。发布前必须有可用的 `origin` 和已登录的 `gh`。若 PR 检查失败、等待合并超时或目标标签已存在，脚本停止，不绕过保护或覆盖标签。

推送 `v<versionName>` 标签才触发 `release.yml` 的正式构建和 GitHub Release 创建。无需手工创建或 Publish Release；工作流将标题设为 `<versionName>`（例如 `1.2.7`），发布说明由 GitHub 自动生成。

## 签名设置

仓库 Settings → Secrets and variables → Actions 中添加 `MUSICTAG_KEYSTORE_BASE64`，值为原有 `%USERPROFILE%\.android\debug.keystore` 的 Base64。可在本机 PowerShell 复制：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
```

工作流将原密钥恢复到 runner 临时目录，通过 `MUSICTAG_KEYSTORE_PATH` 交给 Release 构建，结束时删除。验包校验现有 Music Tag 证书指纹。

音频指纹使用 AcoustID 应用 key。发布前在同一仓库 Secrets 中设置 `ACOUSTID_CLIENT_KEY`；构建时通过同名环境变量注入。PR 检查不需要 key，Release 缺失 key 时停止。应用 key 会进入 APK，不能将它视作用户私密凭据；不要把它提交到源码。本地调试可在当前 PowerShell 进程中设置同名环境变量，或在被忽略的 `local.properties` 中设置 `acoustid.clientKey`；环境变量优先。

## 本地与 CI 共用脚本

| 脚本 | 职责 |
| --- | --- |
| `.\scripts\test.ps1` | 默认检查 SAF 边界、两个模块的单元测试、Debug 原生库与 Lint；`-MatchingOnly` 检查 SAF 边界、匹配与写入相关单元测试及 Debug 原生库，不需要 Release 密钥 |
| `.\scripts\build-apk.ps1` | Release 构建与验包，完整测试由发布脚本在本地执行，不重复运行测试 |
| `.\scripts\verify-apk.ps1` | 独立检查版本、签名及 V2、权限、ARM64、ZIP 对齐、许可和敏感文件排除 |

本地启动用 `pwsh -NoProfile -File <脚本>`。脚本支持 `-SdkPath`，也可从环境变量或 `local.properties` 定位 SDK。复现 Release 时先运行 `test.ps1`，再运行 `build-apk.ps1`；未设置 `MUSICTAG_KEYSTORE_PATH` 时使用原用户目录中的密钥。APK 保留在 `app/build/outputs/apk/release/`，自动化通过不等于真机验收。

开发匹配规则时可用 `pwsh -NoProfile -File .\scripts\test.ps1 -MatchingOnly` 做定向检查；交付前仍运行不带该参数的完整检查。固定响应测试不验证真实网络接口或手机上的指纹生成。
