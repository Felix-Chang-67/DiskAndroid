# DiskAndroid

在安卓平板上通过 USB 外接光驱读取、播放、刻录 CD / DVD / BD。

- 不需要 root：走 Android USB Host API，在用户态直接发 SCSI / MMC 命令（USB Mass Storage Bulk-Only Transport）
- 目标设备：Xiaomi Pad 7S Pro + BUFFALO BRXL-PTV6U3-SVB
- 技术栈：Kotlin + Jetpack Compose，minSdk 29，targetSdk 35，NDK

## 文档

- 开发计划、风险、测试节点：[`docs/PLAN.md`](docs/PLAN.md)
- 当前测试节点说明：[`docs/testing/N1.3.md`](docs/testing/N1.3.md)（历史：[`N1.1`](docs/testing/N1.1.md)、[`N1.2`](docs/testing/N1.2.md)）

## 模块

| 模块 | 说明 |
|---|---|
| `app` | Compose 界面、USB 插入入口、诊断信息与日志导出 |
| `core:log` | 日志系统（纯 Kotlin）：内存环形缓冲、会话文件（.log / .jsonl）、hex dump |
| `core:scsi` | SCSI 传输层（纯 Kotlin）：命令/结果类型、Bulk-Only Transport、sense 解析（T10 ASC/ASCQ 表）、命令 hex 日志、只读白名单守卫 |
| `core:mmc` | MMC/SPC 命令构造与响应解析（INQUIRY、GET CONFIGURATION、MODE SENSE 2Ah、GESN、MECHANISM STATUS、RPC……） |
| `core:drive` | 光驱会话：单线程命令队列、盘片/托盘状态机、轮询、事件；盘片信息读取（容量、TOC、轨道、盘片结构） |
| `core:testing` | 测试替身：模拟 MMC 光驱（FakeMmc）与可注入故障的 BOT 设备（FakeBotDevice） |
| `core:usb` | USB 枚举、热插拔、权限、描述符解析、BOT 接口选择、连接与占用、Android bulkTransfer 适配 |
| `native/diskcore` | usbfs ioctl 辅助（链路速度、内核驱动、CLEAR_HALT、RESET）；后续承载原生传输层 |

## 构建

需要 JDK 17+、Android SDK（platform android-37.0、build-tools）、NDK 30.0.16248370、CMake 4.1.2。

```bash
./gradlew test                    # 单元测试 + Robolectric 冒烟测试
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

release 构建使用 `keystore/` 里的测试签名（见 [`keystore/README.md`](keystore/README.md)），只做 R8 裁剪不混淆，方便阅读崩溃堆栈。

官方 Maven Central 对部分共享出口 IP 返回 429，`settings.gradle.kts` 里优先使用 Google 托管的 Maven Central 镜像。

`tools/gen_asc_table.py` 从 T10 官方 asc-num.txt 生成 `core/scsi/.../AscTable.kt`。
