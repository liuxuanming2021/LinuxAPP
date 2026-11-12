# LinuxAPP

LinuxAPP 是给 **Minecraft 服务器** 用的一套「手机 App + 服务端插件」：

- **插件**（`LinuxAPP.jar`）：在服务器上开一个 HTTP 接口，把在线玩家、聊天、队伍、头衔、经济、处罚这些数据交给 App。
- **App**（Android / iOS）：玩家在手机上登录后，看自己的金币点券、和全服聊天、进队伍、看排行、管理员还能在手机上处理举报和处罚。

> 这一版是**通用版**：只强制依赖 **AuthMe**，其它插件全是可选。服务器只装 AuthMe + LinuxAPP 也能正常启动、登录、聊天、禁言、封禁、举报；装了别的插件，对应功能自动出现，没装就不显示，不会出现「点了没反应」。

---

## 一、插件

### 1.1 必须的依赖

| 插件 | 说明 |
| --- | --- |
| **AuthMe**（AuthMeReloaded / AuthMeReReloaded） | **必装**。App 登录用的就是玩家在游戏里的 AuthMe 密码。没装 AuthMe，插件不会启用。 |

### 1.2 可选的联动（装了就自动接上，不装就隐藏对应功能）

| 插件 | 接上之后多出来的功能 |
| --- | --- |
| **InTheNameOfLinux**（ITNOL） | 完整处罚（禁言 / 禁移动 / 禁破坏 / 全部 / 封禁）、举报工单、保护对象、二次身份验证。没装时改用内置的本地禁言/封禁/举报。 |
| **LinuxTeam** | 队伍：建队、邀请、审批、队伍频道、领地共享。 |
| **LinuxTitle** | 头衔。 |
| **NalxerECO** | 金币 / 点券余额与转账。 |
| **Vault** + 经济插件 | 只读的金币余额（没有 NalxerECO 时的兜底）。 |
| **PlayerPoints** | 点券余额（没有 NalxerECO 时的兜底）。 |
| **XlandCore** | 服主 / 副服主 / 协管名单，管理页的「管理层」列表。 |
| **Residence** | 队伍领地共享（`resadmin pset`）。 |
| **FakePlayerPlugin** | 管理页的「假人」标签。 |
| **NalxerLink** | 它广播的 `[App] 名字: 内容` 会进 App 公屏，标「FS1安卓端」。 |

### 1.3 安装

1. 把 `LinuxAPP.jar` 丢进服务器的 `plugins/` 目录（AuthMe 也要在）。
2. 重启服务器。控制台会看到：

   ```
   [LinuxAPP] Enabling LinuxAPP vX.Y.Z
   [LinuxAPP] [联动] 已接上 AuthMe —— 用游戏密码登录
   [LinuxAPP] [联动] 没装 InTheNameOfLinux —— 禁言/封禁/举报改用本地实现
   [LinuxAPP] 接口已启动：http://0.0.0.0:49282/api/health
   [LinuxAPP] Done
   ```

3. 开放端口 **49282/TCP**（或者改 `plugins/LinuxAPP/config.yml` 里的 `http.port`，App 里填的地址要跟它一致）。
4. 把服务器 IP 告诉玩家，App 里「服务器接口」填 `http://你的IP:49282`。

> 升级旧版（`NalxerApp.jar`）时：把旧 jar 删掉，放 `LinuxAPP.jar`。首次启动会把 `plugins/NalxerApp/` 的数据（登录会话、队伍申请、领地共享开关等）自动迁移到 `plugins/LinuxAPP/`。

### 1.4 常用命令

| 命令 | 作用 |
| --- | --- |
| `/linuxapp status` | 看接口状态、哪些联动接上了 |
| `/linuxapp reload` | 重载 `config.yml` |
| `/linuxapp token <玩家>` | 给某个玩家签发一个登录令牌（调试用） |
| `/linuxapp logout <玩家>` | **强制某个账号退出登录**（账号卡在别的设备上时用这个） |

`/nalxerapp` 是旧名字，仍然可用。

