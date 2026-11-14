# LinuxAPP —— 服务端接口 + 手机客户端

LinuxAPP 是这套服务器的**手机端**：一个 Paper 1.12.2 插件（自带 HTTP/HTTPS 接口），加两套客户端。
用**游戏 ID + 游戏密码**（AuthMe）登录，进去就能看自己的金币、点券、等级、经验、在线时长、
头衔、队伍、聊天、签到、举报；有权限的人还能管玩家、处理举报、处罚、管假人、改插件配置。

```
手机 App ──HTTPS :49283 / HTTP :49282──> LinuxAPP 插件（自带 HttpServer / HttpsServer）
                                              │
                        AuthMe / Vault / PlayerPoints / InTheNameOfLinux
                        LinuxTeam / LinuxTitle / LinuxACE / FakePlayerPlugin / XlandCore / NalxerLink
```

| 端 | 技术 | 说明 |
| --- | --- | --- |
| **iOS 13 及以上** | 原生 SwiftUI（`app/mobile-ios-native`） | 系统原生控件、系统 TabView/NavigationView、系统通知与提示音 |
| **安卓 / iOS 12** | 网页版（Capacitor 壳 + `app/mobile/www`） | 同一份 HTML/CSS/JS，装进安卓 APK 与 iOS 12 的 ipa |

两套客户端连的是同一套接口、同一份权限，功能对齐。

---

## 一、装插件

把 `LinuxAPP.jar` 丢进 `plugins/`，重启服务器即可（**只强制依赖 AuthMe**，其余插件缺了会自动降级或关掉对应功能）。

```
plugins/LinuxAPP/config.yml       # 配置
plugins/LinuxAPP/stats.yml        # 玩家数据（离线也能读）
plugins/LinuxAPP/audit.log        # 管理操作审计（谁封了谁、谁跑了什么命令）
plugins/LinuxAPP/certs/           # 想开 HTTPS 就把 p12 放这里（可选）
plugins/LinuxAPP/chat.json        # 聊天缓存（网页版读它）
```

启动日志应该能看到：

```
[LinuxAPP] Enabling LinuxAPP v 26.13.55
[LinuxAPP] [联动] 已接上 AuthMe —— 用游戏密码登录
[LinuxAPP] [联动] 已接上 Vault 经济 —— 金币数据
[LinuxAPP] [联动] 已接上 PlayerPoints —— 点券数据
[LinuxAPP] [联动] 已接上 InTheNameOfLinux —— 封禁 / 解封
[LinuxAPP] [联动] 已接上 LinuxTeam 队伍（信息 / 建队 / 加入 / 退出 / 踢人 / 转让）
[LinuxAPP] [联动] 已接上 LinuxTitle 头衔（列表 / 佩戴）
[LinuxAPP] [联动] 已接上 XlandCore 身份（服主 / 副服主 / 市长 / 协管）
[LinuxAPP] 接口已启动：http://0.0.0.0:49282/api/health
```

浏览器打开 `http://服务器IP:49282/api/health`，能返回 JSON 就算通了。

## 二、开 HTTPS（推荐）

不挂 Nginx，插件自己就能听 HTTPS：把证书导出成 **PKCS#12（.p12 / .pfx）** 放进 `plugins/LinuxAPP/certs/`，
密码写进 `config.yml` 的 `http.tls-keystore-password`，重启后会看到：

```
[LinuxAPP] HTTPS 已启动：https://0.0.0.0:49283
```

客户端默认地址就是 `https://api.nalxer.top:49283`，改地址在「更多 → 接口地址」。
证书换新的不用重启：插件每 30 秒看一次 p12 的修改时间，变了就重建 HTTPS 服务。

## 三、配置（`config.yml`）

