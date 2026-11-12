# Nalxer App · iOS 12 兼容分支

主工程 `mobile/` 用的是 Capacitor 6，最低 iOS 13、只有 arm64。
这份分支用 **Capacitor 3.9 + 插件 1.x**，部署目标 **12.0**，网页资源直接复用 `../mobile/www`（不复制、不维护第二套前端）。

## 目录

```
mobile-ios12/
├── package.json          Capacitor 3.9 + haptics 1.1.4 / local-notifications 1.1.0 / status-bar 1.0.8
├── capacitor.config.json webDir 指向 ../mobile/www
├── build-ios12.sh        macOS 上一键出未签名 ipa
└── ios/App/              由 `npx cap add ios` 生成（已提交），Podfile 已写死 platform :ios, '12.0'
```

## 在 macOS 上出包

```bash
cd mobile-ios12
bash build-ios12.sh 26.12.x
```

产物：`E:\Plugins\Outlay\Nalxer-<版本>-ios12-unsigned.ipa`。

## 本机（Windows）能做的

- `npm install` / `npx cap add ios` / `npx cap sync ios` / `npx cap copy ios` 都能跑（不需要 Xcode）；
- `pod install` 和 `xcodebuild` 必须 macOS，Windows 上 `cap sync` 会提示跳过 pod install，这是正常的。

## 兼容处理

- 部署目标 12.0：`ios/App/Podfile`（`platform :ios, '12.0'`）与 `project.pbxproj` 里 4 处 `IPHONEOS_DEPLOYMENT_TARGET = 12.0`。
- 网页侧加了一层老系统兼容（在主工程 `mobile/www` 里，两个工程共用）：
  - `legacy.js`：探测老 WebKit 的 flex 不支持 `gap`，命中就给 `<html>` 加 `no-flex-gap`；
  - `legacy.css`：在 `no-flex-gap` 下用外边距还原间距，并补 `position: -webkit-sticky`、`-webkit-overflow-scrolling`。
- `app.js` 本来就是纯 ES5（无箭头函数、模板字符串、`?.`、`??`、`async/await`），iOS 12 的 JavaScriptCore 可以直接跑。

## 已知限制

- iOS 12 只可能跑在 arm64 设备上（32 位设备最高 iOS 10，而 iOS 10 需要 Capacitor 2 / iOS 11+，属于另一档工作）。
- 老系统的毛玻璃 `backdrop-filter` 会退化成半透明底（`legacy.css` 里已兜底），视觉上比新系统略平，但布局与功能一致。
- 这个分支的 ipa 目前还**没有构建过**：GitHub 的 macOS runner 一直被平台取消（iOS job 未分配 runner 即 `cancelled`，Android 正常），等 runner 恢复或换一台 Mac 直接跑 `build-ios12.sh` 即可。
