# 构建与发布

GitHub Actions 是 APK 交付入口：`.github/workflows/ci.yml` 检查 PR，`.github/workflows/release.yml` 验证标签并发布安装包。

## GitHub Actions

- PR：`ci.yml` 的 `build` 检查运行 SAF 边界、两个模块的单元测试和 Lint；不使用 Release 密钥或打包。`main` 仅接收与最新主线同步、且 `build` 通过的 PR。
- 已确定随同一版本交付的功能、修复和版本号优先用一个职责书签、一个 PR 合入。需要提前合入或独立审查时，各 PR 仍须通过检查。
- 标签：仅推送 `v*` 标签触发 `release.yml`，标签须等于 `v` 加 Gradle 的 `versionName`，且指向 `main` 历史中的提交。工作流对标签提交重新运行测试与 Lint，再恢复原密钥、构建并验包，成功后创建 GitHub Release 并附加 APK。
- 权限与产物：构建 job 只读，publish job 才有 Release 写权限；APK artifact 保留 14 天。检查失败时上传 `Check-reports`。已有 Release 缺少同名 APK 时补传，已有同名 APK 时停止并要求人工检查。

GitHub Actions 使用 Windows runner 和 PowerShell 7.6+。Java、Android SDK 与 Build Tools 的版本从 `app/build.gradle.kts` 读取；`prepare-ci.ps1` 安装所需 SDK 组件。

## 发布步骤

在当前 `fix/*` 或 `feat/*` 职责书签上整理好本次交付内容，核对上一个实际交付的 Release 与本次提交范围，然后运行：

```powershell
pwsh -NoProfile -File .\scripts\release.ps1 -Bump patch
# 或明确指定目标版本
pwsh -NoProfile -File .\scripts\release.ps1 -Version 1.3.0
```

`release.ps1` 只负责递增 `versionCode`、准备版本提交、运行本地检查、推送职责书签、创建或复用 PR、等待 GitHub 按分支保护合并、同步 `main` 并推送版本标签。`-Bump` 还支持 `minor` 和 `major`；`-DryRun` 只读展示目标版本与候选书签。发布前必须有可用的 `origin` 和已登录的 `gh`。若 PR 检查失败、等待合并超时或目标标签已存在，脚本停止，不绕过保护或覆盖标签。

推送 `v<versionName>` 标签才触发 `release.yml` 的正式构建和 GitHub Release 创建。无需手工创建或 Publish Release；工作流将标题设为 `<versionName>`（例如 `1.2.7`），发布说明由 GitHub 自动生成。

## 签名设置

仓库 Settings → Secrets and variables → Actions 中添加 `MUSICTAG_KEYSTORE_BASE64`，值为原有 `%USERPROFILE%\.android\debug.keystore` 的 Base64。可在本机 PowerShell 复制：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
```

工作流将原密钥恢复到 runner 临时目录，通过 `MUSICTAG_KEYSTORE_PATH` 交给 Release 构建，结束时删除。验包校验现有 Music Tag 证书指纹。

## 本地与 CI 共用脚本

| 脚本 | 职责 |
| --- | --- |
| `.\scripts\test.ps1` | SAF 边界、单元测试、Lint 和报告汇总，不需要 Release 密钥 |
| `.\scripts\build-apk.ps1` | Release 构建与验包，检查由工作流前一步执行，不重复运行测试 |
| `.\scripts\verify-apk.ps1` | 独立检查版本、签名及 V2、权限、ARM64、ZIP 对齐、许可和敏感文件排除 |

本地启动用 `pwsh -NoProfile -File <脚本>`。脚本支持 `-SdkPath`，也可从环境变量或 `local.properties` 定位 SDK。复现 Release 时先运行 `test.ps1`，再运行 `build-apk.ps1`；未设置 `MUSICTAG_KEYSTORE_PATH` 时使用原用户目录中的密钥。APK 保留在 `app/build/outputs/apk/release/`，自动化通过不等于真机验收。
