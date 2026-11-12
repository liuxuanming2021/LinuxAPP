#!/usr/bin/env bash
# 把未签名的 App.app 做一次 ad-hoc 签名并打成 ipa。
# 用法: adhoc-sign.sh <App.app 路径> <输出 .ipa 路径>
set -euo pipefail

APP_DIR="${1:?需要 App.app 路径}"
OUT_IPA="${2:?需要输出 ipa 路径}"

if [ ! -d "$APP_DIR" ]; then
  echo "找不到 $APP_DIR" >&2
  exit 1
fi

PLIST="$APP_DIR/Info.plist"
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$PLIST" 2>/dev/null || echo 'top.nalxer.app')"
# ad-hoc 没有真实 team，用 bundle id 的散列生成 10 位前缀（字母数字）
TEAM="$(printf '%s' "$BUNDLE_ID" | md5 -q | cut -c1-10 | tr '0-9a-f' 'A-P')"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/entitlements.plist" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>application-identifier</key>
  <string>${TEAM}.${BUNDLE_ID}</string>
  <key>com.apple.developer.team-identifier</key>
  <string>${TEAM}</string>
  <key>get-task-allow</key>
  <false/>
  <key>beta-reports-active</key>
  <true/>
</dict>
</plist>
PLISTEOF

echo "== ad-hoc 签名 $BUNDLE_ID（team $TEAM）"

# 先签内嵌的 framework / dylib / appex，再签主包
find "$APP_DIR" -maxdepth 3 -name "*.framework" -type d | while read -r fw; do
  echo "   sign $(basename "$fw")"
  codesign --force --sign - --timestamp=none "$fw" >/dev/null 2>&1 || true
done
find "$APP_DIR" -maxdepth 4 -name "*.dylib" -type f | while read -r dylib; do
  codesign --force --sign - --timestamp=none "$dylib" >/dev/null 2>&1 || true
done
find "$APP_DIR" -maxdepth 4 -name "*.appex" -type d | while read -r appex; do
  codesign --force --sign - --entitlements "$WORK/entitlements.plist" --timestamp=none "$appex" >/dev/null 2>&1 || true
done

codesign --force --sign - --entitlements "$WORK/entitlements.plist" --timestamp=none "$APP_DIR"
codesign --verify --deep --strict "$APP_DIR" || echo "校验告警（ad-hoc 常见），继续打包"

mkdir -p "$WORK/Payload"
cp -R "$APP_DIR" "$WORK/Payload/"
( cd "$WORK" && zip -qry "$OUT_IPA" Payload )
echo "== 输出 $OUT_IPA"
unzip -l "$OUT_IPA" | head -8
