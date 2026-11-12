#!/usr/bin/env bash
# 在 macOS 上给 iOS 12 分支出未签名 ipa。
# 用法：bash build-ios12.sh [版本号]
# 需要：macOS + Xcode（14 或更新都可以，部署目标是 12.0）+ CocoaPods + Node。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="${1:-26.12.2}"

echo "== 1/5 安装前端依赖"
cd "$ROOT"
npm install --no-audit --no-fund

echo "== 2/5 同步网页资源（会用 ../mobile/www 覆盖 ios/App/App/public）"
npx cap sync ios

echo "== 3/5 pod install"
cd "$ROOT/ios/App"
pod install --repo-update

echo "== 4/5 xcodebuild（不签名）"
xcodebuild \
  -workspace App.xcworkspace \
  -scheme App \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  IPHONEOS_DEPLOYMENT_TARGET=12.0 \
  MARKETING_VERSION="$VERSION" \
  CURRENT_PROJECT_VERSION=1 \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO \
  build

echo "== 5/5 打包未签名 ipa"
APP="$ROOT/ios/App/build/Build/Products/Release-iphoneos/App.app"
if [ ! -d "$APP" ]; then
  echo "没找到 App.app，构建失败"
  exit 1
fi
OUT="$ROOT/../Outlay/Nalxer-$VERSION-ios12-unsigned.ipa"
rm -rf "$ROOT/payload" && mkdir -p "$ROOT/payload/Payload"
cp -R "$APP" "$ROOT/payload/Payload/"
cd "$ROOT/payload" && zip -qry "$OUT" Payload
echo "完成：$OUT"
unzip -l "$OUT" | head -12
