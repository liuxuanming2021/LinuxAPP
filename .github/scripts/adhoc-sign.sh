#!/usr/bin/env bash
# 给未签名 ipa 补一份 ad-hoc 签名（补 application-identifier，免签工具装不上时用它）
# 用法: adhoc-sign.sh <App.app 路径> <输出 .ipa 路径>
set -euo pipefail

APP="${1:?用法: adhoc-sign.sh <App.app> <out.ipa>}"
OUT="${2:?用法: adhoc-sign.sh <App.app> <out.ipa>}"

if [ ! -d "$APP" ]; then
  echo "找不到 App.app: $APP"
  exit 1
fi

BUNDLE_ID=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$APP/Info.plist")
TEAM_ID=$(printf '%s' "$BUNDLE_ID" | md5 -q | cut -c1-10)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ENT="$WORK/entitlements.plist"
cat > "$ENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>application-identifier</key>
  <string>${TEAM_ID}.${BUNDLE_ID}</string>
  <key>com.apple.developer.team-identifier</key>
  <string>${TEAM_ID}</string>
  <key>get-task-allow</key>
  <false/>
  <key>beta-reports-active</key>
  <true/>
</dict>
</plist>
PLIST

echo "bundle id : $BUNDLE_ID"
echo "team id   : $TEAM_ID"

# 先签框架与 dylib，再签主程序
find "$APP/Frameworks" -maxdepth 2 -name "*.framework" -type d 2>/dev/null | while read -r fw; do
  echo "sign framework: $(basename "$fw")"
  /usr/bin/codesign --force --sign - --timestamp=none "$fw"
done

find "$APP/Frameworks" -maxdepth 3 -name "*.dylib" 2>/dev/null | while read -r dl; do
  echo "sign dylib: $(basename "$dl")"
  /usr/bin/codesign --force --sign - --timestamp=none "$dl"
done

find "$APP/PlugIns" -maxdepth 2 -name "*.appex" -type d 2>/dev/null | while read -r ex; do
  echo "sign appex: $(basename "$ex")"
  /usr/bin/codesign --force --sign - --timestamp=none --entitlements "$ENT" "$ex"
done

/usr/bin/codesign --force --sign - --timestamp=none --entitlements "$ENT" "$APP"

/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP" || true

mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"

rm -f "$OUT"
( cd "$WORK" && zip -qry "$OUT" Payload )

echo "已生成: $OUT"
unzip -l "$OUT" | head -20
