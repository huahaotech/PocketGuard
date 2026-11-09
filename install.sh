#!/usr/bin/env bash
# 通过 adb 安装 / 重装
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-/c/platform-tools/adb.exe}"

"$ADB" wait-for-device
"$ADB" install -r "$(cygpath -w "$ROOT/PocketGuard.apk")"
echo "已安装。打开「口袋守护」并按提示开启设备管理员 / 悬浮窗 / 无障碍权限。"
