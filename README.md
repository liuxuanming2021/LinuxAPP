# NalxerApp —— 服务器数据 App（服务端插件 + 安卓客户端）

给 Nalxer 服务器做的手机端：玩家用**游戏 ID + 游戏密码**登录，直接看自己的金币、
点券、经验等级、在线时长、队伍、头衔；管理员多一个「玩家管理」页，能搜人、封禁、解封。

```
手机 App ──HTTPS──> api.nalxer.top（Nginx 反代）──> 127.0.0.1:8787（插件内置接口）
                                                        │
                                      AuthMe / Vault / PlayerPoints / InTheNameOfLinux
                                      LinuxTeam / LinuxTitle
```

---

## 一、服务端（插件）

### 1. 安装

把 `NalxerApp.jar` 丢进 `plugins/`，重启服务器。首次启动会生成：

```
plugins/NalxerApp/config.yml     # 配置
plugins/NalxerApp/stats.yml      # 玩家数据缓存（离线也能查）
plugins/NalxerApp/audit.log      # 管理操作审计日志
```

启动日志里应该能看到这几行（没接上的会写「未检测到」，功能自动关掉）：

```
[NalxerApp] [联动] 已接上 AuthMe —— 用游戏密码登录
[NalxerApp] [联动] 已接上 Vault 经济 —— 金币数据
[NalxerApp] [联动] 已接上 PlayerPoints —— 点券数据
[NalxerApp] [联动] 已接上 InTheNameOfLinux —— 封禁 / 解封
[NalxerApp] [联动] 已接上 LinuxTeam 队伍
[NalxerApp] [联动] 已接上 LinuxTitle 头衔
[NalxerApp] 接口已启动：http://0.0.0.0:49282/api/health（明文直连，手机里填 http://服务器IP:49282 就能用）
```

### 2. 配置（`config.yml`）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `http.host` | `0.0.0.0` | 监听地址。云服务器直连就保持 `0.0.0.0`；只想让本机 Nginx 转发就改回 `127.0.0.1` |
| `http.port` | `49282` | 端口。App 里填 `http://服务器IP:49282`；云服务器记得在安全组放行 |

> 老版本默认是 `127.0.0.1:8787`（只监听本机，手机连不上）。新 jar 启动时会检测到这种老默认值，
> 把 `http.host` 自动改成 `0.0.0.0`、`http.port` 自动改成 `49282`，并在控制台打一行
> 「config.yml 的接口地址还是老默认值（127.0.0.1:8787），已自动改成 0.0.0.0:49282」。
| `http.threads` | `8` | 接口线程数，玩家多可以加到 16 |
| `http.tls` | `false` | 插件自带 HTTPS 的总开关；开了就不用再挂 Nginx |
| `http.keystore` | `keystore.p12` | PKCS12 证书文件，放在 `plugins/NalxerApp/` 下 |
| `http.keystore-password` | 空 | p12 的密码，开了 `tls` 就不能留空 |
| `token.secret` | 自动生成 | 登录令牌的签名密钥，首次启动自动写入，**别外泄** |
| `token.ttl-hours` | `168` | 令牌有效期（小时），168 = 7 天 |
| `login.require-authme` | `true` | `true` = 必须通过 AuthMe 校验游戏密码；`false` = 不校验密码，只放行此刻在游戏里在线的玩家（给正版服 / 没装 AuthMe 的用，安全性更低） |
| `login.attempts-per-minute` | `5` | 同一 IP 每分钟最多尝试登录几次（防爆破） |
| `login.requests-per-minute` | `180` | 同一 IP 每分钟最多请求几次 |
| `admin.players` | `[Linux582021]` | 管理员名单（另外 **OP 自动算管理员**） |
| `admin.commands.enabled` | `true` | 允不允许 App 里的「服务器命令」页执行命令 |
| `admin.commands.allow` | `[linux, banlist, ...]` | 允许执行的**根命令**白名单，用例见下面「命令控制台」 |
| `data.coins/points/level/playtime/team/title` | `true` | 要不要对外提供这几项数据 |
| `debug` | `false` | 打开后控制台会打每个请求 |

改完用 `/nalxerapp reload` 生效，不用重启。

### 3. 命令（控制台 / 游戏内）

| 命令 | 说明 |
| --- | --- |
| `/nalxerapp status` | 看接口状态、联动情况、在线人数 |
| `/nalxerapp reload` | 重载配置 |
| `/nalxerapp token <玩家>` | 给某个玩家手动发一个令牌（排查用） |

### 4. Nginx 反代（必须在 HTTPS 后面）

见 `docs/nginx-api.conf`，核心就三行：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8787;
    proxy_set_header X-Real-IP $remote_addr;
}
```

`X-Real-IP` / `X-Forwarded-For` 一定要传，否则限流会按 Nginx 的 IP 算，
所有人共用一份额度。

域名解析：`api.nalxer.top` → 服务器 IP，然后 `certbot --nginx -d api.nalxer.top`。

---

## 二、接口

所有响应都是 JSON，`{"ok":true,...}` 或 `{"ok":false,"message":"...原因..."}`。

| 方法 | 路径 | 需要令牌 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/health` | 否 | 健康检查 + 各联动是否接上 |
| POST | `/api/login` | 否 | `{"name":"玩家名","password":"游戏密码"}` → 返回令牌 |
| GET | `/api/me` | 是 | 自己的数据 |
| GET | `/api/me?name=xxx` | 是（管理员） | 查别人 |
| GET | `/api/online` | 是 | 在线玩家列表 |
| GET | `/api/admin/search?q=关键字` | 是（管理员） | 搜玩家，返回 `[{name,online,banned}]` |
| GET | `/api/admin/bans` | 是（管理员） | 封禁列表 |
| POST | `/api/admin/ban` | 是（管理员） | `{"name":"x","reason":"原因","minutes":0}`，0/不给 = 永久 |
| POST | `/api/admin/unban` | 是（管理员） | `{"name":"x"}` |
| GET | `/api/players` | 是 | 在线玩家列表：名字 / 等级 / 延迟 / 世界 / 队伍 / 头衔 |
| GET | `/api/chat?since=0` | 是 | 聊天记录（最多 120 条），`since` 传上一条的 id 只回新的 |
| POST | `/api/chat` | 是 | `{"message":"..."}`，最多 200 字；被禁言返回 403 |
| GET | `/api/team` | 是 | 我的队伍 + 队伍列表 |
| POST | `/api/team` | 是 | `{"action":"create|join|leave|kick|disband|transfer","arg":"..."}` |
| GET | `/api/titles` | 是 | 当前头衔 / 已解锁数 / 全部头衔 + 条件 |
| POST | `/api/titles` | 是 | `{"id":"elder"}` 佩戴，`{"id":""}` 卸下 |
| GET | `/api/admin/commands` | 是（管理员） | 命令清单（分组 + 说明 + 危险标记），App 用它渲染按钮 |
| POST | `/api/admin/exec` | 是（管理员） | `{"command":"ltab info"}` → `{ok, output:[回显行]}` |

令牌放请求头：`Authorization: Bearer <token>`。

`/api/me` 返回字段：

```json
{
  "name": "Linux582021", "uuid": "...", "online": true,
  "coins": 1500, "hasCoins": true,
  "points": 567,  "hasPoints": true,
  "level": 12, "exp": 30, "totalExp": 555, "levelLive": true,
  "playtimeSeconds": 36000, "playtimeHours": 10,
  "firstPlayed": 1730000000000, "lastPlayed": 1730500000000,
  "team": "摸鱼队", "teamTag": "§7[摸鱼] §r",
  "title": "§7[初来乍到] §r", "titleId": "newcomer", "titleName": "初来乍到",
  "expIntoLevel": 0, "expToNext": 112, "muted": false,
  "health": 20.0, "maxHealth": 20.0, "food": 20, "gamemode": "SURVIVAL",
  "world": "world", "ping": 0,
  "banned": false, "banReason": null, "banUntil": null
}
```

`hasCoins` / `hasPoints` 为 `false` 表示服务端没装对应插件（App 里显示「—」而不是 0）。

`level` / `exp` / `totalExp` 人不在线时是从 `world/playerdata/<uuid>.dat` 里读 NBT 得来的（1.2.0 起），
所以离线玩家也能看到真实等级，不再是 0。`levelLive` 为 `true` 表示这是实时读的（人在线）。

封禁原因在返回前会洗一遍：去掉 `§` 色码、去掉「你已被此服务器封禁！」这种模板前缀、去掉纯管理员名，
所以 App 里看到的是人写的那句话。

---

## 二之零、聊天 / 队伍 / 头衔（1.2.0 新增）

这三块是 1.2.0 才接上的，都直接读写游戏里的数据，不另存一份。

**聊天**：`ChatBus` 挂在 `AsyncPlayerChatEvent` 的 `MONITOR` 优先级上，游戏里说的话会进一个 120 条的环形缓冲；
App 发消息时走 `POST /api/chat`，服务端在主线程 `broadcastMessage`，格式是 `前缀 + 名字 + "§7: §f" + 内容`，
前缀默认 `&b[LinuxAPP] &f`（`config.yml` 的 `chat.prefix` 可改）。发之前会查 ITNOL 的禁言（`banmsg` 类型），
被禁言直接 403，不会广播。

**队伍**：全部调 LinuxTeam 的 `TeamManager`，支持建队 / 加入 / 退出 / 踢人 / 转让 / 解散。
队长退出会被 LinuxTeam 拒绝（「队长要先转让或解散队伍」），这是插件本身的规定，我们没有绕过。

**头衔**：读 `TitleConfig` 的全部头衔（`conditionType` 有 `playtime` / `daily_playtime` / `statistic` / `festival` / `manual`），
佩戴走 `TitleManager.setActive(name, id)`，**离线也能戴**（LinuxTitle 只在玩家在线时校验权限，我们提前查了 `unlocked`）。

---

## 二之一、插件自带 HTTPS（不想挂 Nginx 的时候）

默认是明文 HTTP，靠 Nginx 转 HTTPS。如果服务器上只有这一个服务、不想再装 Nginx，
可以让插件自己上 TLS（Java 内置 HttpsServer，不用额外依赖）：

```bash
# 1. certbot 签出来的证书转成 PKCS12
openssl pkcs12 -export -out keystore.p12 \
  -inkey /etc/letsencrypt/live/api.nalxer.top/privkey.pem \
  -in    /etc/letsencrypt/live/api.nalxer.top/fullchain.pem \
  -passout pass:你的密码

# 2. 放进插件目录
cp keystore.p12 plugins/NalxerApp/
```

```yaml
http:
  port: 49282
  tls: true
  keystore: keystore.p12
  keystore-password: '你的密码'
```

重启后控制台会打：

```
[NalxerApp] 接口已启动：https://0.0.0.0:49282/api/health（插件自带 TLS，证书来自 keystore.p12）
```

App 里的接口地址填 `https://你的域名:49282`。注意：

- 证书要**和域名匹配**，用 IP 直连手机不认（证书里没有那个 IP）；
- 换了证书要重启服务器才会重新读；
- `tls: true` 但 p12 缺失或密码错时，插件打一条 warning 并**退回普通 HTTP**，不会起不来；
- 端口只放这一个服务时，直接开 443 也行（Linux 上要 root 或 `setcap`）。

---

## 二之二、命令控制台（管理员在 App 里执行命令）

不装一堆 REST 接口，而是给管理员一个**受控的命令执行器**：App 里点命令 → 服务器主线程执行 → 回显原样返回。
这样 ITNOL 的 `/linux xxx`、Linux 系插件的 `/ltab`、`/team`、`/title`、`/fp` 全都能用，插件升级了也不用改 App。

安全边界一共四道：

| 边界 | 做法 |
| --- | --- |
| 谁能用 | 令牌里的 `admin` 必须为真（`admin.players` 名单或 OP），玩家令牌一律 403 |
| 能执行什么 | 只认 `admin.commands.allow` 里的根命令，且以 `;` `|` 分行、换行、`§` 一律拒绝 |
| 永远不能执行 | `stop` `restart` `reload` `op` `deop` `whitelist` `plugins` `version` `give` `gamemode` `gm` `kick` `permissions` `sudo` …（代码里写死的黑名单，改配置也没用） |
| 执行完留下什么 | 每次执行写一行审计：`时间  玩家  控制台执行 xxx`，进控制台也进 `plugins/NalxerApp/audit.log` |

默认白名单（`admin.commands.allow`）：