### 1.5 关键配置（`plugins/LinuxAPP/config.yml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `http.port` | `49282` | App 连的端口 |
| `http.host` | `0.0.0.0` | 监听地址 |
| `login.require-authme` | `true` | 用 AuthMe 密码校验；设 `false` 只允许「此刻在游戏里在线」的玩家登录（没装 AuthMe 的正版服可用） |
| `admin.players` | `[Linux582021]` | 哪些账号算管理员 |
| `su.players` | `[Linux582021, fs1]` | 谁能用 su 控制台 / 插件配置 |
| `token.ttl-hours` | `168` | 登录令牌有效期（小时），过期后自动释放账号 |
| `chat.capture-join-quit` | `true` | 公屏显示「X 上线了 / X 下线了」 |
| `chat.capture-link` | `true` | 抓 NalxerLink 的 `[App]` 消息 |
| `moderation.mute-message` | 见文件 | 本地禁言时给玩家看的提示 |
| `team.*` | 见文件 | 队伍申请、领地共享相关 |

### 1.6 权限

| 权限 | 作用 |
| --- | --- |
| `linuxapp.admin` | 使用 `/linuxapp` 管理命令 |
| `nalxerapp.admin` | 旧权限名，保留兼容 |
| `inthenameoflinux.penalty` | 装了 ITNOL 时，拥有它的人能在 App 里禁言 |
| `inthenameoflinux.ban` | 装了 ITNOL 时，拥有它的人能在 App 里封禁 |

App 里的按钮是**按权限实时算的**（服务端返回 `caps`），不是按角色猜：没有 `ban` 权限就看不到封禁入口；协管的单次处罚最长 30 天，且不能永久。

---

## 二、App

### 2.1 安装

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Android | `LinuxAPP-<版本>.apk` | 直接安装（需要允许「安装未知来源应用」）。 |
| iOS（iOS 13+） | `LinuxAPP-<版本>-unsigned.ipa` | 未签名，需要自己用爱思 / 全能签 / AltStore 等签名后安装。 |
| iOS（iOS 12） | `LinuxAPP-<版本>-ios12-unsigned.ipa` | 老系统专用，界面一样。 |

> iOS 签名工具如果会「修改 Info.plist」，请**不要勾**那个选项——它会把允许 http 的 ATS 配置删掉，装完就连不上服务器。

### 2.2 第一次使用

1. 打开 App，点「服务器接口」，填 `http://你的服务器IP:49282`（也可以直接改默认值再保存）。
2. 填「游戏 ID」和「密码」——就是你在游戏里 `/login` 用的那个密码。
3. 点「登录」。登录成功后默认进「我的」页。
4. 连不上时点登录页下面的「**测试连接**」，它会直接告诉你卡在哪一步（端口不通 / ATS 被拦 / 超时）。

### 2.3 功能

| 页面 | 能做什么 |
| --- | --- |
| **我的** | 头像、身份标签、金币 / 点券 / 等级、经验进度、每日签到、举报玩家、我的举报记录 |
| **社交** | 公屏 / 队伍频道 / 队伍 三个标签；发消息、转账（金币、点券）、队伍聊天开关；「队伍」标签里建队、邀请、审批、踢人、转让、解散、领地共享 |
| **头衔** | 看自己的头衔、切换头衔（装了 LinuxTitle 才有） |
| **管理** | 在线玩家、玩家详情、举报工单、惩罚、假人（按权限显示，协管只看得到能用的） |
| **更多** | 深色模式、软件内 / 软件外提醒、主题（默认 / Miuix / iOS）、多服务器（Beta）、接口地址、关于 |

### 2.4 几个要说明的行为

- **一个账号只能在一台设备上登录。** 在第二台设备登录会被拒绝，必须先在原设备点「退出登录」，或者让管理员在控制台执行 `/linuxapp logout <名字>`。令牌过期（默认 7 天）后会自动释放，不会一直占着。
- **软件内提醒**：收到别人消息时顶部弹一条（2 秒），带提示音和振动。聊天页里只响不弹，避免挡消息。
- **软件外提醒**：App 退到后台时，安卓会起一个前台服务继续收消息并发系统通知；iOS 用本地通知（需要允许通知权限）。
- **多服务器（Beta）**：设置里打开后，「软件链接配置」可以填最多 5 台服务器（名称 + 地址），保存时会逐台测连通性，连不上的不会保存。配好之后主页顶部会出现切换按钮，切过去会重新拉那台服务器的所有数据，两台服务器的数据互不影响。
- **主题**：默认 / Miuix / iOS 三种风格，六种强调色（天蓝、淡粉、淡绿、古董白、黑、灰），另可开关底部悬浮导航。

