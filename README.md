# DiskAndroid

在安卓平板上通过 USB 外接光驱读取、播放、刻录 CD / DVD / BD。

- 不需要 root：走 Android USB Host API，在用户态直接发 SCSI / MMC 命令（USB Mass Storage Bulk-Only Transport）
- 目标设备：Xiaomi Pad 7S Pro + BUFFALO BRXL-PTV6U3-SVB
- 技术栈：Kotlin + Jetpack Compose，minSdk 29，targetSdk 35，NDK

## 文档

- 开发计划、风险、测试节点：[`docs/PLAN.md`](docs/PLAN.md)
- 当前测试节点说明：[`docs/testing/N1.1.md`](docs/testing/N1.1.md)

## 模块

| 模块 | 说明 |
|---|---|
| `app` | Compose 界面、USB 插入入口、诊断信息与日志导出 |
| `core:log` | 日志系统（纯 Kotlin）：内存环形缓冲、会话文件（.log / .jsonl）、hex dump |
| `core:usb` | USB 枚举、热插拔、权限、描述符解析、BOT 接口选择、连接测试 |
| `native/diskcore` | usbfs ioctl 辅助（链路速度、内核驱动、CLEAR_HALT、RESET）；后续承载原生传输层 |

## 构建

需要 JDK 17+、Android SDK（platform android-37.0、build-tools）、NDK 30.0.16248370、CMake 4.1.2。

```bash
./gradlew test                    # 单元测试 + Robolectric 冒烟测试
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

release 构建使用 `keystore/` 里的测试签名（见 [`keystore/README.md`](keystore/README.md)），只做 R8 裁剪不混淆，方便阅读崩溃堆栈。

官方 Maven Central 对部分共享出口 IP 返回 429，`settings.gradle.kts` 里优先使用 Google 托管的 Maven Central 镜像。
