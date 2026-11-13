#!/usr/bin/env bash
# 给未签名 ipa 补一份 ad-hoc 签名（补 application-identifier，免签工具装不上时用它）
# 用法: adhoc-sign.sh <App.app 路径> <输出 .ipa 路径>
set -uo pipefail

APP="${1:?用法: adhoc-sign.sh <App.app> <out.ipa>}"
OUT="${2:?用法: adhoc-sign.sh <App.app> <out.ipa>}"

if [ ! -d "$APP" ]; then
  echo "找不到 App.app: $APP"
  exit 1
fi

BUNDLE_ID=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$APP/Info.plist" 2>/dev/null || echo "top.nalxer.app")
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
PLIST

echo "bundle id : $BUNDLE_ID"
echo "team id   : $TEAM_ID"

# 逐个签框架 / dylib / appex；失败只记录，不中断（最后一定会出 ipa）
if [ -d "$APP/Frameworks" ]; then
  while IFS= read -r fw; do
    [ -z "$fw" ] && continue
    echo "sign framework: $(basename "$fw")"
    /usr/bin/codesign --force --sign - "$fw" 2>&1 || echo "  (警告) $(basename "$fw") 签名失败，继续"
  done < <(find "$APP/Frameworks" -maxdepth 2 -name "*.framework" -type d)
  while IFS= read -r dl; do
    [ -z "$dl" ] && continue
    echo "sign dylib: $(basename "$dl")"
    /usr/bin/codesign --force --sign - "$dl" 2>&1 || echo "  (警告) $(basename "$dl") 签名失败，继续"
  done < <(find "$APP/Frameworks" -maxdepth 3 -name "*.dylib")
fi

if [ -d "$APP/PlugIns" ]; then
  while IFS= read -r ex; do
    [ -z "$ex" ] && continue
    echo "sign appex: $(basename "$ex")"
    /usr/bin/codesign --force --sign - --entitlements "$ENT" "$ex" 2>&1 || echo "  (警告) $(basename "$ex") 签名失败，继续"
  done < <(find "$APP/PlugIns" -maxdepth 2 -name "*.appex" -type d)
fi

echo "sign app: $(basename "$APP")"
if ! /usr/bin/codesign --force --sign - --entitlements "$ENT" "$APP" 2>&1; then
  echo "(警告) 主 App 带 entitlements 签名失败，退化为不带 entitlements 再签一次"
  /usr/bin/codesign --force --sign - "$APP" 2>&1 || echo "(警告) 主 App 仍未签上，ipa 依然会打包"
fi

/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP" 2>&1 || echo "(提示) 校验未通过，仅影响部分安装器"

mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"

rm -f "$OUT"
( cd "$WORK" && zip -qry "$OUT" Payload )

if [ -f "$OUT" ]; then
  echo "已生成: $OUT"
  ls -lh "$OUT"
  unzip -l "$OUT" | head -20
else
  echo "打包失败：$OUT 未生成"
  exit 1
fi