```yaml
admin:
  commands:
    enabled: true
    allow: [linux, banlist, unban, tempban, banip, unbanip,
            nalxerapp, ltab, lchest, title, team,
            fp, fplist, fpr, fpreload, fpsave, linuxsync]
```

想放开别的根命令，往 `allow` 里加就行（比如 `ban`、`unbanall`），加完 `/nalxerapp reload`。
注意 `/linux ban` 没有时间参数，临时封禁要用根命令 `tempban <玩家> <时间> [原因]`。

超时 5 秒、最多回 40 行 / 4000 字，防止一条命令把 App 撑爆。

---

### 客户端地址随时能改

App 的登录页和「设置」页都能改接口地址，支持三种写法：

- `aam.rainplay.cn:49282`（不带协议，自动补 `http://`）
- `http://服务器IP:49282`
- `https://api.nalxer.top`（挂了 Nginx + 证书的话）

设置页还有「测试连接」按钮，会打一次 `/api/health` 告诉你插件通没通、各联动接没接上。

### 2.5 功能开关（1.3.0）

不想让某个功能出现在 App 里，就在 `features` 里关掉，关掉后对应接口直接返回 `403`：

```yaml
features:
  chat: true        # 聊天（游戏 ↔ App 双向）
  team: true        # 队伍（LinuxTeam）
  titles: true      # 头衔（LinuxTitle）
  checkin: true     # 每日签到
  report: true      # 举报
  penalty: true     # 惩罚（禁言 / 移动 / 方块 / 封禁）
  fakes: true       # 假人管理（FakePlayerPlugin）
  console: true     # 命令控制台（管理员白名单命令）
```

### 2.6 每日签到（1.3.0）

「我的」页那张签到卡，数据存在 `plugins/NalxerApp/checkins.yml`：

```yaml
players:
  steve:
    name: Steve
    last: '2026-11-03'   # 最近一次签到（北京时间）
    streak: 3            # 连续天数
    total: 12            # 累计天数
```

- 奖励：50% 给 **5000 金币**（Vault），否则给 **1–5 点券**（PlayerPoints）。
- 重置：每天北京时间 0 点；断一天连签归 1，累计不清零。
- 两个经济插件只接上一个也能用，会自动退到接上的那一边。
- 想改金额，改 `CheckIn.java` 里的 `COIN_REWARD` / `POINT_MAX` 后重编。

### 2.7 举报 / 惩罚 / 假人（1.3.0）

这三块都直连 InTheNameOfLinux 与 FakePlayerPlugin 的原有数据，不是另存一份：

| App 里的位置 | 背后调用 | 说明 |
|---|---|---|
| 举报列表 | `ReportManager.listReportsNewestFirst()` | 举报人 / 被举报人 / 理由 / 处理结果 |
| 受理举报 | `ReportManager.acceptReport(...)` | 提交后 30 分钟内不能受理（ITNOL 的调查时间） |
| 惩罚列表 | `LinuxPenalty.getAllPenalties()` | 禁言 / 移动 / 方块 / 封禁四类 |
| 开惩罚 | `LinuxPenalty.banMsg/banMove/banBlock/banAll` | `minutes<=0` 为永久 |
| 解除惩罚 | `LinuxPenalty.releaseRecord(type, name)` | 按类型解除 |
| 假人列表 | `FakePlayerPlugin.getFakePlayers()` | 名字 / 世界 / 坐标 / 是否活跃 |
| 召唤假人 | `FakePlayerPlugin.spawnFake(name, player)` | 需要操作者本人正在游戏里 |

## 三、安卓客户端

### 已有安装包

`Nalxer-1.0.0.apk`（约 3.6 MB，自签，装的时候安卓会提示「未知来源」，
选「仍要安装」即可）。

首次打开填接口地址 `https://api.nalxer.top`、游戏 ID、游戏密码，登录一次之后
会记住，下次直接进。

### 自己重新打包

```bash
cd mobile
npm install                 # 只第一次需要
npx cap copy android        # 把 www/ 里的网页同步进原生工程
cd android
./gradlew assembleDebug     # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 正式包：app/build/outputs/apk/release/app-release.apk（已签名）
```

环境要求：JDK 17、Android SDK（platform 34 + build-tools 34）、Node 18+。

签名文件是 `android/nalxer-release.jks`，**不要删**——换签名后新包无法覆盖安装旧包，
必须卸载重装。

### 界面

- **我的**：头像、名字、在线状态、队伍 / 头衔徽标、金币 / 点券 / 等级三张卡、
  经验进度条、在线时长、首次 / 最近进服、UUID、被封禁时的红框提示。
- **管理**（只有管理员看得见这个标签）：搜玩家 → 点选 → 填原因、选时长（永久 / 1 小时 /
  1 天 / 7 天）→ 封禁 / 解封；下面一直显示当前封禁列表，带一键解封。
- **设置**：当前账号、服务器地址、有无管理权限、令牌到期时间、退出登录。

自动刷新：前台每 30 秒拉一次数据。

---

## 四、iOS

iOS 工程已经生成好并配好了图标 / 启动图 / Info.plist，详细步骤见 **[IOS.md](IOS.md)**，
一页写完：拷到 Mac → `npm install` → `npx cap copy ios` → `pod install` → 打开
`App.xcworkspace` → 选 Team → Run / Archive。

能直接拷走的压缩包：`E:\Plugins\Outlay\Nalxer-iOS-1.1.0.zip`（33 个文件，含 web 资源与文档）。

Windows 上只能准备工程、同步前端；**出 ipa 必须在 macOS + Xcode 上做**（苹果签名工具只有 Mac 有）。

## 四之二、用 GitHub Actions 构建

源码已经放到 `liuxuanming2021/InTheNameOfLinux` 仓库的 `app/mobile/` 下，工作流是
`.github/workflows/app-build.yml`，两个 job：

| job | 跑在 | 干什么 | 产物 |
| --- | --- | --- | --- |
| Android APK | ubuntu-latest | `npm install` → `npx cap copy android` → gradlew `assembleDebug` | `Nalxer-android-apk`（debug 签名，能直接装） |
| iOS 未签名 ipa | macos-14 | `npx cap copy ios` → `pod install` → `xcodebuild -sdk iphoneos` 不签名 → 打成 `Payload/App.app` | `Nalxer-ios-ipa`（未签名，Windows 上用 Sideloadly 自签安装） |

触发方式：改 `app/mobile/**` 自动跑，或者 Actions 页面点 **Run workflow**。

注意：

- 工作流文件属于 `.github/workflows/`，推它需要 token 有 **Workflows** 权限；普通
  Contents 写权限会 403（本次就是手工在网页上提交的）。
- CI 里没有 `nalxer-release.jks`，所以安卓是 debug 签名；正式包还是本机
  `./gradlew assembleRelease`（签名文件不进仓库）。
- iOS 那个 ipa 是**未签名**的，只能用来 sideload（Sideloadly / AltStore 用你的
  Apple ID 重签），不能直接上架；上架还是得 Mac 上 Xcode Archive。

---

## 五、安全说明

- 密码走 AuthMe 校验，插件**不存密码**，只发一个 HMAC 签名的令牌（有效期 7 天，可改）。
- 令牌里带 `name|admin|到期时间`，服务端每次请求验签 + 验过期。
- 登录限流 5 次/分钟/IP，其他接口 180 次/分钟/IP。
- 管理操作（封禁 / 解封）全部写 `audit.log`，带操作者名字和时间。
- 接口只监听 `127.0.0.1`，外部只能经 Nginx 的 HTTPS 进来；**不要**做成公网明文 HTTP。
- 管理员判定：`admin.players` 名单 + 服务器 OP。

---

## 六、常见问题

**登录提示「游戏 ID 或密码不对」**
先确认能在游戏里正常登录（AuthMe 密码）；确认 `login.require-authme` 是 true（改成 false 就只认在线玩家了）之外
的奇怪值；看控制台有没有 `[审计] ... 密码错误`。

**数据全是 0 / 显示「—」**
对应插件没装或没启用。看启动日志的 `[联动]` 那几行，`/api/health` 里的 capabilities
也能看出来（`coins:false` 就是没接上经济）。

**离线玩家金币是 0**
Vault 经济插件（EssentialsX）对离线玩家的余额读取要能读到它的用户文件，一般没问题；
如果只有个别玩家异常，让他进一次服再查。

**手机连不上**
`https://api.nalxer.top/api/health` 用浏览器打开能返回 JSON 才算通；直接访问
`http://服务器IP:8787` 是连不上的（只监听本地回环），这是故意的。


## 七、1.3.0 新增接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/checkin` | 签到状态 |
| `POST` | `/api/checkin` | 今日签到 |
| `GET` | `/api/reports` | 我的举报 |
| `POST` | `/api/report` | 提交举报 |
| `GET` | `/api/admin/status` | 插件 / 功能状态 |
| `GET` | `/api/admin/players?q=` | 搜玩家（在线 + 离线） |
| `GET` | `/api/admin/player?name=` | 玩家详情 + 惩罚 + 举报 |
| `GET` | `/api/admin/penalties` | 惩罚列表 |
| `POST` | `/api/admin/penalty` | 开 / 解除惩罚 |
| `GET` | `/api/admin/reports?pending=1` | 举报列表 |
| `POST` | `/api/admin/report` | 受理 / 驳回举报 |
| `GET` | `/api/admin/fakes` | 假人列表 |
| `POST` | `/api/admin/fake` | 召唤 / 移除假人 |

## 七、1.3.1 修了什么

| 问题 | 原因 | 现在 |
|---|---|---|
| 头衔显示「没有」，戴着的也不显示 | `/api/me` 走 `getActiveTitleDef(Player)`，离线没有 Player 对象；`unlocked` 比较大小写敏感，而 LinuxTitle 存的是小写 | 改读 `PlayerDataManager.getActiveTitle(name)` + `TitleConfig.getTitle(id)`，离线也能显示；解锁判断统一转小写比较 |
| 控制台 say 进不了 App 聊天 | RCON 走 `RemoteServerCommandEvent`、stdin 走 `ServerCommandEvent`，面板用 `Bukkit.dispatchCommand` 时两个事件都不发 | 命令侧兼容命名空间与 `/bc`；另加 `LogTail` 盯 `logs/latest.log` 的 `[Server]/[Rcon]/[CONSOLE]` 播报行兜底（`chat.capture-log`，默认开） |
| 加了两个队伍只显示一个 | `/api/team` 只读 `getPrimaryTeam` | 新增 `myTeams`（`TeamManager.getTeams(UUID)`），App 队伍页一队一张卡 |
| 惩罚只有禁言 | App 只写了 banmsg | 禁言 / 禁止移动 / 禁止交互 / 全部禁止 / 封禁，五种都能开、都能解除 |
| 假人只能召唤和移除 | 没接 FakePlayerPlugin 的配置 | 新增「假人设置」：游戏模式（创造/生存/冒险）、无敌开关，以及单个假人「重生」 |

`/api/admin/fake` 现在支持 `op=add|remove|respawn|set`；`/api/admin/fakes` 多返回 `settings`（gamemode / invulnerable / 名字前缀）。

## 八、1.4.x 修了什么

| 版本 | 改了什么 | 为什么 |
| --- | --- | --- |
| 1.4.0 | 聊天页加「世界 / 队伍」频道切换；队伍管理并进聊天页；命令并进管理页 | 之前队伍、聊天、命令各占一个底栏位置，手机上五个页签已经挤；队伍消息和公屏混在一起看不清哪些只有队友能看 |
| 1.4.0 | 修封禁页两个「解封」按钮 | 玩家详情里既生成了 `data-pen="ban"` 对应的撤销，又留了一个独立的 `data-ban="0"`，点了分不清作用的哪一个 |
| 1.4.1 | 假人逐个「加载区块 / 刷怪」开关 | 之前只能召唤和移除，`keep-loaded-chunks`、`spawn.*` 只能改配置文件再重启 |
| 1.4.1 | 举报页分「待处理 / 已处理」 | 只列待处理的，处理完的举报在 App 上再也看不到结果 |
| 1.4.1 | Linux 身份验证（`verify.players`） | 受保护账号（默认 Linux582021）在 App 里登录后要再输一次 ITNOL 密码，没验完除了退出登录什么都干不了 |
| 1.4.2 | su 控制台：验证后执行任意命令 | 白名单永远包不全（装插件、`eco`、`gamemode` 都不在里面），要一个「验明正身之后随便打」的入口 |
| 1.4.2 | 身份验证改成弹窗 | 原来是一整屏，看不到下面是什么；改成盖在界面上的弹窗，并写明「你登陆的是 Linux582021 账号，请通过 Linux 身份验证」 |

