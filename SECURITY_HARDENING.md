# 仓库令牌安全加固备忘（SECURITY_HARDENING）

> 适用仓库：`CarDanceVisualizer`（github.com/Ethan2576m/CarDanceVisualizer）
> 目的：把本机 Git 使用的宽泛 `gho_` OAuth token（Git Credential Manager 授权）替换为**仅限本仓库、最小权限**的细粒度 PAT，并开启账号 2FA，把令牌一旦泄漏的波及面压到最低。

## 一、权限收敛对照

| 项目 | 旧（已撤销） | 新（现行） |
|---|---|---|
| 授权类型 | Git Credential Manager OAuth（账号级宽泛） | 细粒度 PAT（仅单仓库） |
| 作用范围 | 账号下全部仓库 | 仅 `CarDanceVisualizer` |
| 权限 | `repo` / `workflow` / `gist` | `Contents: 读写` + `Actions: 读写` |
| 有效期 | 长期有效 | 90 天（建议到期轮换） |
| 泄漏波及 | 账号全部资产 | 仅这一个公共仓库 |

## 二、操作步骤（GitHub 网页完成，助手无法代点）

1. **撤销旧授权**
   - 头像 → **Settings** → **Applications** → **Authorized OAuth Apps**
   - 找到 **Git Credential Manager** → 右侧 `⋯` → **Revoke**
   - 注：入口在**账号级** Settings，不在仓库 Settings；别误点 "Revoke all"。

2. **生成细粒度 PAT**
   - **Settings** → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**
   - Token name：自取，如 `cardance-push`
   - Repository access：**Only select repositories** → 勾 **`CarDanceVisualizer`**
   - Permissions → `+ Add permissions`：
     - **Contents** → **Read and write**
     - **Actions** → **Read and write**
     - （Metadata 自动 Read-only，无需改）
   - Expiration：90 天（或自定义）
   - **Generate token** → 复制 `github_pat_...`（**只显示这一次**，离开页面即不可见）

3. **开启 2FA（独立但强烈建议）**
   - **Settings** → **Password and authentication** → **Two-factor authentication** → **Enable**
   - 用 Authenticator App 扫码 + 验证码完成；开启后**不影响 PAT 使用**。

## 三、本机换凭据（由助手执行）

```bash
# 清掉旧条目（按 host+username 匹配），再用新 PAT 覆盖写入
printf 'protocol=https\nhost=github.com\nusername=YOUR_USERNAME\n' | git credential erase
printf 'protocol=https\nhost=github.com\nusername=YOUR_USERNAME\npassword=github_pat_xxx\n' | git credential approve
```

- `YOUR_USERNAME` 为 GitHub 账号名；`github_pat_xxx` 为上面生成的新令牌。
- 凭据落在 **Windows 凭据管理器**，Git Credential Manager 在后续 `git` 操作时自动读取。
- 若本机 `git` 版本不支持 `credential erase`，直接用 `approve` 以同一 `host+username` 覆盖即可（旧令牌在 GitHub 端已撤销，服务端亦失效）。

## 四、验证清单

| 验证项 | 命令 / 方式 | 预期结果 |
|---|---|---|
| 读权限 | `git ls-remote origin` | 返回 `main` 等引用 |
| 写权限 | `git push origin <分支>` | 远端成功建 / 更新分支 |
| Actions 读 | `curl -H "Authorization: Bearer <PAT>" https://api.github.com/repos/<owner>/CarDanceVisualizer/actions/artifacts` | 返回制品列表 JSON（如 `car-dance-apk`） |

> 注：在受限网络环境（如国内访问 GitHub 抽风、沙箱出口临时阻断），`github.com:443` 直连可能超时；此时验证失败是**网络问题而非令牌问题**，可换手机热点或在网络恢复后重试。验证用的临时分支务必 `git push --delete` 删掉，保持仓库干净。

## 五、应急回退

若旧 token 已撤销、新 PAT 又暂时拿不到（如网页打不开）：直接在仓库目录执行 `git push`，Git Credential Manager 会弹出浏览器重新走 OAuth 登录，登录后即恢复推送。该 OAuth token 权限较宽，**仅作临时过渡**——网络恢复后请尽快按第二节换回细粒度 PAT，并撤销此次的 OAuth 授权。

## 六、轮换

细粒度 PAT 到期前（默认 90 天）按第二节重新生成并替换本机凭据；旧 PAT 在 GitHub 端可随时单独撤销，互不影响。建议每次轮换顺手复核 2FA 是否仍开启。
