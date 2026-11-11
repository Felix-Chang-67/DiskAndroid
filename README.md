# DiskAndroid

在安卓平板上通过 USB 外接光驱读取、播放、刻录 CD / DVD / BD。

- 不需要 root：走 Android USB Host API，在用户态直接发 SCSI / MMC 命令（USB Mass Storage Bulk-Only Transport）
- 目标设备：Xiaomi Pad 7S Pro + BUFFALO BRXL-PTV6U3-SVB
- 技术栈：Kotlin + Jetpack Compose，minSdk 29，targetSdk 35，NDK

## 文档

- 开发计划、风险、测试节点：[`docs/PLAN.md`](docs/PLAN.md)
- 当前测试节点说明：[`docs/testing/N2.3.md`](docs/testing/N2.3.md)（历史：[`N1.1`](docs/testing/N1.1.md)、[`N1.2`](docs/testing/N1.2.md)、[`N1.3`](docs/testing/N1.3.md)、[`N1.5`](docs/testing/N1.5.md)、[`N1.5b`](docs/testing/N1.5b.md)、[`N1.6`](docs/testing/N1.6.md)、[`N2.1`](docs/testing/N2.1.md)、[`N2.2`](docs/testing/N2.2.md)）

## 模块

| 模块 | 说明 |
|---|---|
| `app` | Compose 界面、USB 插入入口、诊断信息与日志导出、音乐 CD 播放（AudioTrack、前台服务、MediaSession）、DVD 播放界面（全屏、菜单操作、自动播放、地区码提示） |
| `core:log` | 日志系统（纯 Kotlin）：内存环形缓冲、会话文件（.log / .jsonl）、hex dump |
| `core:scsi` | SCSI 传输层（纯 Kotlin）：命令/结果类型、Bulk-Only Transport、sense 解析（T10 ASC/ASCQ 表）、命令 hex 日志、只读白名单守卫 |
| `core:mmc` | MMC/SPC 命令构造与响应解析（INQUIRY、GET CONFIGURATION、MODE SENSE 2Ah、GESN、MECHANISM STATUS、RPC、READ CD、CD-TEXT……） |
| `core:drive` | 光驱会话：单线程命令队列、盘片/托盘状态机、轮询、事件；盘片信息读取（容量、TOC、轨道、盘片结构、CD-TEXT） |
| `core:cdda` | 音乐 CD 播放（纯 Kotlin）：READ CD 读取与重试 / 静音、字节序检测、去加重、播放引擎（缓冲、换轨、跳转） |
| `core:meta` | 在线曲目信息（纯 Kotlin）：MusicBrainz 光盘 ID、MusicBrainz / 网易云音乐 / LRCLIB 客户端、时长比对、LRC 歌词解析、本机缓存 |
| `core:fs` | 盘上的文件系统（纯 Kotlin）：ISO 9660 / Joliet / Rock Ridge、UDF 1.02–2.60（元数据分区、备用表、VAT）、卷标解码（UTF-8 / Shift-JIS / GBK）、盘片内容识别、文件系统结尾 |
| `core:video` | 视频盘的标题识别（纯 Kotlin）：DVD 的 IFO（标题、章节、单元、音轨 / 字幕）、BD / AVCHD 的播放列表（剪辑、章节、流）、过滤重复 / 循环播放列表、挑主片 |
| `core:player` | 视频播放器的 Kotlin 接口（`DvdPlayer`）和 JNI 桥；编译 `native/diskplayer` 成 libdiskplayer.so |
| `core:rip` | 读成 ISO（纯 Kotlin）：读取计划（数据区 / 补零区，可重写盘只读到文件系统结尾）、两遍读取引擎、ddrescue 格式 mapfile、读盘报告与校验值 |
| `core:testing` | 测试替身：模拟 MMC 光驱（FakeMmc）与可注入故障的 BOT 设备（FakeBotDevice） |
| `core:usb` | USB 枚举、热插拔、权限、描述符解析、BOT 接口选择、连接与占用、Android bulkTransfer 适配 |
| `native/diskcore` | usbfs ioctl 辅助（链路速度、内核驱动、CLEAR_HALT、RESET）；后续承载原生传输层 |
| `native/diskplayer` | 自写的 DVD 播放器（C）：libdvdnav 导航（首播、菜单、按钮、静止画面）、MPEG-PS 解复用、FFmpeg 解码与 bwdif 去隔行、DVD 字幕 / 菜单高亮叠加、音画同步；Android 部分是 JNI、OpenGL ES 3、AAudio。`test/` 是电脑上跑的测试（带菜单的测试 DVD 用 dvdauthor 生成） |
| `native/third_party` | `build.sh`：下载并编译播放器用的第三方库（libdvdread、libdvdnav、libudfread、libbluray、FFmpeg；版本固定、核对 SHA-256），输出静态库。解密库不在这里 |

## 构建

需要 JDK 17+、Android SDK（platform android-37.0、build-tools）、NDK 30.0.16248370、CMake 4.1.2。第一次编译 `core:player` 时会自动运行 `native/third_party/build.sh android-arm64`（要联网下载源码，需要 python3、curl、make、pkg-config；meson / ninja 没装的话自动装进 `native/third_party/out/venv`），之后就不再编。

```bash
./gradlew test                    # 单元测试 + Robolectric 冒烟测试
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk

# 原生播放器在电脑上的测试（需要 ffmpeg、dvdauthor、spumux、genisoimage）
bash native/third_party/build.sh host
bash native/diskplayer/test/run_host_tests.sh
```

release 构建使用 `keystore/` 里的测试签名（见 [`keystore/README.md`](keystore/README.md)），只做 R8 裁剪不混淆，方便阅读崩溃堆栈。

官方 Maven Central 对部分共享出口 IP 返回 429，`settings.gradle.kts` 里优先使用 Google 托管的 Maven Central 镜像。

`tools/gen_asc_table.py` 从 T10 官方 asc-num.txt 生成 `core/scsi/.../AscTable.kt`。