### 1.4.0：队伍频道

- `ChatBus` 每条消息多了 `channel` / `teamId` / `teamName` 三个字段；`channel=world` 是公屏，`channel=team` 只有该队的人看得到。
- `GET /api/chat?channel=team&team=<队 id>` 读队内消息（不带 `team` 返回 400）；`POST /api/chat` 带 `{"message":"…","channel":"team","team":"<id>"}` 发到队里，同时用 LinuxTeam 自己的 `ChatService` 广播进游戏，格式 `[队伍] 队标 名字 » 内容`。
- 游戏里用 `/tc` 或在队伍频道说话，也会以 `channel=team` 进 App。
- 队伍接口 `GET /api/team` 多返回 `myTeams`：一个人可以同时在几个队里，App 按这个渲染一队一卡。

### 1.4.1：身份验证

```yaml
verify:
  enabled: true
  players:
    - Linux582021
```

- 这个名单里的账号登录后，`/api/login` 和 `/api/me` 会返回 `verifyRequired: true, verified: false`。
- 验证前除 `/api/me`、`/api/logout`、`/api/verify` 外，其它接口一律 `403 {"code":"need_verify"}`。
- 校验的是 **ITNOL 的验证密码**（走 `LoginManager` 的 PBKDF2 哈希），不动游戏内的登录状态、不写锁定计数；连错 5 次锁 10 分钟。
- 验证状态按「账号 + 令牌过期时间」记在内存里，换令牌要重新验。

### 1.4.2：su

- `POST /api/admin/su`，body `{"command":"…"}`，需要管理员 + 已通过身份验证。
- 和 `/api/admin/exec` 的区别只有一个：**不走 `admin.commands.allow` 白名单**。
- `stop` / `restart` / `end` 仍然拦：这三条一按服务器就没了，App 里补救不了。
- 回显里出现「错误 / 找不到 / Unknown command」时直接返回 `ok=false`，免得 App 假报成功。

### 1.4.x 的命令与接口清单

```
POST /api/admin/su          su：任意命令（验证后）
POST /api/admin/fake        op=add|remove|toggle|respawn，toggle 带 key=load|spawn|covered
GET  /api/admin/reports     pending=1 只看待处理，pending=0 连已处理一起
GET  /api/chat              channel=world|team，team=<队 id>
POST /api/chat              body 里带 channel / team 就发到队伍频道
GET  /api/team              多返回 myTeams
```

## 九、1.5.0 修了什么

| 问题 | 原因 | 现在 |
| --- | --- | --- |
| 登录 Linux582021 没弹身份验证 | 生产服跑的还是旧 jar（换包后没重启）；另外名单为空时会退回 `identityTarget()`，对不上就不拦 | 启动日志与 `/api/health` 直接打印「二次身份验证：开启 …… [名单]」；名单默认 `[Linux582021, fs1]`，没验证除了「退出登录」什么都点不了 |
| su 谁都能用 | `/api/admin/exec`、`/api/admin/su` 之前只校验 admin | 新增 `su.players`（默认 `[Linux582021, fs1]`），三个控制台入口先过 `suAllowed()`，不在名单里一律 403；封禁 / 惩罚 / 举报 / 假人不受影响 |
| 看不到身份 | 之前只显示队伍和头衔 | 接 XlandCore：`isOwner / isCoOwner / isMayor / isModerator`，`/api/me` 多 `identity` 与 `role` 两个字段，App 我的页徽章和设置页「玩家身份」都显示 |
| su 关不掉 | 控制台一直挂在管理页 | 设置里新增「启用 su 控制台」开关（`nalxer.suon`）；只有服务端说 `su=true` 的号才看得见开关；打开时若没验证先弹 Linux 身份验证，通过后才置开；关掉后「命令」子标签和 su 卡一起消失 |
| 假人只能加不能停 | 只用了「区块常驻 / 刷怪」两个开关 | 改用 FakePlayerPlugin 自带的 `setFakeEnabled`：停用 = 撤实体、退出 PlayerChunkMap、不再刷怪，坐标和各项开关全留着；启用 = 回原位重新加载。停用的假人从 `getSavedLocations()` 补进列表，所以 App 里始终看得到、随时能再开 |
| 聊天页一进来在最上面 | 只在渲染时滚一次，且被外层 harness 抢了 | `scrollChatBottom()`：立即 + rAF + 60ms + 240ms 各滚一次；切页时若本地有记录也先滚到底 |
| 频道切换条会跟着滚走 | 频道条在普通卡片里 | `.chat-head` 改 `position: sticky; top: 0; z-index: 25`，队伍选择与提示一起吸顶 |

### 1.5.0 的接口变化

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 多返回 `verify{enabled, players, identity, identityTarget}` 与 `su[]`；`capabilities` 多 `identity` / `xland` |
| GET | `/api/me` | 多返回 `identity`（`{role,label,prefix,roles,hasXland}`）、`role`、`su` |
| POST | `/api/admin/fake` | `op=toggle` 支持 `key=enabled`（停用 / 启用整个假人），另有 `load` / `spawn` / `covered` |

### 1.5.0 的配置变化

```yaml
verify:
  enabled: true
  players:
    - Linux582021
    - fs1

su:
  # 能用「命令控制台」和「su（任意命令）」的号；不在名单里的管理员照样能封禁 / 惩罚 / 举报 / 管假人
  players:
    - Linux582021
    - fs1
```

老配置首次启动会自动补 `su` 段（日志「config.yml 补上了「su 名单」段」），`verify` 段补的时候名单也会带上 fs1。

**换 jar 之后必须重启服务器**：`/nalxerapp reload` 只重读配置，不换类。

## 十、1.6.0 修了什么

| 需求 | 做法 |
| --- | --- |
| 受保护账号登录必须验，不验就进不去 | `verify.players` 里的号登录后强制弹窗，`body.verify-locked` 锁住界面；没验时除 `/api/me`、`/api/logout` 全 403 `need_verify`，点「退出登录」才走 |
| 玩家身份 | `Hooks.identityOf(name)` 反射 XlandCore 的 `isOwner/isCoOwner/isMayor/isModerator`，`/api/me` 与玩家详情都带 `identity {role,label,prefix,roles}` |
| 在线玩家移到管理页 | 管理页子标签：在线 / 玩家 / 举报 / 惩罚 / 假人 / 命令 / su |
| 设置页管 Linux 系插件 | `/api/admin/linux`、`/api/admin/linux/config`、`/api/admin/linux/op`；停用 / 启用 / 重载 / 改 config.yml（先 YAML 校验，旧的留 `.app-bak`） |
| 插件联动只显示 Linux 系 | 前端按名字前缀 `Linux` 过滤 |
| 队伍和聊天分开 | 底栏恢复「队伍」页；聊天页第三个频道「我的队伍」只读查看 |
| 只在切页/切频道时滚到最后一条 | `state.chatScrollPending` + `scrollChatBottom()`（顶上让开标题栏与频道条，底下让开输入框） |
| 登录失败提示真实原因 | `api()` 里 401/403 的业务错误不再被自己的 `.catch` 吞成「连不上服务器」 |
| 命令与 su 分家 | 命令子页只跑白名单；`su` 子页默认隐藏，设置里打开才出现（打开要验一次身份） |
| su 仿真控制台 | 黑底等宽控制台，`>` 提示符、命令回显、`✓ 执行完毕`；服务端以 `Bukkit.getConsoleSender()` 身份执行，从 `logs/latest.log` 增量抓回显（`stop/restart/end` 永远不给） |
| 聊天标题被遮挡 | 顶栏 `z-index:30` 吸顶，频道条 `top: var(--topbar-h)` 贴在它下面，标题与频道条一起可见 |

## 十一、1.7.0 修了什么

| # | 问题 | 根因 | 现在 |
|---|------|------|------|
| 1 | 假人插件不在「Linux 系插件」里 | `isLinuxPlugin` 只认 `Linux` 前缀 | 改认 `Linux` / `FakePlayer` 两个前缀，`/api/admin/linux` 与设置页列表都能看到 FakePlayerPlugin |
| 2 | 身份验证一直没用 | 弹窗只在个别入口触发，服务端 `needsVerify` 常返回 false | 整套拿掉：`needsVerify()` 直接 `return false`，客户端删掉验证弹窗，su 也不再要求验证 |
| 3 | 头像是离线默认皮肤 | 用离线 UUID 走 crafatar，离线 UUID 查不到皮肤 | 改按玩家名取：`mc-heads` → `crafatar` → `minotar` 三级回退，最后才用首字母 |
| 4 | 队伍只有退出 / 解散 | `teamAction` 只做了 5 个动作 | 补 `invite`（邀请在线玩家，60 秒内 `/team accept`）、`tag`（改队标，≤4 字）、`setting`（队伍聊天开关）、`transfer`，全部支持指定 `team` id |
| 5 | 举报只看得到待处理 | 接口只返回 `pending` | `/api/admin/reports?filter=all|pending|handled`，App 里三个筛选标签 + 「N 条 · 待处理 X · 已处理 Y」 |
| 6 | 离线改不了队标 / 队伍设置 | `teamAction` 开头要求玩家在线 | `tag` / `setting` 不碰 Player，按名字取 UUID 校验队长即可离线操作 |

队伍接口形状（1.7.0）：

```json
POST /api/team  {"action":"tag","arg":"测试队","team":"841e386201d24d80"}
POST /api/team  {"action":"setting","arg":"chat:off","team":"841e386201d24d80"}
POST /api/team  {"action":"invite","arg":"Steve","team":"841e386201d24d80"}
GET  /api/admin/reports?filter=handled
```

## 十二、26.11.8 修了什么

版本号从这里开始和服务端插件对齐（`26.11.8` = 年份.月份.序号），不再用 1.x。

| 问题 | 原因 | 修法 |
| --- | --- | --- |
| 队伍功能大多无效（建队 / 加入 / 退出 / 踢人 / 转让 / 解散都不动） | ① LinuxTeam 的 `kick(Team,UUID)` / `disband(Team)` / `transferLeader(Team,UUID)` 是 void，插件按返回值判成功，永远是「失败」；② `teamAction` 开头只要玩家不在游戏里就整个返回「你现在不在游戏里」 | void 方法改成「先自己校验前置条件，再当成功」；离线时用 `Player` 动态代理（只实现 `getUniqueId` / `getName`）走过 LinuxTeam 的入参检查 |
| 举报页切「待处理 / 已处理」报 `Can't find variable: showHandled` | 空列表文案里引用了不存在的变量 | 文案按 `filter` 分三支：全部 / 待处理 / 已处理 |
| 搜索玩家点「详情」后还留在搜索页 | 详情是内嵌在管理页里的一块 DOM | 新增 `#page-pdetail` 独立页，点详情直接 `switchPage('pdetail')`，左上角「← 返回玩家列表」回管理页 |
| 设置页内容散乱、没有作者和版本 | 历史原因堆在一起 | 重排成 账号 / 显示 / 管理 / 接口地址 / 插件联动 / 关于软件 / 退出登录，页面最底一行「Nalxer App 26.11.8 · 作者 Linux582021」 |
| 普通玩家也能看到「改 Linux 系插件配置文件」 | 管理卡片只按 `admin` 判断，没拆子卡 | `#admin-panel-card` 单独按 `state.admin` 显隐 |

配套接口没变，只是 `/api/team` 的 `action` 现在离线也能用：`create` / `join` / `leave` / `kick` / `transfer` / `disband` / `invite` / `tag` / `setting`，带 `team` 参数时只作用于那一支队。

## 十三、26.11.11 硬依赖 ITNOL

