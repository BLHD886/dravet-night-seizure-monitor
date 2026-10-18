> 本文部分为早期开发说明，当前功能状态以仓库根README为准。

# 夜间守护原生 Android 工程

工程目标 Android 8.0+（minSdk 26），compile/target 35；Java 17、Android Gradle Plugin 8.9.0、Gradle 8.11.1。无第三方 App 运行库。现代机型仍必须分别验证通知、锁屏、后台和 USB；不承诺全品牌行为一致。

## 构建与 USB

1. 安装 Android Studio，打开本目录，安装 JDK 17、Android SDK Platform 35 / Build Tools 35.0.0；按官方安装流程审阅并接受适用 SDK 许可。
2. 运行 `./gradlew.bat :app:assembleDebug`（Linux/macOS：`sh gradlew :app:assembleDebug`）。包内包含校验过的 Gradle Wrapper，首次运行自动下载固定版本 Gradle 8.11.1；也可用根目录 Build-Android.ps1 指定 JDK/SDK 路径。
3. 开启手机开发者选项与 USB 调试；在手机上确认该电脑的授权。用 `adb devices` 验证状态。
4. 安装 app/build/outputs/apk/debug/app-debug.apk，运行 `adb reverse tcp:8765 tcp:8765`。
5. 电脑启动 `prototype/server.py --enable-live`；手机“设置”填写 `http://127.0.0.1:8765` 和电脑 runtime/dev-token.txt 中的家长访问码，保存并测试。
6. 手机同意通知权限后手动开始接收。接收服务应有常驻通知。初期只开启雷达故障提示；只有验证腕表标准心率接入后才勾选腕表提示。

USB reverse 仅在 USB 调试连接有效时工作，拔线后不可继续依赖它。不同房间测试用受信同一 Wi-Fi：服务监听 `--host 0.0.0.0 --enable-live`，手机填写电脑局域网 IP。debug 构建允许 HTTP 本机/私有 IP；release 默认禁止明文，需正式 HTTPS。不要把开发端口直接暴露公网。

## 功能与限制

- 原生页面：真实数据状态、手动事件演练及反馈、观察本地草稿和提交、摘要/助手、连接设置。
- SQLite 保存最近状态与通知去重；Android Keystore AES-GCM 保存访问码，备份关闭。
- 用户启动的 connectedDevice 前台服务与电脑网关交互，5 秒间隔查询；不是厂商系统推送。不规避 Doze、勿扰或强制停止，锁屏/省电模式送达没有验证就不能称为可靠。
- 15 秒数据陈旧与 30 秒启动宽限仅为工程链路提示参数，不是医学阈值。没有数据、空点云和区域无人不作为患者异常。
- 自动发作识别停用；只有手动演练事件和技术故障通知。二级演练不是疾病检测结果。
- 默认不会开机自启，也不自动要求解除电池限制。用户停止后不再接收。正式长期监护需进一步选定并验证推送/设备伴侣架构。
- 原型没有多家庭权限、临床性能验证或正式云部署。真实模型仍需配置且用户本次同意；不建议在未完成权限/加密部署的环境保存可识别患者记录。

本工程完成源码后仍需实际 APK 编译和至少一台真机安装测试。以交付记录中的实测状态为准，不能由“代码包含服务”推断已经锁屏送达。