### 2.5 没有装可选插件时，App 里会怎样

| 情况 | 表现 |
| --- | --- |
| 没装 LinuxTeam | 社交页没有「队伍」标签 |
| 没装 LinuxTitle | 底栏没有「头衔」 |
| 没装 NalxerECO / Vault | 金币显示「—」，转账页提示不可用 |
| 没装 ITNOL | 处罚只有禁言 / 封禁（本地实现），没有禁移动 / 禁破坏 / 全部 |
| 没装 Residence | 队伍页的「领地共享」开关提示不可用 |
| 没装 FakePlayerPlugin | 管理页没有「假人」标签 |
| 不是管理员 / 协管 | 底栏没有「管理」 |

---

## 三、自己编译

### 3.1 插件

```bash
cd plugin            # 本仓库根目录，pom.xml 在这里
mvn -DskipTests clean package
# 产物：target/LinuxAPP.jar（pom 里 finalName 配的是这个）
```

需要 JDK 8 或更高（服务端 1.12.2 用 Java 8 跑）。

### 3.2 Android

```bash
cd app/mobile
npm install
npx cap copy android
cd android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 3.3 iOS

```bash
cd app/mobile
npm install
npx cap copy ios
# 然后用 Xcode 打开 app/mobile/ios/App/App.xcworkspace 打包
```

iOS 12 老系统版在 `app/mobile-ios12`（Capacitor 3，`platform :ios, '12.0'`），目录结构与上面一致。仓库里的 GitHub Actions（`.github/workflows/app-build.yml`）会在 push `app/mobile/**` 时自动打 Android 与两个 iOS 包。

---

## 四、常见问题

**Q：App 一直显示「连不上服务器」？**
A：① 先点「测试连接」看具体报错；② 用手机浏览器打开 `http://服务器IP:49282/api/health`，能看到 JSON 就说明端口通；③ 看服务器防火墙 / 安全组有没有放行 49282；④ iOS 如果是重签名装上的，检查签名工具有没有改 Info.plist（会丢 ATS 配置）。

**Q：登录提示「这个账号已经在另一台设备上登录了」？**
A：在原设备点「退出登录」，或管理员执行 `/linuxapp logout <名字>`。令牌过期（默认 7 天）后也会自动释放。

**Q：登录提示「密码错误」但游戏里能进？**
A：App 用的是 AuthMe 的密码。如果服务器用的是别的登录插件（没有 AuthMe），把 `login.require-authme` 设成 `false`，改成「在线才能登录」。

**Q：管理页什么都没有？**
A：管理页只对管理员 / 协管开放；另外每个按钮都按真实权限显示，没有 `inthenameoflinux.ban` 之类权限的人看不到对应处罚。

**Q：换服务器 / 换端口之后 App 连不上？**
A：在「更多 → 接口地址」里把地址改成新的（多服务器模式下改「软件链接配置」），保存后再登录。

---

## 五、目录结构

```
.
├── pom.xml                 # 插件工程
├── src/                    # 插件源码
├── libs/                   # 编译期可选依赖（ITNOL / Residence，仅编译用）
├── app/
│   ├── mobile/             # App 前端 + Android + iOS 13+ 工程（Capacitor 5）
│   └── mobile-ios12/       # iOS 12 专用工程（Capacitor 3）
└── .github/workflows/      # 自动打包
```

## 六、说明

- 插件与 App 之间的接口是明文 HTTP（默认 49282），没有走 HTTPS；如果服务器前面有 Nginx，也可以自己套一层。
- 本仓库是通用版；带完整 ITNOL 联动的私有版仍在原来的仓库里维护。