| 改了什么 | 说明 |
| --- | --- |
| 举报不再「猜」 | 以前用反射调 `ReportManager`，线上 ITNOL 版本对不上就 `NoSuchMethodException`，App 里报「服务器没有 ITNOL 插件」、已处理的举报读不出来。 |
| 硬依赖 | `plugin.yml` 写 `depend: [InTheNameOfLinux]`，`pom.xml` 里用 `libs/InTheNameOfLinux.jar` 编译，缺插件直接不启动并在控制台说明原因。 |
| 直调 API | 举报 `ReportManager.listReportsNewestFirst / getReport / createReportByName / acceptReport / rejectReport / getPendingCount`；惩罚 `LinuxPenalty.banMsg/banMove/banBlock/banAll`、`unban*`、`getAllPenalties`；封禁 `BanManager.banPlayer/tempBanPlayer/unbanPlayer`；身份 `LoginManager.verifyPasswordFromApp`。 |
| 两个 jar 配套 | 发布时必须同时替换 `plugins/NalxerApp.jar` 和 `plugins/InTheNameOfLinux.jar`，老 ITNOL 缺 `verifyPasswordFromApp` / `listReportsNewestFirst` / `createReportByName` 这几个 App 专用方法。 |
| `全部惩罚` 解除 | `unbanAll` 对不上记录类型，改为回退 `releaseRecord("banall")` / `releaseRecord("all")`。 |

## 十四、26.11.12 修了什么

| 问题 | 根因 | 改法 |
| --- | --- | --- |
| 加入队伍成功却弹「加入失败」 | `LinuxTeam` 的 `TeamManager.join(Team, Player)` 是 `void`，返回 null，代码用 `Boolean.TRUE.equals(ok)` 判定，永远 false | join 前自己校验「已在队里 / 满员 / 上限」，调用后直接当成功；`kick` / `disband` / `transferLeader` 同样按 void 处理 |
| 没有转账 | 客户端和服务端都没有对应接口 | 反射调 `NalxerECO` 的 `EconomyHook.pay` / `PointsHook.pay`（不改那个插件），新增 `GET/POST /api/transfer`，聊天输入框旁加「转账」按钮（金币 / 点券两页） |
| 聊天同一条显示两遍 | 服务端同一句可能被记两次；客户端并发拉取 + 无 id 去重 | `ChatBus.add` 同一 name+text+source+channel 1.5 秒内只存一次；客户端 `loadChat` 串行 + 按消息 id 去重 |
| 偶尔看不到最新消息 | 并发请求互相覆盖 `state.chatLast` | 串行化后只有一次在飞，`state.chatPending` 收尾补拉 |
| 上下线没提示 | 没有监听 join/quit | `ChatBus.onJoin/onQuit` 记 `source=notice` 的系统消息，App 里画成居中灰条 |
| 在线玩家点「管理」还要再搜一次 | 点击后只是把名字填进搜索框 | 改成直接 `playerDetail(name)` 进详情页 |

配套：服务端 `NalxerApp.jar` 需要和 `InTheNameOfLinux.jar` 一起换（26.11.11 起是硬依赖）。

## 十五、26.11.14 NalxerLink 的安卓消息进公屏

| 改了什么 | 说明 |
| --- | --- |
| 来源能分清 | 聊天页每条消息后面现在有三种来源：`· 游戏`（游戏里说的）、`· App`（NalxerApp 自己发的）、`· FS1安卓端`（装了 NalxerLink 的安卓客户端发的）。 |
| 怎么认出来的 | `NalxerLink` 的 `ChatService.post(name, text)` 会 `Bukkit.broadcastMessage("§7[§bApp§7] §f名字§7: §f内容")`，这行会落到 `logs/latest.log`。`LogTail` 按 `[App] 名字: 内容` 解析成 `source=link`，名字用真实玩家名。 |
| 不碰那个插件 | 全程只读日志文件，`NalxerLink` 怎么升级都不影响；不用它的 HTTP 接口、不需要 token。 |
| 日志前缀兼容 | Paper 的 `[22:24:01] [Server thread/INFO]: …` 会被逐层剥掉，只剥「时间」和「线程/级别」两种方括号，`[App]` / `[Server]` 这种正式前缀留着。颜色码先 `stripColor` 再匹配。 |
| 配置 | `chat.capture-link: true`（开关）、`chat.link-speakers: []`（额外认哪些前缀，默认已含 `app`/`fs1`/`nalxerlink`）。 |
| 启动提示 | 控制台会打 `[联动] 已接上 NalxerLink 1.24.0 —— 安卓客户端发的消息会进公屏，标「FS1安卓端」`；没装则提示装了之后的行为。 |
| 聊天刷新 | 聊天页每 3 秒拉一次（原来 5 秒且只在进页面时拉），不在聊天页时降频到 12 秒只点亮红点；新消息按 id 去重追加，不会整页重画。 |
| 转账按钮 | 和「发送」同宽同高（`min-width:76px`、同一套 padding/字号）。 |

名字取的是 NalxerLink 广播出来的那个名字，也就是**对方在 FS1 安卓客户端登录时用的账号**
（NalxerLink 的 `ApiServer.handleLogin` 里 `session.username` 来自密码校验通过的账号名，
`ChatService.post(session.username, text)` 原样广播），不是写死的「FS1玩家」这种标签。

实测（测试服 Paper 1.12.2 + `NalxerLink-1.24.0.jar`）：往 `logs/latest.log` 追加
`[00:01:00] [Server thread/INFO]: §7[§bApp§7] §fLinux582021§7: §f来自 FS1 安卓客户端的消息` →
`/api/chat` 返回 `{"source":"link","name":"Linux582021","text":"来自 FS1 安卓客户端的消息"}`，
App 聊天页显示 `Linux582021 · 23:59 · FS1安卓端`。

## 十六、26.11.15 主题 / 实时刷新 / 执行人

| 改了什么 | 说明 |
| --- | --- |
| 主题功能 | 更多页「显示」卡里多了一行「主题」，点开是二级菜单：风格（**默认** / **Miuix**）+ 颜色（天蓝色 / 淡粉色 / 淡绿色 / 古董白 / 黑色 / 灰色）。选完立刻生效，记在 `localStorage`（`nalxer.skin` / `nalxer.accent`），重开也还在。默认就是天蓝色。 |
| 两套风格 | `body[data-skin="miuix"]` 一整套覆盖：大圆角（卡片 20px / 弹窗 24px）、卡片去边框改用阴影、按钮和开关改胶囊、分段控件变胶囊底 + 白色滑块、输入框改浅底无边框、底部标签栏去上边框加阴影。 |
| 六种颜色 | `ACCENTS` 里每色都有浅色和深色两套（`--accent` 与 `--accent-soft`），切深色时自动换成对应的深色版，不会出现深底深字。 |
| 聊天实时刷新 | 服务端 `/api/chat` 的 `last` 原来取的是**全局** lastId，游标会被别的频道推过去；服务器重启后 id 从 1 重来，旧游标永远查不到新消息。现在返回「本次实际返回的最后一条 id」，并在 `since` 比服务端还大时回 `reset: true`，客户端收到就清空重拉。 |
| 客户端轮询 | 聊天页 2 秒一轮（原 3 秒）；`state.chatFetchAt` 做 8 秒卡死自愈；不在聊天页时降频只查红点；切到后台再回来（`visibilitychange`）立刻刷新一次。 |
| 每次切页都刷新 | 新增 `refreshPage()`：切到哪一页就重拉那一页的数据（我的 / 聊天+队伍 / 队伍 / 头衔 / 管理当前子页 / 玩家详情 / 更多），右上角刷新按钮也走它。 |
| 只认 `[APP]` 日志 | `LogTail` 现在只把 `[APP] 玩家名：内容`（大小写不敏感）当成 FS1 安卓端的消息；`[NalxerLink] [Link] 有效 token：0` 这类插件自己的运行日志全部忽略（`IGNORED_SPEAKERS` + `speaker.startsWith("nalxer")`）。 |
| 执行人写 APP端 | 从 App 里做的举报处理、禁言/禁止移动/禁止交互/全部禁止、封禁/解封，记录里的执行人统一写成 `APP端（用户名）`（`Hooks.appActor`），游戏里做的还是原来的名字。 |

实测（测试服）：`/api/chat?since=999999` → `reset=true`；追加 `[APP] 实时测试号：不刷新页面也能看到这条` 后，
已打开的聊天页 3 秒内自动出现该条，无需任何操作；`[NalxerLink] [Link] 有效 token：0` 不会进公屏；
App 里对 TabCheck 禁言 5 分钟 → `/api/admin/penalties` 里 `operator` 为 `APP端（AppTest）`。

## 十七、26.11.16 iOS 风格 / 悬浮导航 / 分段控件 / 默认页

| 改了什么 | 说明 |
| --- | --- |
| 分段控件圆角与阴影 | 以前滑块自己带 `border-radius:999px` + 外投影，套在带 3px 内距的胶囊容器里，圆角对不齐、阴影还会溢出容器（看起来就是「r角不对」）。现在容器 `overflow:hidden` 把滑块裁进同一条曲线，滑块不再自己投影；Miuix / iOS 的条目多了改横向滑动，不折行。 |
| 新增 iOS 风格 | `body[data-skin="ios"]`：卡片改半透明 + `backdrop-filter` 液态玻璃、顶部栏和弹窗一起毛玻璃、系统式分段控件（灰底 + 白滑块，12px 圆角）、按钮 14px 圆角带投影、主按钮带强调色光晕。 |
| 悬浮导航栏 | 主题弹窗里多了「悬浮导航栏」开关：底栏变成浮起来的一颗胶囊（`body[data-nav="float"]`），半透明 + 毛玻璃 + 大投影，选中项一颗浅色胶囊；iOS 风格默认开，其他风格默认关，随时可改。开了之后页面底部内距、聊天输入条、提示条都会自动上移，不会被挡住。 |
| Miuix 不只是换色 | 卡片内距 18/16、圆角 20、行高加大、`btn-mini` 改胶囊、分段控件横向滑动、页面边距 12 —— 布局跟着 Miuix 的观感走。 |
| 默认落在「我的」 | 登录成功、退出后重新登录、带 token 冷启动，都强制落回「我的」页（`enterApp()` 里 `state.page='me'`），不会再接着停在退出前那一页。 |
| ITNOL 回到插件管理 | `isLinuxPlugin` 加上 `InTheNameOfLinux`，「插件联动」和「管理 Linux 系插件」里都能看到它；但它的配置读写 / 启停 / 重载只对 `Linux582021` 开放（`isSuperOnlyPlugin` + `denySuperOnly`），别人拿到的是 403「InTheNameOfLinux 的配置只有 Linux582021 能改」，客户端那颗按钮也是灰的。 |

实测（测试服）：`/api/admin/linux` 返回 8 个插件，其中 `InTheNameOfLinux 26.11.6-RC superOnly=true`；
用 AppTest 读 ITNOL 配置 → `403 InTheNameOfLinux 的配置只有 Linux582021 能改`；同一账号读 LinuxTAB 配置 → `200`。

## 十八、26.11.17 动效 / 合体分段控件 / 管理层名单

| 改了什么 | 说明 |
| --- | --- |
| 开屏动画 | `<body>` 里加了 `#splash`（N 标 + Nalxer + 进度条），登录页或主界面准备好后淡出；加 `.hide` 缩放淡出，`hideSplash()` 用 `splashGone` 保证只收一次，另有 2.5 秒兜底。 |
| 切页过渡 | `switchPage()` 记 `prevPage`，按底栏顺序（我的 / 聊天 / 队伍 / 头衔 / 管理 / 更多）决定新页从左还是右滑入；详情页这种非底栏页用淡入；动画类 360ms 后移除，`void el.offsetWidth` 强制重排让动画能重播。顶栏标题同步做一次淡出淡入（`.topbar.swap`）。 |
| 底栏与弹窗过渡 | 底栏每项 `transition` 颜色/背景/位移，选中项图标轻微上浮放大、按下缩到 0.94；弹窗改用 `opacity + visibility` 过渡（`.modal.hidden` 保留 `display:flex` 才能过渡），内容从 `translateY(10px) scale(.985)` 归位。`prefers-reduced-motion` 下全部关闭。 |
| 分段控件改成合体按钮 | 以前是「胶囊里一颗滑动的滑块 + 左右箭头横滑」，容易误触。现在是一整条带分隔线的合体按钮组：容器 14px 圆角、内部分隔线、选中项用强调色浅底，条目多时自动换行、文字居中，不再横滑。 |
| 控件居中 | `.seg > *` / `.seg > .btn` / `.seg > .seg-btn` 统一 `flex:1 1 auto; min-width:0; display:flex; justify-content:center; text-align:center`，行内按钮组也居中；同时修掉「6 颗队伍按钮被挤成竖排」——根因是 iOS/Miuix 皮肤里还留着 `white-space:normal` 的旧规则，特异性压过新规则。 |
| 管理层名单 | 管理页「在线」子标签新增「管理层」：服主 / 副服主 / 管理员（OP）/ 协管，**在不在线都列**，先按身份等级、同级在线优先、再按名字；每行一颗「详情」按钮直接进玩家详情页。后端 `GET /api/admin/staff` → `Hooks.staffList()`（名单读 XlandCore 的 `owners` / `co-owners` / `moderators`，管理员读 `Bukkit.getOperators()`）。 |
| 保护对象不再假成功 | ITNOL 对 `protected-player` 的四种功能惩罚是**静默 return**（void 方法），App 以前照样回「已对 X 开禁言」。现在 `Hooks.isProtectedTarget()` 先按 `PluginConfig.protectedPlayer` 判断，命中就直接回「「X」是服务器的保护对象，ITNOL 会拦住这类惩罚，App 里不给执行」，并且 `penaltyAction` 的 `ok` 判定补上「不给执行 / 不能 / 保护对象 / 拦」这些词 → HTTP 400，客户端不会再弹成功。封禁走 `BanManager.banPlayer`，它本身返回 false 并回「不能对 X 执行封禁操作」。 |
| 详情页返回修好 | 返回按钮点了又被拽回详情页：①`refreshPage()` 在 `page==='pdetail'` 时又调 `playerDetail()`，而它末尾又 `switchPage('pdetail')`，形成自调用；②详情请求慢，返回后旧请求才回来，又把页面切回详情。修法：`playerDetail(name, silent)`，静默刷新不再切页；`state.detailSeq` 序号，返回时 `+1` 作废还在飞的请求，回调里 `if (seq !== state.detailSeq) return;`。 |