只列常用的，全量说明见 [官网帮助页](https://linux.nalxer.top/nalxerapphelp.html)。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `http.host` / `http.port` | `0.0.0.0` / `49282` | HTTP 监听。公网直连就保持 `0.0.0.0` |
| `http.tls-port` | `49283` | HTTPS 端口，`0` = 不开 |
| `http.threads` | `8` | 接口线程数 |
| `login.require-authme` | `true` | 一律用 AuthMe 校验密码，不另设密码 |
| `login.attempts-per-minute` | `5` | 每个 IP 每分钟登录尝试上限（防撞库） |
| `login.requests-per-minute` | `180` | 每个 IP 每分钟总请求上限 |
| `sessions.single-device` | `true` | 一个账号同时只允许一台设备，第二台 403 `session_taken` |
| `security.strict-encryption` | `false` | 打开后除登录/握手外都要密文会话（426 拦截） |
| `security.allow-plaintext-fallback` | `true` | 允许客户端带 `X-No-Enc: 1` 走明文（默认开着，方便排查） |
| `admin.players` | `[]` | 管理员名单（游戏 ID），服务器 OP 自动算管理员 |
| `su.players` | `[Linux582021]` | 能用命令控制台与 `su` 的账号 |
| `data.*` | 全 `true` | 金币 / 点券 / 等级 / 在线时长 / 队伍 / 头衔 要不要对外提供 |
| `features.*` | 全 `true` | 聊天 / 队伍 / 头衔 / 签到 / 举报 / 处罚 / 假人 / 控制台 开关 |
| `chat.capture-link` | `true` | 抓 `logs/latest.log` 里 `[App] 名字: 内容` 的广播，标「FS1安卓端」 |

## 四、接口

免加密：`/api/health`、`/api/session`、`/api/login`、`/api/logout`、`/api/ping`。
其余接口在 `security.strict-encryption` 下需要会话密钥；返回 `409 session_expired` 时客户端重新握手即可。

| 分组 | 接口 |
| --- | --- |
| 账号 | `GET /api/health`、`POST /api/session`、`POST /api/login`、`POST /api/logout`、`GET /api/me`、`POST /api/ping` |
| 社交 | `GET\|POST /api/chat`、`GET /api/online`、`GET\|POST /api/team`、`GET\|POST /api/titles`、`GET\|POST /api/transfer` |
| 玩家 | `GET\|POST /api/checkin`、`GET\|POST /api/report`、`GET /api/reports` |
| 管理 | `/api/admin/status`、`staff`、`players`、`player`、`search`、`penalties`、`penalty`、`ban`、`unban`、`reports`、`report`、`fakes`、`fake`、`exec`、`su`、`commands`、`linux`、`linux/config`、`linux/op` |

### 加密握手

1. 客户端生成 RSA-2048 密钥对，把公钥（X.509、Base64）POST 给 `/api/session`；
2. 服务端用 `RSA/ECB/OAEPPadding` + OAEP-SHA256 加密 32 字节 AES 密钥回传；
3. 之后请求体用 `AES/GCM/NoPadding`（前 12 字节 IV）加密，带 `X-Session` 与 `X-Enc: 1`；响应同样加密。

### 权限

登录返回 `caps`：`view / report / mute / ban / fake / config / su`。客户端只按这些显示页签与按钮，
服务端每次请求还会再查一遍 —— 界面藏起来不算数。

## 五、客户端构建

### 网页版（安卓 + iOS 12）

```bash
cd app/mobile
npm install
npx cap copy android && npx cap copy ios           # 改完 www/ 必须重新 copy，否则包里还是旧页面
cd android && ./gradlew assembleRelease            # 产物 app/build/outputs/apk/release/app-release.apk
```

iOS 12 的 Xcode 工程在 `app/mobile-ios12`，`deployment_target = 12.0`。

### 原生 iOS（iOS 13+）

```bash
cd app/mobile-ios-native
brew install xcodegen
xcodegen generate
xcodebuild -project LinuxAPP.xcodeproj -scheme LinuxAPP -configuration Release \
           -sdk iphoneos -destination 'generic/platform=iOS' \
           CODE_SIGNING_ALLOWED=NO build
```

CI（`.github/workflows/app-build.yml`）会跑四个 job：`android`、`ios`、`ios12`、`ios-native`，
产物分别上传，`ios-native` 还会跑一遍 ad-hoc 自签。

### 版本号要一起改

`app/mobile-ios-native/project.yml`、`app/mobile/android/app/build.gradle`（versionCode 递增）、
`app/mobile-ios12` 与 `app/mobile/ios` 的 `project.pbxproj`（各两处 `MARKETING_VERSION`）、
`app/mobile/www/index.html`（三处版本串 + 资源 `?v=NN`）、`pom.xml`。

## 六、常用命令

| 命令 | 说明 |
| --- | --- |
| `/linuxapp` | 插件状态（接口地址、联动情况） |
| `/linuxapp logout <名字>` | 踢掉某账号的 App 会话（换设备登录用） |
| `/linuxapp entities` | 看假人 / 掉落物 / 船这些实体的数量 |
| `/linuxapp purge arrows` / `items` | 清掉落物或箭（卡服时救命） |

## 七、常见问题

- **手机连不上**：先在服务器本机 `curl http://127.0.0.1:49282/api/health`；不通说明插件没起或端口被占。
- **提示 `session_taken`**：这个号在别处登录着，控制台 `/linuxapp logout <名字>`，或者在另一台设备退出。
- **退出后还显示已登录**：老版本 WebUI 的退出请求没走加密被 426 挡了，26.13.51 已修（先走加密通道，失败再补明文）。
- **无敌开关关不掉**：26.13.55 修了（以前写字符串 `"false"`，Bukkit `getBoolean` 读不到，一直按 `true`）。
- **假人重生报 NPE**：26.13.54 修了（召唤/重生/移除全部放主线程执行）。
- **控制台输出不全**：26.13.51 把上限提到 250 行 / 24000 字符，并且 su 会连着读多段日志。

## 八、文档

- 官网：<https://linux.nalxer.top/>
- 使用帮助（含配置、接口、排错）：<https://linux.nalxer.top/nalxerapphelp.html>
- 插件帮助总入口：<https://linux.nalxer.top/linuxhelp.html>
