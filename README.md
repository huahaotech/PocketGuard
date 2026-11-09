# 口袋守护 · PocketGuard

为没有原生口袋防误触功能的 Android 手机提供距离感应防护。当前版本 **1.2**，包名 `com.pocketguard.app`，target SDK 34，最低 Android 8.0；推荐 Android 9 及以上使用无障碍熄屏。

## 功能

- 监听距离感应器，识别持续遮挡，减少放入口袋后的误触。
- 屏幕被唤醒后重新获取距离读数，再决定是否熄屏；不是在系统点亮屏幕前阻止唤醒，也不保证固定响应时间。
- 可设置正常使用时不自动熄屏，避免手指短暂遮挡感应器造成干扰。
- 在锁屏且符合设置条件时，检测通知面板展开并尝试收起。
- 可选透明悬浮层拦截触摸，提供前台服务通知与停止入口。

优先使用 Android 9+ 无障碍 `GLOBAL_ACTION_LOCK_SCREEN`；不可用或未实际熄屏时可降级为已授权设备管理员的 `lockNow()`。后者可能要求下次输入密码，不能保证保留指纹解锁。

普通第三方应用无法彻底禁止状态栏下拉。本项目采用“检测后收起”，可能有延迟，也可能受系统版本、SystemUI 实现及电池管理影响。距离感应器的有无及后台表现也因设备而异。

## 使用

安装后，在应用内按需进入系统设置授予无障碍、悬浮窗及备用设备管理员权限，再启用口袋防护。Android 13+ 侧载应用可能需要用户自行处理系统的受限制设置。前台服务会显示常驻通知。

撤销或卸载前，建议先关闭防护。若启用了设备管理员，需先在系统设置中停用该管理员，之后才能卸载。

## Windows 构建

纯 Java，无第三方应用依赖，不需要 Gradle。需要 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0，设置 `JAVA_HOME` 和 `ANDROID_HOME`：

```powershell
.\build.ps1
```

输出 `build/PocketGuard.apk`。脚本默认生成本机调试签名，密钥保存在被 Git 忽略的 `build/` 中；不要提交密钥。首次构建签名与此前分享的 APK 不同，通常无法直接覆盖安装。删除 build 目录会同时删除该本地调试签名，应自行备份需要长期使用的签名。

安装：`adb install -r build/PocketGuard.apk`。原有 `build.sh` / `install.sh` 保留为 Git Bash 构建方式，工具链路径需通过环境变量自行配置，详见脚本。

此开源整理保持原功能源码不变。已执行源码构建及 APK 签名验证；距离感应、锁屏、指纹与系统面板行为需要在目标真机验证。

## 隐私与开源

本应用不请求联网权限，不包含广告、统计或上传功能。无障碍会读取窗口信息及用于识别系统通知面板的节点信息；详见 [隐私说明](PRIVACY.md)。

源码采用 [Apache License 2.0](LICENSE)。其他项目：[平桌面](https://github.com/huahaotech/PingLauncher) · [静话](https://github.com/huahaotech/QuietCalls)。