实测（测试服）：`/api/admin/staff` 返回 14 人 —— 服主 2（fs1、Linux582021）、副服主 2、管理员 7、协管 3，全离线时按名字排；对 `Linux582021` 发 `banmsg`/`all`/`ban` 全部 `400` 并回保护对象文案，对 `TabCheck` 正常 `200 已对 TabCheck 开 禁言（10 分钟）`、解除也 200。

## 十九、26.11.18 iOS 配色回退 / 开屏停 2 秒 / 列表与开关修正

| 问题 | 根因 | 现在的做法 |
| --- | --- | --- |
| iOS 风格太「玻璃」 | 26.11.16 给 `.card` / `.topbar` / `.icon-btn` / `.modal-card` / `.chat-head` 加了半透明 + `backdrop-filter` | 全部改回 `var(--card)` / `var(--bg)` 实底、去掉 `backdrop-filter`；圆角、内距、胶囊按钮、悬浮导航等**布局不动** |
| 开屏一闪而过 | `hideSplash()` 准备好就立刻收 | 记录启动时刻，最少显示 **2000ms** 再淡出，2.5s 兜底 |
| 管理列表位置 | 放在了在线玩家上面 | 挪到「在线玩家」**下面** |
| 详情按钮有概率无效 | 手指按下到 click 之间列表被重绘，按钮节点被换掉 | ①整行可点（行带 `data-manage`）；②`pointerdown` 记时间，1 秒内不重绘；③`playerDetail` 带序号，返回时作废在飞请求 |
| 开关不吃主题色 | iOS 皮肤把「开」写死 `var(--green)` | 统一 `background: var(--accent)`，三套皮肤都跟随主题色 |
| 分段控件顶出圆角 | 皮肤版 `.seg` 用了 `overflow: visible` | 改回 `overflow: hidden`；聊天页吸顶条去掉左右负边距 |
| 接口地址输入框太窄 | `#f-server-edit` 没套通用输入框样式（实测 177/721） | 并进通用输入框规则，现在 691/721 铺满；`#f-team-name` / `#f-fake` / `#f-su2` / `#f-linux-cfg` 一并处理 |

## 二十、26.11.19 分段控件（iOS 16/17 风格）与队伍按钮网格

| 改动 | 细节 |
| --- | --- |
| 分段控件重做 | `.seg` 变成 iOS 16/17 样式：`--chip` 灰轨道 + `padding: 3px` + `gap: 2px`，选中项是 `var(--card)` 的圆角滑块 + 柔和投影；去掉了原来「合体按钮 + inset 竖分隔线」的做法（换行时分隔线会断在半空） |
| 换行也整齐 | `flex-wrap: wrap` + `flex: 1 1 auto`，条数多时自动换行，滑块各自独立 |
| iOS 皮肤更接近系统 | 轨道 `rgba(120,128,145,0.16)`、滑块 `box-shadow: 0 3px 8px rgba(15,23,42,.12)`；深色用 `rgba(255,255,255,.10)` 轨道 + `.16` 滑块 |
| 选中文字 | 从 `--accent` 改成正文色（iOS 就是深字 + 白滑块），强调色只留给主按钮与图标 |
| 队伍动作 | `#team-actions` 与 `teamCard()` 的动作区都改成 `.action-grid`：3 列网格、`var(--chip)` 圆角按钮；`:only-child` 时 `grid-column: 1 / -1` 占满整行 |
| 踩坑 | 皮肤规则 `body[data-skin="ios"] .seg-btn`（0,2,1）会压过 `.action-grid .seg-btn`（0,2,0），导致网格按钮还是透明无圆角 → 用 `.action-grid.action-grid > .seg-btn`（0,3,0）与 `body[data-skin="ios"] .action-grid .seg-btn` 双保险 |

## 二十一、26.11.20 分段控件：白色滑块滑动动画

- `.seg` 里插入一个 `<i class="seg-thumb">`，绝对定位在轨道内（`z-index: 0`），选中项文字 `z-index: 1`。
- `placeSegThumb(seg)` 用选中项的 `getBoundingClientRect()` 减去容器 rect，写 `transform: translate(x, y)` 与 `width/height`，因此换行（第二行）也能正确落位。
- CSS：`transition: transform .28s cubic-bezier(.32,.72,0,1), width/height .28s`；`prefers-reduced-motion` 下关闭。
- 监听：`MutationObserver`（childList + class 变化）→ `scheduleSegSync(true)`；点击任意 `.seg` 内按钮后 `setTimeout(..., 0)` 同步一次。
- 新建滑块先 `transition: none` 落位，`void thumb.offsetWidth` 触发重排后再放开过渡，避免从 (0,0) 飞入。
- 暗色 / iOS 皮肤各自覆盖滑块底色与投影；`.seg > .active` 的背景与阴影置空（`!important`），统一由滑块承担，避免新旧两块叠在一起。

## 二十二、26.11.21 白块修正 / 统计卡不再挤 / 底栏平移

- 滑块被皮肤规则盖成透明：`.seg > *` 里写了 `background: transparent`，特异性 (0,2,1) 压过 `.seg-thumb` (0,1,0)，算出来的白块是 `rgba(0,0,0,0)`。改为 `.seg-thumb { position: absolute !important; background: var(--card) !important }`，深色与 iOS 各自 `!important` 覆盖。
- 主页统计卡：`grid-template-columns: repeat(3, minmax(0,1fr))`、`gap: 11px`、`padding: 12px`，数字按长度分档（`long` 15px / `xlong` 12.5px / `tiny` 10.5px）+ `text-overflow: ellipsis`，`setStat` 的阈值改成 8/10/13。
- 底栏高亮块：`.tab-thumb` 绝对定位在 `.tabs` 内，`placeTabThumb()` 用 `offsetWidth/offsetHeight` 与 `getBoundingClientRect()` 定位，`transition: transform .26s cubic-bezier(.32,.72,0,1)`；`switchPage` 里先播一次、260ms 后无声补一次（防止首帧布局未稳）。

## 二十三、26.11.22 队伍并入社交页

- 底栏顺序：`我的 / 社交 / 头衔 / 管理 / 更多`（`PAGE_ORDER = ['me','chat','title','admin','more']`，`PAGE_IDS` 删掉 `team`）。
- `#page-chat` 里新增 `#team-manage`（原来 `#page-team` 的三张卡整体搬入），频道控件 `#chat-channels` 改为 `公屏 / 队伍频道 / 队伍`，`data-chat="world|team|manage"`。
- `renderChatChannels()` 三态：`manage` 时隐藏 `#chat-list`、`.chat-input-bar`、`#chat-online`，显示 `#team-manage`；`world/team` 反之。
- `setChatChannel('manage')` 直接 `loadTeam()`；`refreshPage()` 的 chat 分支继续同时拉 `loadChat()` 与 `loadTeam()`。
- ★ 踩坑：改写 HTML 时先算好 `start/end` 索引再插入新块会导致索引失效、切错区间（曾把聊天页切坏，后用 `android/app/src/main/assets/public/index.html` 这份打包副本恢复）。正确顺序是先删除、再在结果字符串上做替换。

## 二十四、26.11.23 底栏高亮块恢复原尺寸 / 分段控件补同步

- 底栏：`placeTabThumb()` 的收边从 12px 改成横向 4px、纵向 6px（`offsetWidth - 4` / `offsetHeight - 6`，位移 `+2 / +3`），视觉上跟以前 `background` 画在页签上一样大。
- 分段控件：`openTheme` / `openTransfer` / `openLinux` / `openLinuxConfig` / `setAdminTab` 与 `renderChatChannels()` 末尾都补一次 `setTimeout(syncSegs, 20~30ms)`，保证弹窗刚打开、列表刚重绘时白块也落到位；此前只有 MutationObserver，弹窗首次打开会看到白块跳。
- `.seg-thumb` 阴影改成 `0 0 0 1px rgba(15,23,42,.045), 0 2px 5px rgba(15,23,42,.10), 0 6px 14px rgba(15,23,42,.08)`，浅色底上边界更清楚。

## 二十五、26.11.24 上下回弹 / 底栏满格高亮 / 重绘控件也平移

- 回弹：`bindBounce()` 监听 `touchstart/touchmove/touchend`，只在「已在顶部还往下拉」或「已在底部还往上拉」时接管，位移 `min(110, |dy|) * 0.42`，松手 `transform .45s cubic-bezier(.22,.61,.36,1)` 归零；`touchmove` 里 `preventDefault()` 阻止原生滚动叠加。弹窗、`.chat-list`、`.mc-console`、`.tabs`、`.seg` 内不接管。
- CSS：`html, body { overscroll-behavior-y: none }`（关掉 Chrome/Android 自带边缘效果），`.page { will-change: transform }`。
- 底栏：`placeTabThumb()` 改为 `active.offsetWidth / offsetHeight` 满格定位，圆角 18px（悬浮导航 20px）。
- 分段控件：`placeSegThumb()` 把上一帧的盒子存在 `seg._thumbBox`，重建时先用旧盒子落位再滑到新盒子，解决 `#cmd-groups`、`#chat-team-pick` 每次重绘导致白块闪一下的问题。

## 二十六、26.11.25 重建后的分段控件也能平移

- `placeSegThumb()` 记住上一次的盒子（`seg._thumbBox`）。若白块已被销毁（按钮被 `innerHTML` 重建），
  新建时先落位到旧盒子，强制一次重排，再在 `requestAnimationFrame` 里写到新盒子，过渡就能跑。
- 实测：重建 `#chat-channels` 后 40ms/120ms/220ms/340ms 的 transform 依次 36.6 / 211.2 / 233.6 / 236px。
- 底栏高亮块：`width = active.offsetWidth + 10`、`height = active.offsetHeight + 4`、位移 `-5 / -2`，圆角 18px（悬浮 20px）。
- 回弹：`MAX = 150`、`DAMP = 0.55`，松手 `transform .42s cubic-bezier(.22,.61,.36,1)` 归零。

## 二十七、26.11.26 权限收紧 / 聊天弹窗 / 历史消息

- 插件配置（Linux 系 + 假人 + ITNOL）的读、写、启停、重载全部只对服主开放：`isSuperOnlyPlugin` 现在对所有插件为真，`isSuperUser` 读 `su.players`（默认 `Linux582021` / `fs1`）；`/api/me`、`/api/login` 多返回 `owner`。
- 客户端 `loadLinux()` 用 `locked = !!p.superOnly && !state.owner` 置灰按钮，`linuxOp()` 前置拦截并 toast「插件配置只有服主能改」。
- 主题入口不受身份限制。
- 聊天气泡入场：`renderChat()` 用 `state.chatRenderedMax` 判断哪些是本次新到的，新气泡带 `.fresh`，CSS `bubbleInUp` / `bubbleInSide` 0.26s。
- 顶部弹窗 `#msg-pop`：`showMsgPop(m)` 1.5s 自动收；开关 `#sw-notify`（`nalxer.notify`，默认开）；非聊天页的轮询命中新消息也会弹。
- `loadChat(reset)`：进社交页 / 切频道时清空 `chatMsgs`/`chatLast`/`chatRenderedMax` 并从头拉 200 条。
- 底栏高亮块：圆角 18px（悬浮 22px），尺寸 +6 / +2，位移 -3 / -1。

