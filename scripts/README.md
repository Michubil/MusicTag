# 构建与检查

GitHub Actions 是 APK 交付入口，工作流为 `.github/workflows/build-apk.yml`。

## GitHub Actions

- PR：脚本语法、GUI/SAF 边界、两个模块的单元测试与 Lint。`main` 更新不再触发重复检查。
- `main` 必须通过 PR 合并，GitHub Actions 的 `build` 检查通过且分支与主线保持最新后才允许合并，管理员同样受约束。
- jj 书签推送后对应 GitHub 分支；重写同一书签并推送会更新 PR，取消该 PR 的过期检查。每次 Release 发布独立运行。
- 仅 GitHub Release 的 `published` 事件触发打包（包含预发布）；检查通过后，构建并验证签名 APK。推送分支、推送标签、保存 Release 草稿均不打包，也不提供手动 Run workflow 入口。
- 标签须等于 `v` 加 Gradle 的 `versionName`，且指向已合入 `main` 的提交；标签不会修改版本，发布前通过普通 PR 更新 `versionCode` 与 `versionName`，无需专用发布分支。
- 版本 PR 合并后，执行 `jj git fetch --remote origin` 同步 `main`，再执行 `jj tag set v<versionName> -r main` 和 `jj git push --remote origin --tag v<versionName>`。最后在 GitHub Releases 选择该标签并点击 Publish release，触发打包；将占位符替换为实际版本。
- 发布构建成功后，APK 自动附加到触发本次构建的 GitHub Release。上传任务单独获得 Release 写权限，PR 与构建任务保持只读；重复执行时跳过内容相同的附件，同名不同内容则报错。Actions 中也保留 APK 和 `Check-reports` 14 天，不自动创建新的 Release。

使用 GitHub 托管的 Windows runner 和 PowerShell 7.6+。Actions checkout 获取本次事件的源码，PR 检查合并结果；不在 runner 上初始化 jj 仓库。Java、Android SDK 与 Build Tools 的版本由 `app/build.gradle.kts` 读取，依赖由 Gradle 管理。`prepare-ci.ps1` 只在 GitHub runner 上安装所需 SDK，不修改本机环境。

## 签名设置

仓库 Settings → Secrets and variables → Actions 中添加 `MUSICTAG_KEYSTORE_BASE64`，值为原有 `%USERPROFILE%\.android\debug.keystore` 的 Base64。可在本机 PowerShell 复制：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
```

工作流将原密钥恢复到 runner 临时目录，通过 `MUSICTAG_KEYSTORE_PATH` 交给 Release 构建，结束时删除。PR 检查不读取 Secret；Debug 与 Release 的签名配置分离。验包仍校验现有 Music Tag 证书指纹，不生成替代 Release 密钥。

## 本地与 CI 共用脚本

| 脚本 | 职责 |
| --- | --- |
| `.\scripts\test.ps1` | 源码边界、单元测试、Lint 和报告汇总，不需要 Release 密钥 |
| `.\scripts\build-apk.ps1` | Release 构建与验包，检查由工作流前一步执行，不重复运行测试 |
| `.\scripts\verify-apk.ps1` | 独立检查版本、签名及 V2、权限、ARM64、ZIP 对齐、ELF 结构、许可和开发文件排除 |

本地仅在明确要求时运行检查或构建。外部启动用 `pwsh -NoProfile -File <脚本>`；脚本支持 `-SdkPath`，SDK 也可由环境变量或 `local.properties` 定位。Gradle 统一通过 `gradlew.ps1` 调用。需在本地复现 Release 时，先运行 `test.ps1`，再运行 `build-apk.ps1`；签名路径未显式设置时使用原用户目录下的密钥。

测试样本均在测试临时目录生成，MP3 封面与只读回归不依赖外部歌曲；测试缺失、失败或跳过均阻止交付。APK 保留在 `app/build/outputs/apk/release/`，不复制、不纳入版本管理，密钥和开发文件不进入 APK。自动化通过不等于真机验收。

检查与构建直接读取完整源码，不依赖 `.jj/`、`.git/`、暂存区或提交历史，也不通过提交差异筛选测试。本地 jj 工作区与 CI 使用相同入口；脚本语法检查同时禁止直接调用 Git。验包保留 `.jj/` 与 `.git/` 的排除检查，避免任何版本管理数据进入安装包。
