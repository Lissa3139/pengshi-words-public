# GitHub 私有同步教程

彭式背单词可以把加密后的学习记录保存到你自己的 GitHub 私有仓库，让 Android 和 Windows 设备共用学习进度。同步是可选的；没有配置时，应用仍可离线学习。

本教程会创建一个专门保存学习数据的私有仓库，并为它生成权限受限的 Fine-grained Token。它与公开的彭式背单词源码仓库分开使用。

GitHub 界面可能调整，最新页面说明见 [创建仓库](https://docs.github.com/en/get-started/start-your-journey/creating-a-repository-for-your-project) 和[管理个人访问令牌](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)。应用通过 GitHub Git API 读写同步记录，所需的仓库权限为 `Contents: Read and write`；可参阅 GitHub 关于[文件树](https://docs.github.com/en/rest/git/trees)和[分支引用](https://docs.github.com/en/rest/git/refs)的权限说明。

## 1. 建立专用私有仓库

1. 登录 GitHub，打开[新建仓库页面](https://github.com/new)，也可从右上角的 `+` 菜单选择 **New repository**。
2. 仓库名可使用 `pengshi-words-sync`，也可以自定义。
3. 将 **Visibility** 设为 **Private**。
4. 勾选 **Add a README file**，这样新仓库会有初始提交和默认分支。若你的默认分支不是 `main`，之后在应用中填写页面显示的分支名。
5. 选择 **Create repository**。

请勿把个人学习记录上传到公开源码仓库，也不要把这个数据仓库改为 Public。仓库所有者和名称会填写到应用设置中。

## 2. 创建 Fine-grained Token

1. 打开 GitHub 头像菜单 → **Settings** → **Developer settings**。也可以直接打开 [Fine-grained Token 创建页](https://github.com/settings/personal-access-tokens/new)。
2. 进入 **Personal access tokens** → **Fine-grained tokens**，选择 **Generate new token**。
3. 填写便于识别的名称，例如 `彭式背单词 Android`，并设置过期时间。
4. **Resource owner** 选择刚才创建仓库的个人账户或组织。
5. **Repository access** 选择 **Only select repositories**，再只勾选刚才创建的数据仓库。
6. 在 **Repository permissions** 中找到 **Contents**，设为 **Read and write**。保留 GitHub 自动提供的只读 **Metadata** 权限；不需要授予仓库管理、工作流或其他权限。
7. 选择 **Generate token**，立即复制 Token 并保存到密码管理器。GitHub 官方提醒，完整 Token 只在生成时显示一次；之后若遗失，需要撤销并重新创建（见 [GitHub 的 Token 安全说明](https://github.blog/developer-skills/github/github-for-beginners-answers-to-some-common-questions/)）。

Fine-grained Token 对你选中的仓库应用所设权限；GitHub 也会默认提供对公开仓库的只读访问。Token 是访问 GitHub 的凭据，应用不要求把它写进源码或公开项目。可以为每台设备分别创建 Token，并将写权限限定到同一个数据仓库。若 Token 过期或意外泄露，可在 GitHub 撤销它，再创建新的 Token 并更新应用设置。

## 3. 设置同步密码

同步密码由你自己设置，不是 GitHub 登录密码，也不是刚才生成的 Token。应用使用它加密和解密学习数据；所有设备必须填写完全相同的同步密码。

请选择不容易猜到、能安全保存的密码短语，并保存在密码管理器中。丢失同步密码后，应用无法解密仓库里的现有学习数据；GitHub 无法替你恢复它。

## 4. 在第一台设备连接并同步

建议先在保存完整学习记录的设备上完成首次连接和上传，再连接其他设备。

在 Android 或 Windows 应用中打开 **设置 → GitHub 同步**，填写下表：

| 应用字段 | 要填写的内容 |
| --- | --- |
| GitHub 用户名或组织 | 数据仓库所属的个人账户或组织名 |
| 私有仓库名称 | 数据仓库名称，不要加 `.git` |
| 分支 | 仓库的默认分支，通常为 `main` |
| 同步密码 | 上一步创建并妥善保存的同步密码 |
| GitHub Fine-grained Token | 上一步创建、已授权这个数据仓库的 Token |

Android 选择 **保存连接**，Windows 选择 **保存 GitHub 配置**；随后选择 **立即同步**。等待应用显示同步完成后，再继续配置其他设备。连接信息保存后，Token 和同步密码由应用保存在本机的加密存储中。

## 5. 连接其他设备

在另一台设备上填写相同的仓库所有者、仓库名、分支和同步密码。Token 可以沿用尚未过期且权限正确的那个，也可以为新设备创建单独的 Fine-grained Token；若新建，同样只选择这个数据仓库，并只授予 `Contents: Read and write`。

保存配置后选择 **立即同步**，等待完成。不要因为设备显示“等待依赖”或“冲突”就清除保存完整记录的设备上的数据；这些状态表示同步还没有完成。先核对两台设备的仓库、分支和同步密码，并确认网络连接及 Token 权限。

## 常见问题

| 现象 | 优先检查 |
| --- | --- |
| 401 或 403 | Token 是否过期；Resource owner 是否正确；是否只选中了目标私有仓库；`Contents` 是否设为 `Read and write`。组织仓库的 Token 可能还要等待管理员批准。 |
| 找不到仓库或分支 | 用户名 / 组织名、仓库名和分支名是否填写正确；仓库是否仍为 Private；是否已勾选 README 并建立初始分支。 |
| 无法解密或密码错误 | 所有设备的同步密码是否逐字符一致；注意大小写、空格和输入法。 |
| 同步冲突或一直未完成 | 不要删除原设备数据；先确认仓库与密码一致，待同步完成后再继续使用其他设备。 |

## 凭据与隐私

- 公开源码仓库用于程序代码，学习数据仓库必须保持 **Private**。
- Fine-grained Token 只授予目标数据仓库的 `Contents: Read and write` 权限。不要把 Token 或同步密码放进 README、Issue、Pull Request、源码或截图。
- 应用会在写入 GitHub 前加密同步记录；Android 使用系统支持的本地加密存储保存凭据，Windows 使用当前账户的 DPAPI 加密保存。详细说明见[隐私与数据](privacy.md)。
- 若 Token 暴露，立即在 GitHub 撤销并换新 Token。若同步密码遗失，先保留已有设备的本地学习数据，不要清除应用数据。