## 二十八、26.11.27 管理入口只给服主

- `applyOwnerPanels()`：`#admin-panel-card`（su 开关 + Linux 系插件 + 联动摘要）与 `#status-card`（插件联动状态）只在 `state.owner` 为真时显示；`enterApp()` 与每次 `loadMe()` 都会刷新。
- 普通管理员在「更多」页只剩 账号 / 显示 / 接口地址 / 关于；底栏「管理」页的在线、玩家、举报、惩罚、假人、命令、su 不受影响。
- 服务端 `denySuperOnly()` 继续兜底（非服主 403「插件配置只有服主能改（插件名）」）。

## 二十九、26.11.28 提醒音 / 振动 / 系统通知 + Miuix 圆角

- 提醒三件套：
  * 声音：`alertSound()` 用 WebAudio 两声短音（880Hz、1174Hz，各 0.14s），不依赖音频文件。
  * 振动：`alertVibrate()` 优先 `Capacitor.Plugins.Haptics.impact({style:'MEDIUM'})`，失败退回 `navigator.vibrate(180)`。
  * 系统通知：`pushSystemNotice(m)` 在 `document.hidden` 且「软件外提醒」开着时，用 `LocalNotifications.schedule` 推一条（标题 = 发言人 + 频道，正文 = 内容）。
- 两个开关：`nalxer.notify`（软件内）与 `nalxer.notice.out`（软件外），`renderNotifySwitch()` 同步两个 `aria-checked`。
- 权限：`enterApp()` 与打开开关时调 `askNoticePermission()`（`checkPermissions` → `requestPermissions`）。
- 弹窗时机：`renderChat()` 不再判断 `state.page !== 'chat'`，聊天页也弹；回前台补拉时用 `state.suppressPop` 压 1.6 秒，避免一次弹几十条。
- 依赖：`@capacitor/haptics@6.0.3`、`@capacitor/local-notifications@6.1.3`（`npx cap sync` 会写进 Android settings.gradle 与 iOS Podfile）；AndroidManifest 加 `VIBRATE` / `POST_NOTIFICATIONS` / `RECEIVE_BOOT_COMPLETED` / `SCHEDULE_EXACT_ALARM`。
- Miuix：`.seg` 与子项 `border-radius: 999px !important`，`.seg-thumb` 胶囊 + `--bg-soft`；输入框 `border-color: rgba(15,23,42,.13) !important`（Miuix 的 `--card-b` 是透明的）；卡片 26px、弹窗 28px、气泡 18px。

## 二十九、26.11.29 提醒水位 / 系统通知 / 名字不分大小写

| 改动 | 说明 |
| --- | --- |
| 提醒水位 | `state.notifiedMax` 记录已提醒过的最大消息 id，`notifyNewest(list)` 只对更大的 id 弹窗；`d.reset` 时重置水位。切页面、点社交按钮、回前台补拉都不会重弹旧消息。 |
| 软件内提醒 | 2 秒自动收；`alertSound()`（WebAudio 880Hz/1174Hz 两声）+ `alertVibrate()`（`@capacitor/haptics` 的 `impact(MEDIUM)`，退回 `navigator.vibrate`）。 |
| 软件外提醒 | `@capacitor/local-notifications`，`document.hidden` 且开关打开时 `schedule` 一条系统通知；两个开关分别存 `nalxer.notify` / `nalxer.notice.out`。 |
| 名字不分大小写 | `Hooks.resolveName` 依次：在线玩家 equalsIgnoreCase → ITNOL `BanManager.getKnownPlayerNames()`（缓存 5 分钟）→ `resolveKnownName` → `Bukkit.getOfflinePlayers()`。登录、详情、惩罚、封禁、转账、管理员判定全部先过它。 |
| 管理默认页 | `switchPage()` 进 `admin` 且非返回场景时 `state.adminTab = 'online'`。 |
| 详情返回 | `state.detailFrom` 记住来源子标签，返回时 `state.keepAdminTab = true` 再 `switchPage('admin')` + `setAdminTab(back)`。 |
| iOS 状态栏 | 新增 `@capacitor/status-bar`，`syncStatusBar(dark)`：`style: dark ? 'DARK' : 'LIGHT'`；`Info.plist` 的 `UIViewControllerBasedStatusBarAppearance=true`、`UIStatusBarStyle=UIStatusBarStyleDefault`。 |
| Miuix 圆角 | `.seg` / 子项 / 开关 / 按钮 999px，输入框 `border-color: rgba(15,23,42,.13)`，卡片 26px、弹窗 28px、气泡 18px。 |

## 三十、26.11.30 队伍申请与队长审批

- LinuxTeam 只有一个 5 分钟的 `Invite`（`TeamManager.requestJoin` 内部 `invites.put(uuid, new Invite(teamId, leader, now + 300000L))`），没有审批队列，也没有 accept/reject。所以审批队列由 NalxerApp 自己存：`plugins/NalxerApp/team-apply.txt`，一行 `teamId|teamName|player|epoch`（`TeamApply.java`）。
- `Hooks.teamRow` 多给三个字段：`publicJoin`（读 `getSettingBool("public-join", true)`）、`friendlyFire`（`friendly-fire`）、`applyNeeded`（`!publicJoin`）。
- 服务端新方法：`teamApplyJoin(player, teamId)`（公开队回「直接点加入」，已申请/已在队/满员各自提示，写入申请并通知在线队长）、`teamApplications(teamId)`、`teamDecision(actor, teamId, applicant, accept)`（非队长拒绝；同意时离线用 `ghostPlayer` 落盘，回执带「他现在不在线，上线后就在队里」）。
- `join` 分支前置拦截：`public-join=false` 时直接回「需要队长批准：点“申请加入”，等队长处理」，不再自己 join。
- `setting` 分支扩到四个键：`chat` / `invite` / `friendly-fire` / `apply`；其中 `apply` 与底层 `public-join` **相反**（`storeKey="public-join"`、`storeVal=!on`），回执文案「队伍聊天 / 队伍伤害 / 加入需要申请」。
- `teamList` 排序改成「需要申请的队伍优先」，再按人数；`/api/team` 的 `teamList(q, 30)` 放宽到 200（测试服 131 个队伍都能翻到）。
- 客户端：队伍卡片里三个 `.set-row` + `.switch`（`data-tset` / `data-tid` / `data-on`，非队长 `disabled`），放在动作网格上方；队长卡片多「加入申请 N 条」块（`同意`/`拒绝` → `data-decide`，`POST /api/team {action:'decide', team, arg, accept}`）；服务器队伍列表按 `publicJoin === false` 渲染「申请加入」+ `需申请` 标签。
- `.switch-row` / `.set-row .set-main` 补了竖排布局（标题 + 灰色说明各一行），否则标签和说明会挤在同一行。
- 实测：`apply:on` →「加入需要申请已打开」；TabCheck `join` →「「App队长队」需要队长批准」；`apply` →「已申请…」；队长读到 `applications=[{player:'TabCheck'}]`；`decide accept` →「已同意 TabCheck 加入「App队长队」（他现在不在线，上线后就在队里）」；成员变 `['AppTest','TabCheck']`。

## 三十一、26.11.31 队伍频道跟随开关 / 开关过渡 / 金额单位

- 队伍开关读取改成**先读盘**：`Hooks.teamSettingFromFile(teamId, key)` 直接解析 `plugins/LinuxTeam/teams.yml` 的 `teams.<id>.settings.<key>`（`YamlConfiguration`，按 `lastModified*31+length` 缓存），文件里没有这个键再退回 `getSettingBool`，所以队长在游戏里改设置、或直接改文件，App 都能立刻看到。
- `Hooks.teamSetting(teamId, team, key, def)` 统一入口；`teamRow` 的 `chatOn / publicJoin / friendlyFire / applyNeeded`、`teamList` 行的 `publicJoin`、`join` 分支的拦截全部走它。
- 客户端 `chatTeams()` 过滤 `chatOn !== false` 的队伍：`renderChatChannels()` 在所有队伍都关了聊天时把「队伍频道」页签整个 `.hidden`，当前频道自动回公屏并清空 `chatMsgs/chatLast/chatRenderedMax` 重新拉；`#chat-team-pick` 也只列开着聊天的队。
- 服务端本来就会拦：`ChatBus.postTeam` 返回 null → `/api/chat` 回「这个队伍的聊天被队长关掉了」（实测 400）。
- 开关动画（`style.css`）：`.switch` 背景 `transition: background .26s cubic-bezier(.32,.72,0,1)`，`::after` 滑块 `transform .26s` 同曲线，按下 `scale(.94)`。
- 金额单位 `fmtAmount(n)`：`K/M/B/T/Q` 五档，≥100 不保留小数、≥10 保留 1 位、否则 2 位并去掉尾零；用在「我的」金币/点券、转账弹窗余额、玩家详情金币/点券。`fmt()` 仍给人数等普通数字用。

## 三十二、26.11.32 全页提醒 / 聊天页只响不弹 / 弹窗避开灵动岛

- `showMsgPop(m)` 开头先做三件事：`alertSound()`、`alertVibrate()`、`pushSystemNotice(m)`（后者只在 `document.hidden` 且「软件外提醒」开着时排本地通知）；随后 `if (state.page === 'chat') return;` —— 聊天页只看消息流，不再盖卡片。
- 非聊天页的空闲轮询从「每 5 拍（10 秒）」改成「每 2 拍（4 秒）」，`notifyNewest(fresh)` 用 `state.notifiedMax` 水位，只对真新消息弹。
- `.msg-pop` 顶部改成 `top: calc(env(safe-area-inset-top, 0px) + 8px)`（配合 `index.html` 的 `viewport-fit=cover`），iPhone 灵动岛 / 刘海下不再遮挡；`.topbar` 早就在用 `--safe-t`。
- 实测：more 页注入消息 → `popCalls=1`、卡片 `class="msg-pop show"`、2 秒后自动收；chat 页注入消息 → 消息直接进列表、卡片全程没 `show`。

## 三十三、26.11.33 聊天自动滚到最新一条

- `scrollChatBottom()` 重写：先把 `#chat-list` 的 `scrollTop` 顶到底，再按「最后一条消息的文档坐标 + 14px −（窗口高度 − 输入条高度）」算窗口滚动目标，`requestAnimationFrame` + 60ms + 200ms 各补一次，等布局稳定后再定位。
- 来新消息：`loadChat()` 里去掉 `if (atBottom)` 的前置判断，`fresh.length` 就直接 `renderChat(); scrollChatBottom();`（原来页面一长 `isChatNearBottom()` 判 false，就再也不跟随）。
- 自己发消息：`sendChat()` 成功后 `loadChat(); setTimeout(scrollChatBottom, 260);`，不依赖 `state.chatScrollPending` 是否被消费。
- 结果：发送前后、别人来消息、切频道、重进聊天页都停在最新一条，且最后一条完整露在输入框上方。

## 三十四、26.11.34 直接拉到底部

- 按用户要求简化：`scrollChatBottom()` 不再计算「最后一条消息露在输入条上方」，直接 `window.scrollTo(0, document.body.scrollHeight - window.innerHeight)`，并同步 `#chat-list.scrollTop = scrollHeight`。
- 仍在 `requestAnimationFrame` / 60ms / 200ms 各补一次，等新气泡撑开布局后落到真正的底部。
- 触发点不变：`loadChat()` 有新消息、`sendChat()` 发送成功、进聊天页与切频道。

## 三十五、26.11.35 只有聊天视图滚到底

- 新增 `chatListVisible()`：`#page-chat` 可见、`state.chatChannel !== 'manage'`、`#chat-list` 未隐藏 三者同时成立才为真。
- `scrollChatBottom()` 开头与内层 `go()` 都先判断它，不成立直接返回 —— 「队伍」页（队长开关、申请、成员、服务器队伍列表都在下面）不会被拉到底，来消息也不再把你拽走。
- 「公屏」「队伍频道」保持 26.11.34 的行为：发消息 / 来消息 / 进聊天页 / 切频道都直接到底。

## 三十六、26.11.36 队伍页与滚动彻底解耦

- `scrollChatBottom()` 仍由 `chatListVisible()` 兜底：`#page-chat` 可见 + `state.chatChannel !== 'manage'` + `#chat-list` 未隐藏，三者同时成立才滚。
- 新增：`setChatChannel('manage')` 时 `backTop()`（`window.scrollTo(0, 0)`，0ms / 60ms / 240ms 各一次），切到「队伍」直接从页面顶部开始看，不被公屏的滚动位置带跑。
- 实测：公屏滚到底后点「队伍」→ `scrollY` 立即为 0；来新消息仍为 0（页面高 13456，完全不动）。

## 三十七、26.12.1 正式版 公屏上下线带时间

- `renderChat()` 的 notice 分支改成：`'<div class="chat-notice"><span>' + esc(m.text) + ' · ' + pad(HH) + ':' + pad(mm) + '</span></div>'`，时间取 `m.time`（服务端 `ChatBus.add` 记的毫秒时间戳）。
- 样式不动：`.chat-notice { display: flex; justify-content: center }` + 灰胶囊，继续居中，不占气泡位置。
- 版本号统一 26.12.1：`pom.xml`、`build.gradle`（versionCode 42 / versionName 26.12.1）、`project.pbxproj` 两处 `MARKETING_VERSION = 26.12.1`、`index.html` 三处（关于卡片写「26.12.1 正式版」）。
- 实测截图 `C:\Temp\jn3.png`：`TabCheck 上线了 · 01:20`、`TabCheck 下线了 · 01:42` 两条居中灰条。

## 三十八、26.12.2 登录页「测试连接」+ iOS 重新签名连不上的排查

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| 别人用全能签/个人证书签完，App 显示「无法连接服务器」，服务器是开的、原包也正常 | 重签名工具会重写 `Info.plist`，把 `NSAppTransportSecurity`（允许 http）删掉 → WKWebView 直接报 `Load failed` | 重签时不要勾「修改 Info.plist」；或先用「测试连接」看真实报错再判断 |

- `mobile/www/index.html` 登录页登录按钮下加「测试连接」`#btn-diag` 与输出区 `#diag-box`（`white-space: pre-line`）。
- `mobile/www/app.js` 新增 `diagServer()`：请求 `<地址>/api/health`，`AbortController` 8 秒超时；成功显示 `连接成功：HTTP 200，服务端版本 x.y.z` + 实际地址；失败按错误关键字给建议（ATS / Load failed / 超时）。
- `mobile/ios/App/App/Info.plist` 补 `NSLocalNetworkUsageDescription`（连局域网服务器时的权限说明）与 `NSBonjourServices = [_http._tcp]`。
- 实测：登录页输入默认地址点「测试连接」→「连接成功：HTTP 200，服务端版本 26.12.2 / 地址 http://aam.rainplay.cn:49282」。

## 三十九、iOS 12 兼容分支（mobile-ios12）

主线 `mobile/` 是 Capacitor 6（最低 iOS 13、arm64）。为了覆盖 iOS 12 设备，另开了一个分支工程 `mobile-ios12/`：

| 项 | 主线 | iOS 12 分支 |
| --- | --- | --- |
| Capacitor | 6.2 | 3.9.0 |
| 插件 | haptics 6 / local-notifications 6 / status-bar 6 | haptics 1.1.4 / local-notifications 1.1.0 / status-bar 1.0.8 |
| 部署目标 | iOS 13.0 | iOS 12.0（Podfile + pbxproj 四处） |
| 网页资源 | `mobile/www` | 同一个 `mobile/www`（`webDir: ../mobile/www`，不复制） |
| 产物 | `Nalxer-<版本>-unsigned.ipa` | `Nalxer-<版本>-ios12-unsigned.ipa` |

- 老系统兼容层放在共用前端里：`mobile/www/legacy.js` 探测 flex `gap` 支持，不支持就给 `<html>` 加 `no-flex-gap`；`mobile/www/legacy.css` 在该类下用外边距还原间距，并补 `-webkit-sticky`、`-webkit-overflow-scrolling`。
- `mobile/www/app.js` 是纯 ES5（无箭头函数 / 模板串 / `?.` / `??` / `async`），iOS 12 的 JavaScriptCore 可直接执行。
- Windows 上可以 `npm install`、`npx cap add ios`、`npx cap sync ios`；`pod install` 与 `xcodebuild` 必须 macOS，仓库里给了 `mobile-ios12/build-ios12.sh` 一键出包。
- 现状（2026-11-06）：**CI 已能出包**。`Nalxer-26.12.2-ios12-unsigned.ipa` 16,582,694 B md5 `e6b624db53c143ddb6d1e8d9c8a97d8f`，`MinimumOSVersion = 12.0`；主线 ipa 同时改成 CI 产物 732,845 B md5 `2afdc963dcb5b89acb1c09668f191a45`。

### iOS CI 为什么会一直 cancelled / 一直 queued

| 写法 | 结果 | 原因 |
| --- | --- | --- |
| `runs-on: macos-14` | 立刻 cancelled | 现在会被解析成 `macos-14-arm64-us-default`，普通账号不支持 arm64 机型 |
| `runs-on: macos-13` | 永远 queued | macos-13 已从 `actions/runner-images` 支持列表下线，等不到机器 |
| `runs-on: macos-15-intel` | 2~3 分钟 success | 当前标准的 x64 标签（`macos-15` 是 arm64） |

- `macos-15-intel` 是 Intel x64，Xcode 16 默认支持 iOS 12 部署目标，出 iOS 12 包够用。
- 想确认失败原因：`GET /repos/<owner>/<repo>/check-runs/<job_id>/annotations` 能看到真实报错，比看 `conclusion` 有用。
- `mobile-ios12/ios/App/Podfile` 的 `:path` 必须写正斜杠。Windows 上 `npx cap sync ios` 生成的是 `'..\..
ode_modules\@capacitor\haptics'`，到 macOS 上 `pod install` 直接失败（`No podspec found`）。

## 四十、26.12.3 新图标 + iOS 12 定位修复

| 改动 | 细节 |
| --- | --- |
| 应用图标 | `make_icons_261202.py` 重画：蓝→紫对角渐变（#56B0FF → #6D5CE7）+ 左上柔光 + 白色 N（Segoe UI Semibold，0.465）+ 右下薄荷点（#35D07F）；iOS 出 1024 不透明方图，安卓出 48~192 的方形/圆形/自适应前景（前景缩到 66% 安全区） |
| iOS 12 开屏错位 | `.splash` / `.modal` 原来只有 `inset: 0`，iOS 12 不认识 → 遮罩没有定位，跑到左上角；现在先写 `top/right/bottom/left: 0` 再写 `inset` |
| iOS 12 高度 | `100dvh`（iOS 15.4+）全部补 `100vh` 兜底：`.login-wrap`、`#app`、`#page-chat` |
| iOS 12 间距 | `legacy.css` 再补 `.splash-inner` / `.msg-pop` / `.modal-card` 的 flex gap 外边距；`.splash` / `.modal` 双保险再写宽高 |
| 版本号 | iOS 12 分支的 `Info.plist` 改成 `$(MARKETING_VERSION)`，pbxproj 补 `MARKETING_VERSION = 26.12.3` / `CURRENT_PROJECT_VERSION = 1`，CI 传参才会生效（之前写死 26.12.2，打出来的包一直显示旧版本） |

## 四十一、26.12.4 图标：白底蓝标

- 观感取自 iOS 12 包里那张 Capacitor 默认图（白底 + 淡网格 + 蓝色交叉标），但标记换成自己的 N：`make_icons_261204.py`。
- 规格：1024 主图（白底 `#FFFFFF` + `#EEF2F8` 对角网格 + 蓝色渐变 `#5CC8FA → #2E8BFF` 的 N + 右下蓝点），iOS 全套尺寸、安卓 48~192 三方（方/圆/自适应前景 66% 安全区）。
- iOS 12 分支的 `Assets.xcassets` 之前一直是 Capacitor 模板图标，这次一起覆盖，两个包图标统一。
- 版本号 26.12.4：`pom.xml`、`build.gradle`（versionCode 45）、两处 `MARKETING_VERSION`、`index.html` 三处。

## 四十二、26.12.5 图标：使用框架默认标

- 按用户要求，图标直接使用 Capacitor 框架默认那张（白底 `#FFFFFF` + 极淡对角网格 + 蓝色交叉标）。
- iOS：从仓库历史里取回框架模板的 18 张 PNG（`AppIcon-20x20@1x` … `AppIcon-512@2x`，逐字节一致）覆盖两个工程的 `Assets.xcassets/AppIcon.appiconset`。
- 安卓：由 `AppIcon-512@2x.png` 缩放生成 48~192 的方形 / 圆形 / 自适应前景；`values/ic_launcher_background.xml` 改成 `#FFFFFF`，前景缩进 66% 安全区。
- 版本号 26.12.5：`pom.xml`、`build.gradle`（versionCode 46）、两处 `MARKETING_VERSION`、`index.html` 三处。

## 四十三、26.12.6 协管权限 / 单设备登录 / 开屏图标 / iOS 12 输入条

| 事情 | 做法 |
| --- | --- |
| 单设备登录 | `Tokens` 令牌负载加 8 位随机编号（`name|admin|exp|nonce`），新增 `Sessions` 维护 `plugins/NalxerApp/sessions.txt`（`账号|令牌编号`）。`/api/login` 先查 `isBusy`，被占就 403 `session_taken`；带 `takeover=1` 可抢登，旧令牌下一次请求变 401 `session_kicked`；`/api/logout` 调 `sessions.end` 释放。 |
| 角色 | `roleOf()` 返回 `owner / su / admin / moderator / player`；`/api/login`、`/api/me`、`/api/admin/player` 带 `role`。协管来源：XlandCore `moderators` → 配置 `admin.moderators` → 权限 `nalxerapp.moderator`。 |
| 协管权限 | `/api/admin/*` 守卫从 `isAdmin` 换成 `canManage`；`admin()` 里 `modOnly` 只放行 status/players/staff/player/penalties/reports/bans + report + penalty；`penaltyAction` 对协管只允许 `banmsg`，其余 403。 |
| 客户端 | `state.role/canManage/moderatorOnly`，`#admin-card` 的显隐原来误用 `state.admin`（协管会看到空白管理页），改 `state.canManage`；隐藏「假人」「命令」子标签；惩罚类型对协管只列禁言。 |
| 开屏 / 登录图标 | `splash-mark` 与 `brand-mark` 由渐变文字改为 `<img src="icon-192.png">`（84×84 / 74×74，圆角 22 / 18）。 |
| iOS 12 输入条 | 安全区变量三层兜底（`0px` → `constant()` → `env()`）；`html.no-flex-gap .chat-input-bar` 用 `position: fixed` + `bottom: calc(var(--tab-h) + var(--safe-b) + 6px)`，并给 `#page-chat` 补 92px 底部内距。 |
| 抢登确认 | 登录失败 `code=session_taken` 时 `confirm()` 询问是否踢掉那台设备；同意后带 `takeover=1` 重登。注意 `String.fromCharCode(10)` 拼换行，别在 heredoc 里写 `
`。 |

实测：单设备 403→退出→再登 200→旧令牌 401；协管只读接口 200、linux/fakes/su 403、禁言 200、封禁 403。

## 四十四、26.12.7 协管权限收敛 + 30 天上限

| 事情 | 做法 |
| --- | --- |
| 协管管理页 | `#admin-card` 的显隐改绑 `state.canManage`（原来误用 `state.admin`，协管看到空白页）；`applyRole()` 隐藏 `[data-admin="fake"]` / `[data-admin="cmd"]`；顶部 `#mod-note` 只在 `state.moderatorOnly` 时显示。 |
| 角色刷新时机 | 关键 bug：`if (d.role) applyRole(d.role)` 只写在登录回调里，`loadMe()` 没有 → 冷启动拿不到角色。现在 `loadMe()` 也调；`boot()` 里被挡回「更多」的 `admin/cmd` 先记到 `state.pendingPage`，`applyRole` 之后再跳回去。 |
| 30 天上限 | 服务端 `ApiServer.MODERATOR_MAX_MINUTES = 30L*24L*60L`，`penaltyAction` 在 `apply` 且非管理员时校验 `minutes`，超出 403；客户端 `applyPenalty()` 先拦一次，`MODERATOR_MAX_MINUTES = 30*24*60`。 |
| 资源版本 | `index.html` 的 `?v=` 长期停在 102，导致改完 JS 仍走缓存（截图两次 sha 相同）。统一 `?v=116`，改完必须 `grep -o '?v=[0-9]*'` 复核。 |
| 探针 | `__probe_mod4.html?t=<token>`：写 localStorage 后载 `index.html?page=more`，把 `#tab-admin` 的 class/display、`#tabs`、`app.js?v=` 打到 `<pre>`，一眼看出是「没给角色」还是「用了旧 JS」。 |

实测：ModA 登录 `role=moderator`，管理页只有四个子标签且顶部有说明；`minutes=44640` → 403，`43200` → 200，`release` → 200，`type=ban` → 403。

## 四十五、26.12.8 管理页按真实权限下发

| 事情 | 做法 |
| --- | --- |
| 能力表 | `ApiServer.capsOf(name)` → `{view, report, mute, ban, fake, config, su}`，`/api/login` 与 `/api/me` 都返回 `caps`。 |
| 权限来源 | `Hooks.hasPerm(name, node)`：在线用 `Player.hasPermission() || isOp()`；离线走 LuckPerms 反射（`LuckPermsProvider.get()` → `getUserManager().getUser(uuid)` → `getCachedData().getPermissionData().checkPermission(node)`），没有 LuckPerms 返回 false。节点用 ITNOL 自己的：`inthenameoflinux.penalty` / `inthenameoflinux.ban`。 |
| 服务端拦截 | `penaltyAction` 不再看角色，改判 `caps.mute` / `caps.ban`：`banmsg` 要 mute，其它类型要 ban；文案「你没有封禁权限（需要 inthenameoflinux.ban），这个处罚做不了」。 |
| 客户端 | `applyRole(role, caps)`：`canManage = caps.view`；假人/命令/惩罚页签分别看 `caps.fake` / `caps.su` / `caps.mute||caps.ban`；`#mod-note` 与页面副标题按能力拼；玩家详情的惩罚类型按 caps 列出，全无权限显示「这里只能看」。 |
| 协管页签不出现 | 根因是角色只在登录回调算一次；`loadMe()` 里补 `if (d.role) applyRole(d.role, d.caps)`，并给 `boot()` 挡回去的 admin/cmd 记 `state.pendingPage`，认出来后再切回。 |
| 资源版本 | `index.html` 的 `?v=` 从 102 统一到 118（之前几次升版本是空转，浏览器一直吃旧 app.js）。 |

实测：ModA 只有 view/report；禁言 403「你没有处罚权限（需要 inthenameoflinux.penalty）」；AppTest 管理员全通、config 仍 false。

## 四十六、26.12.9 协管的封禁/惩罚 + 30 天上限

- 协管在服务器上本来就带封禁与惩罚权限，`capsOf()` 里 `mute` / `ban` 对 moderator 直接放行；
  `fake` / `config` / `su` 仍为 false（假人、插件配置、su 控制台不显示也不可用）。
- 非管理员（协管）的处罚时长：必须 `1 ~ 43200` 分钟。`0`（永久）与 `>43200` 都由 `ApiServer.penaltyAction` 直接 403，
  客户端 `applyPenalty()` 先 toast 拦一次；管理员不受限。
- 客户端页签、副标题、玩家详情里的处罚类型全部由 `state.caps` 推导，不再按角色硬编码。

## 四十七、26.12.10 「管理权限」显示修正

- `#set-admin` 过去有三个写点（`applyRole` 写角色名、`enterApp` 写「有/否」），互相覆盖。
- 现在只有 `paintAdminLabel()` 一处：`!canManage → 无`，`moderatorOnly → 有（协管）`，否则 `有`。
- `index.html` 的默认值也改成「无」，避免首屏闪一下「否」。
- `gh_push_tree.py` / `gh_push_ios12.py` 增加 `f.startswith('__')` 过滤，调试页不再进仓库与安装包。

## 四十七、26.12.11 登录限流 / 单设备不可抢登 / 性能与安全审计

- 登录限流（服务端）：同 IP 60 秒 5 次 → 冷却 30 秒（`login_cooldown`）；同账号连续失败 10 次 → 锁 5 分钟（`login_locked`）；失败第 5 次起响应带 `remaining`，客户端拼「还能再试 N 次」；成功带 `welcome`。
- 单设备：`/api/login` 遇到 `isBusy` 直接 403 `session_taken`，不再接受 `takeover`；释放只有两条路 —— 原设备 `/api/logout`，或控制台 `/nalxerapp logout <玩家名>`（`NalxerApp.onCommand` → `sessions.end`）。
- FakePlayerPlugin：`FakePlayerConnection.sendPacket` 判空 + try/catch，修 `EntityTrackerEntry.updatePlayer` 的空指针崩服。
- InTheNameOfLinux：`BanManager.flush()` 快照 + 异步写（序号防回退），`flushSync()` 供关服。
- NalxerApp 审计：`resolveName` 离线索引 TTL 10 分钟 + 负缓存、`hasPerm` 60 秒缓存、`isAdminName` 单次解析、su 拦截 `reload/rl/op/deop`。

## 四十八、26.13.0 队伍领地共享 + 插件改名 LinuxAPP

- **领地共享**：`LandShare.java` 不调 Residence 的 Java API（会 `NoClassDefFoundError`：WorldGuard/Kingdoms 不在服务器上）。
  读：`resadmin list <玩家>` + 捕获型 `CommandSender` 抓回显（内存实时数据），失败退回 `Save/Worlds/res_*.yml` 的 `OwnerLastKnownName`。
  写：`resadmin pset <res> <player> <flag> true|remove`。
- 开关存 `plugins/LinuxAPP/team-land.txt`（`teamId|enabled|epoch`，`TeamLand.java`）；
  必须先开「队伍申请」（`public-join=false`）才能开，服务端 403 + 客户端灰按钮双保险。
- 17 个 flag 列表在 `LandShare.FLAGS`；入队 / 退队 / 被踢 / 审批通过时自动 `teamLandResync` / `teamLandRevoke`。
- **改名**：`plugin.yml` name=LinuxAPP、命令 `/linuxapp`（alias `/nalxerapp`）、权限 `linuxapp.admin`（兼容旧名）；
  `NalxerApp.migrateDataFolder()` 首启把 `plugins/NalxerApp/**` 拷到 `plugins/LinuxAPP/`。
- 26.13.0 产物：LinuxAPP.jar 172,246 B `46559a168f4455a4b78687fe1a4ac0d4`；APK 3,338,863 B `421aa8ea…`（versionCode 53）。

## 四十九、26.13.1 多服务器（Beta）+ 安卓后台通知

- `LS.servers`：`[{name,url,token,player,role}]` 最多 5 槽；`LS.serverIdx` 当前槽，`LS.multi` 开关。
- `switchServer(i)` 会清空 `chatMsgs/chatLast/chatRenderedMax/notifiedMax/myTeams/reports` 再按新服务器重拉，避免串数据。
- 老用户打开开关时，`boot()` 会把 `LS.token`（若与槽 url 相同）迁移进第 1 槽，避免看起来被登出。
- 安卓：`NotifyService`（前台服务，`dataSync`，10s 轮询 `/api/chat`）+ `NotifyPlugin`（`BackgroundNotify.start/stop`），
  `MainActivity.onCreate` 里 `registerPlugin`；`app.js` 在 `visibilitychange` 时启停。

## 五十、26.13.2 软件改名 LinuxAPP

- `mobile/www/index.html`：`<title>`、`apple-mobile-web-app-title`、开屏 `#splash` 的 `LinuxAPP` 文案与 `alt`、登录页 `<h1>`、关于卡片三处。
- `mobile/www/app.js`：文件头注释、`PAGE_TITLE` 兜底名、su 控制台标题、未接上插件时的提示、`/api/health` 兜底显示名。
- `mobile/android/.../res/values/strings.xml`：`app_name` / `title_activity_main` → LinuxAPP。
- `mobile/ios/App/App/Info.plist` 与 `mobile-ios12/.../Info.plist`：`CFBundleDisplayName` → LinuxAPP。
- `mobile/capacitor.config.json` 的 `appName` → LinuxAPP（`cap copy` 会同步进 android/ios 的 assets）。
- **包名 `top.nalxer.app` 保持不变**：改包名会让覆盖安装变成两个 App、登录态与本地配置全丢。

## 五十一、26.13.3 登录过期自动释放账号

- `Sessions.java` 的记录从 `账号|<令牌编号>` 变成 `账号|<编号>|<过期毫秒>`；`begin(name, id, expiresAt)`。
- `isBusy()` 遇到已过期记录会就地删除并返回 false（下一个设备因此能直接登录）；
  `matches()` 遇到过期记录同样清掉并返回 false（旧设备下次请求 → 401）。
- `matches()` 的「服务端没有记录就放行」分支已删掉：没有记录 = 视为失效，强制重新登录，
  否则「过期被清掉」会被当成「老版本升级」而继续放行。
- 老格式（无过期时间）继续按占用处理，升级不需手动清 `sessions.txt`。
- 客户端 `api()` 的 401 分支把服务端 `message` 存进 `state.kicked`，`logout(true)` 后
  在登录页 `#login-err` 显示出来（「登录已过期，请重新登录」/「这个账号已在另一台设备登录」）。

## 五十二、26.13.4 HTTPS 强制 + 端到端附加加密 + AuthMe 保护

### 1. 只允许 HTTPS

- `http.tls` 默认 `true`。第一次启动会用 JDK 自带 `keytool` 自动生成自签证书
  `plugins/LinuxAPP/keystore.p12`，同时导出 `server.crt`。
- 没有证书、且 `security.allow-plaintext: false`（默认）时，接口**不会启动**，控制台会打印原因。
  也就是说默认状态下不存在明文 HTTP 入口。
- 协议只保留 TLS 1.2 / 1.3，套件只保留 AEAD（AES-GCM / ChaCha20）与前向保密套件。
- 所有响应加 `Strict-Transport-Security`、`X-Content-Type-Options: nosniff`、
  `X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、`Cache-Control: no-store`。

### 2. App 与插件之间的第二层加密（AES-256-GCM）

TLS 之外，App 每次启动会：

1. `GET /api/session` 取服务器证书里的 RSA 公钥；
2. 本地生成 32 字节 AES 密钥，用 RSA-OAEP(SHA-256) 包起来 `POST /api/session`；
3. 之后所有请求体用 AES-GCM 加密（`X-Enc: 1` + `X-Session`），响应也是密文。

中间即使挂了会解密 TLS 的代理、或有人抓包，看到的也只是密文。
`security.require-encryption: true`（默认）时，任何没走加密通道的 `/api/*` 直接 426。

**说明**：聊天内容最终要显示在 Minecraft 游戏里，所以服务器进程必然要解密——
这一点无法做到「服务器也看不见」的真正端到端；这里做的是**传输双层加密**
（TLS + 应用层），比单纯 HTTPS 多一层，能挡住 TLS 终结代理与抓包。

### 3. AuthMe 保护

- 登录仍然只走 AuthMe：`AuthMeApi.checkPassword`，插件从不读取、不缓存、不传输密码哈希。
- 启动时检查 `plugins/AuthMe/authme.db` 的文件权限，若 group/other 可读会警告：
  `AuthMe 数据库权限过宽：... —— 建议 chmod 600，并把 plugins/AuthMe 目录 chmod 700`。
- 日志默认不记录请求体（`security.log-bodies: false`），密码不会进日志。
- 插件只有「读自己 plugins/LinuxAPP 下的文件」的路径，不提供任意文件读取接口。

### 4. 手机怎么信任自签证书

- 安卓：把 `server.crt` 传到手机 → 设置 → 安全 → 加密与凭据 → 安装证书 → CA 证书；
  App 的 `network_security_config` 已允许用户 CA，且 `usesCleartextTraffic=false`。
- iOS：把 `server.crt` 用 AirDrop/邮件发到手机 → 安装描述文件 → 设置 → 通用 → 关于本机 →
  证书信任设置 → 打开该证书的完全信任。
- 有域名和正式证书（Let's Encrypt 等）最省事：转成 PKCS12 覆盖 `keystore.p12` 即可，手机不用装东西。
- `http.cert-san` 要写客户端实际连接的地址，例如 `IP:1.2.3.4` 或 `DNS:mc.example.com`，
  否则浏览器/WebView 会报证书名称不匹配。
