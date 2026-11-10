# DiskAndroid 开发计划（v2）

> 平板：Xiaomi Pad 7S Pro（Android 15 / HyperOS 2）｜光驱：BUFFALO BRXL-PTV6U3-SVB（USB 3.x BOT / MMC）
> 技术栈：Kotlin + Jetpack Compose，minSdk 29，targetSdk 35，NDK
> 开发全部在云端容器里完成（你暂时没有电脑可用）；每个可测节点我把 APK 直接发到对话里，你在平板上安装测试、把诊断信息贴回来。
>
> 进度：N1.1、N1.2 已在真机通过；N1.3 已完成，等待真机测试（见第 7 节和 `docs/testing/N1.3.md`）。

---

## 0. 结论速览

- **云端容器负责全部编译和自动化测试，但连不到你的平板。** 工具链已装好（见 1.1）。安装 APK、真机测试在你那边完成：APK 通过对话发给你，日志通过 App 的"复制诊断信息"贴回来。
- **我已下载并核对了所有第三方库的源码接口：**
  - libdvdread / libdvdnav / libbluray / libudfread 都有"回调式读块"接口，可以直接接到我们的 ScsiTransport，不用改源码。
  - **libdvdcss 和 libaacs 在回调模式下不会对光驱做认证**（libdvdcss 的 `dvdcss_use_ioctls()` 在 stream 模式下直接返回 0；libaacs 的 MMC 层只有 linux/darwin/win32 三种设备实现，没有设备路径时 `mmc_open(NULL)` 直接返回空）。两者都要各打一个"SCSI 回调后端"补丁，这是阶段 3 的核心工作量。
  - libburn 内置的 6 种系统适配器（linux/freebsd/netbsd/solaris/libcdio/dummy）都用不了 USB fd，需要新写一个 `sg-android` 适配器。
- **Media3 1.11.1 的解复用器对 DVD/BD 不够用**（已读源码确认），必须自写 PS Extractor 和 BD 用的 TS PayloadReader 工厂。详见风险 R4。
- 计划共 **28 个可测节点**（N0 … N5.2），每个节点都有：代码 + JVM 单元测试（用镜像文件模拟光驱）+ 一页真机测试说明。

---

## 1. 环境检查结果

### 1.1 云端容器（我这边）

| 项目 | 状态 | 说明 |
|---|---|---|
| 系统 | Ubuntu 24.04 x86_64，4 核 / 15 GB 内存 / 约 31 GB 可用磁盘 | 编 APK 足够；全部原生库都编出来时磁盘偏紧，需要定期清中间产物 |
| JDK | ✅ OpenJDK 21 | AGP 9.x 需要 JDK 17+ |
| Gradle | ✅ 系统自带 8.14.3 | 项目用 wrapper 锁定版本（当前最新 9.9.0） |
| Android SDK（cmdline-tools / platform-tools / platforms / build-tools） | ❌ 缺 | dl.google.com 可达，可装：platforms android-36/37、build-tools 37.0.0 |
| NDK | ❌ 缺 | 可装 r30（30.0.16248370）；r28 起默认 16 KB 页对齐 |
| CMake / Ninja | ✅ 系统自带 | SDK 里的 CMake 4.1.2 也可装 |
| meson | ❌ 缺 | libdvdread 7.1.1 / libdvdnav 7.0.0 / libdvdcss 1.6.0 / libbluray 1.5.0 / libudfread 1.2.0 **都已改用 meson**；用 pip 装 |
| autotools | autoconf / automake ✅，libtool ❌ | libaacs 0.12.0、libburn 1.5.8、libgcrypt 仍是 autotools；发布包自带 configure，只有要改 configure.ac 时才需要 libtool（apt 可装） |
| adb | ❌，**装了也没用** | 容器在云端，连不到你的平板 |
| 网络 | 大部分可达 | Google Maven / Maven Central / Gradle / VideoLAN / libburnia / gnupg / GitHub（release 下载、git clone）可用；**ffmpeg.org 不通**（连接被重置），改用 GitHub 上的 FFmpeg 镜像（能看到 tag n9.0.2）；codeload.github.com 不通（用 git clone 代替） |

**当前最新稳定版本（2026-11-09 查得，动手时锁定）：** AGP 9.5.1、Gradle 9.9.0、Kotlin 2.4.21、Compose BOM 2026.09.00、Media3 1.11.1、NDK r30、libusb 1.0.30、libudfread 1.2.0、libdvdread 7.1.1、libdvdnav 7.0.0、libdvdcss 1.6.0、libbluray 1.5.0、libaacs 0.12.0、libbdplus 0.2.0、libgcrypt 1.12.4、libgpg-error 1.61、FFmpeg 9.0.2、libburn 1.5.8、libisofs / libisoburn 1.5.8.pl02。

SDK 级别：compileSdk 37（Compose BOM 2026.09.00 的库要求 ≥ 37），targetSdk 35，minSdk 29（按你的要求）。只打 arm64-v8a。

**已在容器里安装（2026-11-09）：** cmdline-tools、platform-tools、platforms android-36 / android-37.0、build-tools 36.1.0、NDK r30（30.0.16248370）、CMake 4.1.2；Gradle wrapper 9.9.0。meson 到 P2 引入第三方库时再装。

**网络注意：** 官方 Maven Central（repo.maven.apache.org）对容器的出口 IP 返回 429 限流，所以 Gradle 和 Robolectric 都先走 Google 托管的 Maven Central 镜像（`maven-central.storage-download.googleapis.com`），官方 Central 作为后备。

**容器里没有 KVM，跑不了模拟器。** 发 APK 前的检查是：JVM 单元测试 + Robolectric 冒烟测试（真正启动 Application 和 Activity、渲染 Compose 界面、点主要按钮）+ Lint + 检查 APK（签名、JNI 符号、16 KB 对齐）。USB 相关的行为只能在你的真机上验证。

### 1.2 你那边需要准备的

1. **不需要电脑。** APK 直接发在对话里。
2. **平板设置：**
   - 允许"安装未知应用"：给你用来打开 APK 的应用（Claude App / 浏览器 / 文件管理器）开这个权限。
   - 如果开着"纯净模式"，会拦截不是从应用商店来的 APK，需要关闭或者单次允许。
   - 省电策略：本 App 设为"无限制"，避免长时间读盘或刻录时被后台限制。
3. **硬件：** 带 PD 供电的 USB 3.x 扩展坞，光驱的两个 USB 头都插上。
4. **测试盘（按阶段逐步准备）：**
   - **P1：** 音乐 CD（最好有一张带 CD-TEXT 的，日版 CD 常有）、CD-ROM 数据盘、DVD-ROM 数据盘、自刻 DVD±R、BD-ROM 或 BD-R 数据盘、一张有划痕的旧盘；有混合模式 / CD-Extra 盘更好。
   - **P2：** 无加密的 DVD-Video 和 BDMV / AVCHD 盘。可以在电脑上自制，到时我给步骤（例如 ffmpeg + dvdauthor 做 DVD，tsMuxeR 做 BDMV）。
   - **P3：** 你自己的商业 DVD / BD。
   - **P4–P5：** CD-RW、DVD-RW、DVD+RW、BD-RE 各一张，加少量 CD-R、DVD±R、BD-R。

### 1.3 每个节点的通用流程：怎么装、怎么测、日志在哪

1. **构建：** 我在容器里编译，跑单元测试（后续阶段用镜像文件模拟的 FakeDrive）和冒烟测试，提交并 push 到 `claude/jolly-tesla-c2qlm6`。
2. **拿 APK：** 我把 release 构建的 APK（R8 裁剪、不混淆，体积小、运行快）直接作为附件发到对话里，你在平板上下载安装。
   - 仓库里放了一个**仅用于测试的固定签名**（`keystore/`），每次构建出来的 APK 都能直接覆盖安装，不用卸载，已导入的组件和设置也不会丢。
3. **日志（没有电脑，所以不依赖 adb）：**
   - **"复制诊断信息"按钮（主要方式）：** 系统信息 + USB 设备 + 测试结果 + 最近 300 条日志，粘贴到对话里发给我。
   - **"导出日志（zip）"按钮：** 用 SAF 选位置，zip 里有 `diagnostics.txt`、`.log`（人类可读）和 `.jsonl`（机器可读，可以转成我这边的回放测试用例）。
   - **"分享日志"按钮：** 诊断信息 + 完整会话日志合成一个 txt，走系统分享。
   - **App 内日志面板：** 实时显示，可按级别过滤。
   - **闪退时：** 崩溃堆栈会同步写进日志文件；下次打开 App 时，系统记录的"上次退出原因"（崩溃 / ANR / 被系统杀掉）也会写进日志。重新打开 App 后点"复制诊断信息"即可。
   - App 在 `Android/data/<包名>/files/logs/` 保留最近 20 个会话的日志。
4. **每个节点我给你：** 安装方式、测试步骤、预期结果、要贴回的内容（诊断信息/截图/听感描述）。

---

## 2. 项目结构

包名暂定 `io.github.felixchang67.diskandroid`（可改）。

```
DiskAndroid/
├─ app/                        Compose UI、导航、设置、自动播放调度、前台服务、USB intent 入口
├─ core/
│  ├─ usb/                     设备枚举、权限、热插拔、接口/备用设置选择、链路速度、掉线/供电诊断
│  ├─ scsi/                    ScsiTransport 接口；BOT 实现（Kotlin bulkTransfer）；日志装饰器；只读守卫；Sense 解码表
│  ├─ mmc/                     MMC/SPC 命令构建与解析（纯 Kotlin，无 Android 依赖，可在 JVM 上单测）
│  ├─ drive/                   DriveSession（唯一命令出口，串行队列）、介质状态机、BlockDevice（预读缓存、重试、坏块表）
│  ├─ log/                     环形缓冲 + 滚动文件 + 导出/分享
│  └─ testing/                 FakeDrive：用 ISO / BIN+CUE 模拟 MMC 光驱，可注入坏扇区、NOT READY、中途拔出；日志回放
├─ feature/
│  ├─ rip/        (P1)         ISO 读取、坏扇区表（ddrescue mapfile 格式）、SAF 输出
│  ├─ cdaudio/    (P1)         TOC / 全 TOC、CD-TEXT、CDDA 读取、AudioTrack 引擎
│  ├─ fs/         (P2)         ISO9660 / Joliet / Rock Ridge（Kotlin）+ UDF（libudfread，JNI）
│  ├─ video/      (P2/P3)      VIDEO_TS / BDMV 识别、主片选择、Media3 DataSource / Extractor、播放器 UI
│  ├─ protected/  (P3)         组件导入与加载、CSS / AACS 桥接、地区码
│  └─ burn/       (P4)         libburnia 绑定、刻录向导、读回校验（P1–P3 的构建里不包含此模块）
├─ native/
│  ├─ diskcore/                C ABI 传输层 dc_scsi_exec()、JNI 桥、libusb 后端、块缓存、给第三方库的回调适配
│  ├─ thirdparty/              版本锁定的源码获取脚本（带 sha256 校验）
│  ├─ patches/                 第三方库补丁（每个库一个目录，附说明）
│  └─ build/                   NDK 交叉编译脚本（meson cross file / autotools 环境），统一 16 KB 页对齐
├─ plugins/decrypt/            可选解密组件（libdvdcss、libaacs+libgcrypt）的构建脚本与补丁；产物不进 APK
└─ docs/                       本计划、各节点测试说明、日志格式说明
```

### 2.1 分层

```
Compose UI ─ ViewModel
     │
DriveSession（唯一命令出口；单线程队列；优先级：用户操作 > 播放读 > 后台轮询）
     │                              │
MMC 命令层（core/mmc）         BlockDevice（大块预读 / 缓存 / 重试 / 坏块表）
     │                              │
ScsiTransport ◄─ ReadOnlyGuard ◄─ LoggingTransport ◄─ [ BotKotlinTransport | NativeTransport(libusb) ]
                                                            │
                     native/diskcore: dc_scsi_exec() ◄── libudfread / libdvdread / libdvdnav / libbluray 的读块回调
                                                     ◄── 解密组件（libdvdcss / libaacs）经 host API 回调
                                                     ◄── libburn 的 sg-android 适配器（P4）
```

### 2.2 ScsiTransport（示意，非最终代码）

```kotlin
interface ScsiTransport : Closeable {
    val info: TransportInfo                       // VID/PID、接口号、端点、链路速度、后端名
    fun execute(cmd: ScsiCommand): ScsiResult     // 同步；串行由 DriveSession 保证
}
class ScsiCommand(val cdb: ByteArray, val dir: Dir /* NONE | IN | OUT */,
                  val buf: ByteArray?, val offset: Int, val length: Int, val timeoutMs: Int)
sealed interface ScsiResult {
    data class Good(val transferred: Int, val residue: Int) : ScsiResult
    data class CheckCondition(val sense: Sense, val transferred: Int) : ScsiResult
    data class TransportError(val kind: Kind /* Timeout|Stall|PhaseError|DeviceGone|Io */,
                              val detail: String) : ScsiResult
}
```

### 2.3 BOT 传输要点（对照 USB Mass Storage Class Bulk-Only Transport 1.0）

- **CBW / CSW：** CBW 31 字节（签名 `USBC`，Tag 递增，同时作为日志序号）；CSW 13 字节（签名 `USBS`）。校验签名、Tag、Residue。
- **自动取 sense：** CSW 状态为 1（Failed）时，自动发 REQUEST SENSE（BOT 没有 auto-sense）。
- **错误恢复：**
  - Data-In 阶段 STALL：先清该端点的 HALT，再读 CSW。
  - Phase Error、CSW 无效、超时：执行 Reset Recovery，即 Bulk-Only Mass Storage Reset（类请求 FFh），再对两个端点清 HALT。
- **桥接芯片兼容：** 有的桥接芯片在数据阶段直接发 CSW；如果 Data-In 收到 13 字节、以 `USBS` 开头且 Tag 匹配，就按 CSW 处理并记一条警告。
- **Get Max LUN（FEh）：** 有的设备会 STALL 这个请求，按 LUN 0 处理。有多个 LUN 时，选 INQUIRY 外设类型为 05h（CD/DVD）的那个。
- **Android API 的坑：**
  - `bulkTransfer()` 失败只返回 -1，分不清 STALL、超时还是拔出。处理方式：失败后先查设备还在不在 `UsbManager.deviceList`，不在就判定拔出，在就按 STALL 处理。
  - 清 HALT 没有公开 API，只发 CLEAR_FEATURE 控制传输不一定会复位主机侧的 data toggle。所以 P1 会带一个约 30 行的极小 JNI：`ioctl(fd, USBDEVFS_CLEAR_HALT)`、`USBDEVFS_RESET`（最后手段）、`USBDEVFS_GET_SPEED`（精确链路速度）。**数据收发仍然是纯 Kotlin 的 bulkTransfer。**
  - 换 libusb 后端的主要收益正在这里：错误码精确（PIPE / NO_DEVICE / TIMEOUT）。
- **单命令传输长度：** 默认 32 扇区（64 KiB）。有的 USB-SATA 桥对单命令长度有上限（Linux usb-storage 默认 240 扇区），超了可能挂死。真机测吞吐后再调。

### 2.4 日志格式

每条命令记录：序号/Tag、时间戳、CDB hex、方向/长度、超时、耗时、CSW 状态、Residue、sense（原始 hex + SK/ASC/ASCQ 中文解码），以及当前状态机状态。

- **数据载荷：** ≤ 512 字节的全量 hex；读扇区类只记前 64 字节 + CRC32；**密钥交换类（P3）载荷默认打码**，CDB 和 sense 照常记录。
- **掉线时：** 把掉线前最后 50 条命令连同 USB 事件一起落盘。

### 2.5 只读守卫（P1–P3）

**用白名单而不是黑名单**：只放行已登记的命令，其余一律在发出前拒绝并记日志。P1–P2 不允许任何 Data-Out 命令。

| 操作码 | 命令 | 阶段 | 备注 |
|---|---|---|---|
| 00h | TEST UNIT READY | P1 | |
| 03h | REQUEST SENSE | P1 | |
| 12h | INQUIRY | P1 | |
| 1Bh | START STOP UNIT | P1 | 仅启动、停止、弹出、装载 |
| 1Eh | PREVENT ALLOW MEDIUM REMOVAL | P1 | 锁托盘，主要给 P4 用 |
| 25h | READ CAPACITY | P1 | |
| 28h / A8h | READ(10) / READ(12) | P1 | READ(12) 置 Streaming 位用于视频播放 |
| 42h | READ SUB-CHANNEL | P1 | MCN / ISRC |
| 43h | READ TOC/PMA/ATIP | P1 | 格式 0 / 1 / 2 / 5 |
| 46h | GET CONFIGURATION | P1 | |
| 4Ah | GET EVENT STATUS NOTIFICATION | P1 | Polled，Media 类事件 |
| 51h | READ DISC INFORMATION | P1 | |
| 52h | READ TRACK INFORMATION | P1 | |
| 5Ah | MODE SENSE(10) | P1 | 能力页 2Ah |
| A4h | REPORT KEY | P1 | P1 只用格式 08h（读 RPC 地区状态）；P3 开放 CSS/AACS 相关格式 |
| ACh | GET PERFORMANCE | P1 | |
| ADh | READ DISC STRUCTURE | P1 | |
| BBh | SET CD SPEED | P1 | 降低转速：降噪、降电流 |
| BDh | MECHANISM STATUS | P1 | |
| BEh | READ CD | P1 | |
| A3h | SEND KEY | P3 | 仅 CSS / AACS 认证格式；**Key Format 06h（设置地区码）永久拦截** |

以上操作码已与 Linux `include/uapi/linux/cdrom.h` 交叉核对；CDB 字段细节在实现时逐条对照 MMC-6 / SPC-4，并参考 libdvdcss、libaacs、libburn、libcdio 等成熟实现中已在真实光驱上验证过的写法。

### 2.6 介质状态机

```
NoDevice → NoPermission → Connected → [INQUIRY → TUR]
   ├─ 02/3A/xx 无盘（01 托盘关 / 02 托盘开）
   ├─ 02/04/01 正在就绪 → 轮询 TUR（间隔递增，上限可设，默认 30 s）
   ├─ 06/28/00 换盘（UNIT ATTENTION） → 重新识别
   ├─ 06/29/xx 上电/复位 → 若会话中途出现且不是我们发起的 → 记为疑似供电问题
   └─ Ready → GET CONFIGURATION / READ TOC / … → 分类：音乐 CD｜数据盘｜DVD-Video｜BDMV → 自动播放判定
```

GET EVENT STATUS NOTIFICATION 每秒轮询一次（插在读命令之间），检测"弹出请求 / 新盘 / 盘被拿走"。

---

## 3. 阶段任务拆分与依赖

相对工作量：S < M < L < XL。

### P0 工程骨架（S）

- 容器装工具链；Gradle 多模块骨架 + 版本目录；Compose 空壳；FakeDrive 雏形；CI（如你同意）；测试 keystore；docs。
- **N0：** 装 APK → 打开显示"未连接光驱" → 打通无线 adb 和"导出日志"流程。

### P1 只读（L）——严禁任何写盘命令

**N1.1 USB 枚举与权限**

- `device_filter.xml` 初期按 class 8 匹配。拿到 VID/PID 后收窄，避免插 U 盘也弹出本 App；同时可在设置里开关"对所有 USB 存储设备弹出"（用 activity-alias 运行时启用/禁用实现）。
- `USB_DEVICE_ATTACHED` 拉起 App；Activity 用 singleTask，`onNewIntent` 处理重复插入。
- 申请权限（Android 14+ 要求显式 Intent + FLAG_MUTABLE）；被拒绝时给出引导和重试按钮。
- 处理热插拔（ATTACHED / DETACHED 广播）。
- 选接口：08/06/50；如果存在 UAS（协议 62h）备用设置，显式切到 BOT 备用设置；subclass 为 02h / 05h 时兼容但告警。
- `claimInterface(force = true)`：会把内核 usb-storage 驱动踢开（Android 的存储服务会先尝试挂载它，失败是正常的）。
- 链路速度：bulk 端点 wMaxPacketSize 1024 = USB3 SuperSpeed，512 = USB2 High-Speed；JNI 用 `USBDEVFS_GET_SPEED` 再确认。
- 显示原始描述符。

**N1.2 BOT 传输 + 基础命令**

- INQUIRY：确认外设类型 05h，读出真实机芯厂商、型号、固件版本。
- TUR / REQUEST SENSE / 状态机。
- GET CONFIGURATION：Profile 列表、当前 Profile、关键 Feature（Removable Medium、CD Read、DVD Read、BD Read、Real-Time Streaming、DVD CSS、AACS…）。
- MODE SENSE 2Ah、GET PERFORMANCE、MECHANISM STATUS、GESN。
- REPORT KEY 格式 08h：读 DVD 地区状态（只读，提前暴露 P3 的地区码风险）。

**N1.3 盘片信息**

- READ CAPACITY、READ TOC（格式 0 / 1 / 2）、READ DISC INFORMATION、READ TRACK INFORMATION、READ DISC STRUCTURE（DVD 物理格式和版权信息；BD 盘信息）。
- 识别盘片类型：CD-DA / CD-ROM / CD-R / CD-RW、DVD-ROM / ±R / ±RW / DL、BD-ROM / R / RE / BDXL，以及层数、会话数、空白/可追加/已关闭状态。

**N1.4 读成 ISO**

- **大小判定：** READ CAPACITY 与 TOC、轨道信息、文件系统卷大小交叉核对，差异写日志。TAO 轨道末尾 2 个 run-out 扇区、多区段盘的区段间隙，标记为"间隙"补零，不计为坏扇区。
- **读取策略（类 ddrescue 两遍）：**
  - 第 1 遍：大块读，遇错跳过。
  - 第 2 遍：对坏区逐扇区重试 N 次（可设），仍失败则补零并记录。
- **输出：** ISO、`.log`、ddrescue 格式 mapfile。SAF 选目录，流式写入，不整盘进内存。
- **界面：** 进度、速度（MB/s 和 CD/DVD/BD 倍速）、可取消。
- **中途拔出：** 保留已写部分，标记为不完整（后续可做续读）。
- **系统要求：**
  - 前台服务用 `connectedDevice` 类型（不用 dataSync，Android 15 对 dataSync 有每 24 h 共 6 h 的限制）。
  - 开始前检查可用空间；大于 4 GB 写入失败时提示可能是 FAT32 目标。

**N1.5 音乐 CD 播放**

- **轨道识别：**
  - TOC 的 CONTROL 位区分音轨和数据轨；混合模式盘跳过数据轨。
  - CD-Extra：用全 TOC 中会话 1 的 lead-out 确定最后一首音轨的结尾；拿不到时回退为"下一会话起点 − 11400 扇区"。
- **CD-TEXT：** READ TOC 格式 5，先读 4 字节头再读全量。字符集按规范支持 ISO-8859-1 / ASCII / MS-JIS，必要时猜测 GBK。没有 CD-TEXT 显示"Track N"。
- **读取：** READ CD（BEh）用户数据 2352 字节 / 扇区，可选 C2 错误指针。
  - 字节序：默认小端，用 cdparanoia 式启发检测，设置里可手动覆盖。
  - 播放时可选降速（SET CD SPEED）以降噪、降电流。
- **播放：**
  - AudioTrack 16-bit / 44.1 kHz / 立体声；读线程 + 约 5 秒环形缓冲。
  - 支持上一首、下一首、暂停、拖动进度。
- **坏扇区：** 补静音并做几毫秒淡入淡出防爆音，同时写日志。任何读超时都不阻塞 UI 和音频线程。
- **系统集成：** 音频焦点；耳机拔出时暂停；前台服务 `mediaPlayback`，息屏继续播。
- **预加重：** 有预加重标志位时先记日志，暂不做去加重滤波。

**N1.6 整合与 P1 版自动播放**

- 主界面：设备状态、盘片信息、读取按钮、播放控制、日志面板。
- 插入音乐 CD 时：显示"2 秒后播放 [取消] [立即播放]"横幅，起播约 1 秒淡入。
- 弹盘或拔线：立即停播并释放资源。
- 供电诊断提示（见风险 R1）。

### P2 文件系统与未加密视频盘（L）——依赖 P1 的 BlockDevice；从这里起引入 NDK 第三方库

**N2.1 文件浏览**

- ISO9660 / Joliet（中文文件名）/ Rock Ridge 用 Kotlin 自写；非标准 GBK 文件名做回退。
- UDF 用 libudfread（支持到 2.50 metadata 分区，BD 必需）。libudfread 不支持 VAT 和可备用分区（即包写入的 CD-RW / DVD-RW、UDF 1.5 CD-R），遇到时给出明确提示。
- 文件浏览 UI；可把单个文件原样导出到 SAF。

**N2.2 视频结构识别 + 主片选择**

- **DVD：** VIDEO_TS → libdvdread（`DVDOpenStream2` 回调，不加载 libdvdcss）解析 IFO：每个标题的 PGC 时长、章节、音轨/字幕属性。按你的规则取时长最长的标题。
- **BD：** BDMV（也识别 AVCHD 目录结构）→ libbluray（`bd_open_stream` 回调）列出 PLAYLIST/*.mpls 的时长，取最长；`bd_get_main_title()` 的结果作为参考，两者不同时在日志里标出。
- 候选列表 UI，可手动改选。

**N2.3 DVD 播放**

- **DataSource：** 通过 libdvdread 按 PGC 的 cell 顺序读取标题 VOB 扇区。
  - 这条路径到 P3 会自动经过 libdvdcss 解密，不用重写。
- **自写 PS Extractor：** 拆分 private stream 1 的子流（AC-3 80–87h、DTS 88–8Fh、LPCM A0–A7h、字幕 20–3Fh）。
- **SeekMap：** 用 IFO 时间表一次定位，避免二分查找带来的多次寻道。
- **解码：**
  - MPEG-2：设备 MediaCodec（运行时列出解码器并写日志）。
  - AC-3：设备没有对应解码器时，走 Media3 FFmpeg 音频扩展。

**N2.4 BD 播放**

- **DataSource：** 读 libbluray `bd_read()` 输出的主播放列表 M2TS 流（同样到 P3 自动经过 libaacs 解密）。
- **192 → 188 字节适配。**
- **自定义 HDMV 流类型映射：** 80h LPCM、81h AC-3、82h DTS、83h TrueHD、84h E-AC-3、85h / 86h DTS-HD、90h PGS、EAh VC-1、1Bh AVC。
- **SeekMap：** 用 libbluray 的 EP_map。
- **字幕：** PGS 用 Media3 自带的 PgsParser。

**N2.5 自动播放（完整规则）**

- 有音轨的 CD → 播放音轨；DVD/BD 检测到 VIDEO_TS 或 BDMV → 播放主片；其他数据盘 → 进入文件浏览。
- 设置开关（默认开）、起播前提示、弹盘/拔线立即释放。

*可选加项：* VCD / SVCD（Mode 2 Form 2 的 MPEG-1/2，国内常见），READ CD 读出后交给 PS Extractor 播放。

### P3 商业光盘兼容，仅限个人播放（XL）——依赖 P2 播放管线

> 解密组件是否合法取决于你所在地的法律。按你的设计：组件不随 App 分发、只做边读边播、不提供解密导出、"读成 ISO"对加密盘保持原始密文——这些我会在代码层面强制执行（例如 rip 模块根本不链接任何解密路径）。

**N3.0 原生传输层**

- libusb 1.0.30，`LIBUSB_OPTION_NO_DEVICE_DISCOVERY` + `libusb_wrap_sys_device(fd)`。
- 与 Kotlin 后端并存，设置里可切换；同一套日志格式；对比吞吐与稳定性。
- 定义插件 host API（`dc_host_api`：版本号、`scsi_exec`、`log`），组件导出 `dc_plugin_init()` 接收它。不依赖全局符号。

**N3.1 组件导入与加密检测**

- **导入：** SAF 选 .so 或 KEYDB.cfg → 复制到 `files/plugins/` → 设为只读（Android 14+ 动态代码加载要求）→ 校验 ELF 为 arm64 并检查导出符号和版本 → 用绝对路径加载。
- **检测：** CSS 看 READ DISC STRUCTURE 01h 的版权保护类型；AACS 看 AACS 目录 + GET CONFIGURATION 的 AACS Feature。
- **组件缺失时：** 提示"该盘有版权保护，需要启用解密组件"。

**N3.2 地区码**

- 读光驱 RPC 状态（RPC-1 / RPC-2、当前地区、剩余可改次数）和盘片地区，不匹配时明确提示。
- 绝不修改地区码：SEND KEY 06h 在守卫层被永久拦截。
- BD 的地区码不由光驱强制，而由播放器设置和盘上程序检查；直接播放主片时一般不受影响。

**N3.3 libdvdcss 补丁 + CSS 认证 + 主片解密播放**

- **补丁：** 给 ioctl 层新增"回调 SCSI"平台后端，使 stream 模式也能完成 AGID → Challenge → Key1/Key2 → 盘钥 / 标题钥。
- **加载：** libdvdread 按 soname `libdvdcss.so.2` dlopen；我们先用绝对路径预加载组件，不行再给 libdvdread 打一行补丁。
- `DVDCSS_CACHE` 指向私有目录。
- **sense 解码为中文提示：** 6F/00 认证失败、6F/01 密钥不存在、6F/02 密钥未建立、6F/03 未认证读取加密扇区、6F/04 地区不符、6F/05 地区设置次数问题。

**N3.4 DVD 音轨 / 字幕切换**

- PS Extractor 已经拆好子流，音轨/字幕直接映射为 Media3 的轨道选择。
- VobSub 字幕用 IFO 里的调色板渲染。

**N3.5 DVD 菜单（libdvdnav）——先做技术验证 spike，再定方案**

- 难点：静帧（STILL_FRAME）、跳转时冲刷解码器（HOP_CHANNEL）、cell 间时间戳不连续、按钮高亮叠加层。
- **方案 A：** 自定义 MediaSource + 时间戳重定基 + Compose 叠加层绘制按钮。
- **方案 B（A 不可行时）：** 自建管线（MediaCodec + AudioTrack + 自己做音画同步）。
- spike 完成后把结论和工作量给你，再决定。

**N3.6 libaacs 补丁 + AACS 认证**

- **依赖：** libaacs 依赖 libgcrypt + libgpg-error，交叉编译后静态链入，组件只有一个 `libaacs.so` 文件。
- **补丁：** 新增 Android MMC 设备后端，把 `device_send_cmd` 转到 host API；`mmc_open(NULL)` 时使用回调设备。这样 libbluray 的 `bd_open_stream` 不用改。
- **路径：** `XDG_CONFIG_HOME` 指向私有目录（libaacs 在 `$XDG_CONFIG_HOME/aacs/KEYDB.cfg` 找密钥库）；`LIBAACS_PATH` 指向导入的组件（libbluray 1.5.0 原生支持这个环境变量）。
- **错误分级提示：**
  - 光驱没有 AACS Feature → "该光驱不支持 AACS"。
  - 主机认证时返回 CHECK CONDITION → "该光驱不支持此盘的认证"，原始 sense 全量写日志。
  - AACS 2.0（libaacs 识别为内容证书类型 10h）→ "不支持 AACS 2.0（4K UHD）"。
  - BD+（libbluray 的 `bdplus_detected`）→ 提示可能花屏（libbdplus 暂不纳入）。

**N3.7 BD 主片解密播放**

- 复用 N2.4 的管线。
- 针对"播放列表混淆"（大量时长相同的 MPLS）：启发式检查（片段顺序、重复片段）+ 手动选择。

### P4 刻录（XL）——依赖 P1 和 N3.0；技术上不依赖 P3，如果想先做刻录可以和 P3 对调

- **N4.0 只读的写能力检测：** GET CONFIGURATION 的写相关 Feature、GET PERFORMANCE 写速度、盘片空白/可追加状态。这一步**仍然不发任何写命令**。
- **N4.1 libburnia 编译 + 适配器：** NDK 编 libburn / libisofs / libisoburn；新写 `sg-android.c`（参照 sg-dummy.c 的 359 行接口：`sg_grab` / `sg_release` / `sg_issue_command` / `sg_give_next_adr` …），接到 diskcore 传输层。先只跑 libburn 的查询功能，与 N4.0 的结果对照。
- **N4.2 模拟刻录：** Test Write 只有 CD-R 和 DVD-R 支持（DVD+R / BD 不支持模拟）。不烧盘就能把全流程跑一遍。
- **N4.3 RW 盘真实刻录：** CD-RW / DVD-RW / DVD+RW / BD-RE。刻 ISO 文件 → 读回 → 逐扇区比对 SHA-256。
- **N4.4 一次性盘：** CD-R、DVD±R，再到 BD-R（BDXL 视光驱和盘的支持情况）。
- **N4.5 从文件夹直接刻：** 用 libisofs 现场生成 ISO 流式刻录，不落地临时文件；多区段追加作为可选项。
- **安全措施：**
  - 刻录前二次确认：显示盘片类型、容量、写入大小、速度、是否可擦写。
  - 刻录中：前台服务、屏幕常亮、锁托盘（PREVENT），拦截返回键误触。
  - 电量低于 30% 且未在充电时拒绝开始。
  - 必须在 N1.6 的供电诊断验证通过后才开始 P4。
  - 完成后自动读回校验；每种失败都给出可理解的原因。
- **许可证：** libburnia 是 GPL，见风险 R9。

### P5 音频 CD 抓轨与刻录（M–L）——依赖 P4 和 N1.5

- **N5.1 抓轨：**
  - 安全抓取：重叠读 + 重读比对（cdparanoia 思路）。
  - 读偏移校正：各型号光驱的偏移值可查 AccurateRip 数据。
  - 输出 WAV / FLAC（Android 自带 FLAC 编码器）到 SAF。
  - 元数据来自 CD-TEXT；MusicBrainz 和 AccurateRip 校验为可选的联网功能。
- **N5.2 音频 CD 刻录：** MediaExtractor + MediaCodec 解码任意音频 → 重采样为 44.1 kHz / 16 bit / 立体声 → libburn SAO 写入（曲间 2 秒或 0 秒、CD-TEXT）。先用 CD-RW 测试。

### 3.1 依赖关系

```
P0 → N1.1 → N1.2 → N1.3 ─┬─ N1.4（ISO）
                         └─ N1.5（CD 音频）→ N1.6（整合）
N1.3 → N2.1（引入 NDK）→ N2.2 ─┬─ N2.3（DVD）─┐
                               └─ N2.4（BD）──┴─ N2.5（自动播放）
N2.x → N3.0（libusb）→ N3.1 ─┬─ N3.2 → N3.3 → N3.4 → N3.5（菜单 spike）
                             └─ N3.6 → N3.7
N3.0 + N1.6 → N4.0 → N4.1 → N4.2 → N4.3 → N4.4 → N4.5
N4.x + N1.5 → N5.1 / N5.2
```

### 3.2 原生库编译与适配（全部 arm64-v8a、NDK r30、API 29、`-Wl,-z,max-page-size=16384`）

| 库 | 版本 | 许可 | 构建 | 位置 | 适配 / 补丁 |
|---|---|---|---|---|---|
| libusb | 1.0.30 | LGPL-2.1+ | CMake（自写） | APK | 无，用 `libusb_wrap_sys_device` |
| libudfread | 1.2.0 | LGPL-2.1+ | meson | APK | 无，用 `udfread_block_input` 回调 |
| libdvdread | 7.1.1 | GPL-2.0+ | meson | APK | `DVDOpenStream2` 回调；libdvdcss 加载方式（预加载，必要时一行补丁） |
| libdvdnav | 7.0.0 | GPL-2.0+ | meson | APK | `dvdnav_open_stream2` 回调 |
| libbluray | 1.5.0 | LGPL-2.1+ | meson（关闭 BD-J / Java） | APK | `bd_open_stream` 回调；`LIBAACS_PATH` |
| FFmpeg | 9.0.2（GitHub 镜像） | LGPL-2.1+（不启用 GPL 组件） | configure | APK | 只编 AC-3 / E-AC-3 / DTS / TrueHD 等音频解码器，供 Media3 decoder_ffmpeg 使用 |
| libdvdcss | 1.6.0 | GPL-2.0+ | meson | **外部组件** | **补丁：回调 SCSI 后端**（stream 模式原本不认证） |
| libaacs（+ libgcrypt 1.12.4、libgpg-error 1.61） | 0.12.0 | LGPL-2.1+ | autotools | **外部组件** | **补丁：Android MMC 设备后端**；libgpg-error 需为 aarch64-linux-android 生成 lock-obj 头 |
| libburn / libisofs / libisoburn | 1.5.8 / 1.5.8.pl02 | GPL-2.0+ | autotools | APK（burn 模块） | **新写 `sg-android.c` 系统适配器** |
| libbdplus | 0.2.0 | LGPL | — | 不纳入 | 只检测并提示 |

---

## 4. 风险点

### R1 供电（你点名的）

- **现象：**
  - 起转、寻道、刻录时电流峰值导致掉线：DETACHED 后几秒内又 ATTACHED。
  - 光驱自身欠压复位：会话中突然出现 06/29/xx（上电/复位），而 USB 并没有断开。
  - 读命令连续超时。
- **对策：**
  - 掉线分类器：命令进行中掉线、起转后 N 秒内掉线、30 秒内反复插拔、出现非我方发起的 06/29 复位——任一命中即判定为"疑似供电不足"。
  - 弹出指定提示："请检查带供电的扩展坞 / Boost 线"，并提醒光驱的两个 USB 头都要插上。
  - 日志附上掉线前的最后 50 条命令。
  - 读盘时可选"降速重试"（SET CD SPEED 降低电流）。
  - P4 前做一次"起转 + 全速读 30 秒"的供电自检。
- **限制：**
  - Android 读不到 VBUS 电流，只能间接推断。
  - 刻录中掉电会废掉一次性盘，所以供电诊断必须在 P4 前验证。

### R2 BD 的 AACS 认证（你点名的）

- **认证失败最常见的原因与 USB 无关：** 公开流传的主机证书大多已被新盘携带的主机吊销列表（HRL）吊销，光驱会把新盘上的 HRL 存进固件，之后用这些证书认证就会失败。表现为"光驱不支持此盘的认证"。
- **不需要驱动认证的情形：** KEYDB 里有该盘的 VUK，且盘没开总线加密。
- **需要认证的情形：** 需要读 Volume ID，或盘开了总线加密（要读 Read Data Key）。
- **我们的做法：** 把认证每一步的 CDB 和 sense 全量写进日志，供你判断是否为固件限制；不涉及刷固件。
- **AACS 2.0（UHD）：** libaacs 能识别但不支持，直接提示。
- **BD+：** 没有 libbdplus，只能检测并提示。
- **主片识别：** 部分商业 BD 用播放列表混淆，"取最长"可能选错，需要候选列表 + 启发式 + 手动选。

### R3 地区码（你点名的）

- **DVD：**
  - RPC-2 光驱在固件里强制地区；地区不符时光驱拒绝读取加密扇区（6F/04）。我们只读取 RPC 状态并提示，不修改。
  - BUFFALO 是日本品牌，出厂可能设为地区 2 或"未设置"；部分固件在未设置时同样拒绝读加密扇区。如需设置地区（会消耗有限的修改次数），由你在电脑上用官方方式自行决定。
  - **N1.2 就会读出 RPC 状态，提前暴露这个风险。**
- **BD：** 地区码不由光驱强制；绕过菜单直接播主片一般不受影响。

### R4 ExoPlayer 自定义 DataSource 性能（你点名的）

- **寻道：** 光驱随机寻道 100–200 ms+，起转 1–3 s。Media3 对 TS/PS 默认用 PCR/SCR 二分查找 seek，一次拖动会触发多次寻道，延迟秒级。→ DVD 用 IFO 时间表、BD 用 CLPI 的 EP_map 做 SeekMap，一次定位。
- **小块读：** Extractor 读得很碎。→ 下层 BlockDevice 做大块预读（256 KB–1 MB）+ 缓存；单命令长度受桥接芯片限制，真机调优。
- **BOT 串行：** 播放读、GESN 轮询、UI 查询共用一条通道。→ DriveSession 优先级队列，读请求切小块。
- **带宽不是瓶颈：** DVD 最高 10.08 Mbit/s，BD 最高 48 Mbit/s（约 6 MB/s），USB3 和 BD 6x（约 27 MB/s）都够。瓶颈是寻道、起转和空闲降速。→ 加大 LoadControl 缓冲并持续预读。
- **Media3 1.11.1 解复用器缺口（已读源码确认）：**
  - `PsExtractor` 把整个 private stream 1（0xBD）交给一个 Ac3Reader，多音轨、DTS、LPCM、字幕会混成一路。
  - `TsExtractor` 固定 188 字节包（`TS_PACKET_SIZE = 188`），不认 BD 的 192 字节包。
  - 流类型 80h 在 Media3 里是 DigiCipher II 视频、86h 是 SCTE-35，而在 BD 里分别是 LPCM 和 DTS-HD MA；Media3 也没有 TrueHD（83h）、PGS（90h）、VC-1（EAh）的映射。
  - → 自写 PS Extractor 和 HDMV PayloadReader 工厂（N2.3 / N2.4 已计入）。
- **解码器：**
  - MPEG-2：依赖设备 MediaCodec。AOSP 自带软解，但厂商可能裁剪，需要 N2.3 真机确认。
  - VC-1（部分早期 BD）：Android 基本没有解码器，可能无法播放；要支持只能自接 FFmpeg 视频解码，工作量大。
  - DTS / TrueHD：走 FFmpeg 音频扩展。

### 其他风险

- **R5 Android USB 栈：** `bulkTransfer` 不给 errno；清 HALT 无公开 API；桥接芯片怪癖（提前发 CSW、Get Max LUN 时 STALL、单命令长度上限）；UAS 备用设置；HyperOS 可能对 OTG 有省电策略，需要真机观察。
- **R6 DVD 菜单与 ExoPlayer 的整合：** 静帧、跳转冲刷、时间戳重定基、按钮高亮。这是 P3 最大的工程风险，所以先做 spike。
- **R7 补丁维护：** libdvdcss / libaacs 的补丁要随上游版本维护；libgcrypt / libgpg-error 交叉编译较繁琐。
- **R8 外部组件加载：** Android 14+ 要求动态加载的文件为只读；不 root 的平板上用户无法直接往私有目录放文件。→ App 提供"导入组件"（KEYDB.cfg 同理）。
- **R9 许可证：** libdvdread / libdvdnav / libdvdcss / libburnia 是 GPL；Media3 是 Apache-2.0（与 GPLv2-only 不兼容，与 GPLv3 兼容）。→ 建议整个项目用 GPL-3.0-or-later。自用无影响；若公开发布需开源。
- **R10 CDDA 细节：** 字节序（少数光驱为大端）；读偏移（播放无所谓，抓轨要校正）；预加重。
- **R11 测试受限：** 我接触不到真机，所有 USB / 时序 / 固件问题都依赖你的日志。→ 日志可回放成测试用例，FakeDrive 覆盖逻辑分支。
- **R12 系统限制：** Android 15 对 dataSync 前台服务有时长限制（我们用 connectedDevice / mediaPlayback）；HyperOS 后台策略；刻录全程屏幕常亮。
- **R13 文件系统：** libudfread 不支持包写入格式；非标准 GBK 文件名。

---

## 5. 真机测试 / 日志节点汇总

| 节点 | 你要做的 | 需要的盘 / 条件 | 贴回给我 |
|---|---|---|---|
| N0 | 安装、打开、导出一次日志 | — | 安装是否被 HyperOS 拦截；导出的日志 |
| N1.1 | 插拔光驱 5 次；拒绝一次权限再同意 | 无盘 | 设备信息页（VID/PID、接口、端点、链路速度）+ 日志 |
| N1.2 | 无盘 / 开托盘 / 依次放 CD、DVD、BD | 各一张 | 光驱信息页（INQUIRY、Profile、Feature、RPC 状态）+ 状态变化日志 |
| N1.3 | 每种盘点"盘片信息" | 尽量多类型 | 盘片信息导出 |
| N1.4 | 读一张 CD-ROM 和一张 DVD 成 ISO，在电脑上算 SHA-256 比对；读到一半拔线一次；读一张划痕盘；试 16 / 32 / 64 / 128 扇区块大小 | 最好有已知校验值的盘 | 速度、日志、坏扇区表、比对结果 |
| N1.5 | 音乐 CD：切歌、暂停、拖动；划痕盘；播放中弹盘；播放中拔线 | 日本音乐 CD（带 CD-TEXT 更好；早期带预加重的盘、CCCD、加强版 CD 如有） | 曲名是否正确 + 听感（杂音 / 爆音 / 卡顿）+ 日志 |
| N1.6 | 插盘自动播放；关闭自动播放；（可选）拔掉扩展坞电源复现掉线 | — | 提示是否出现 + 日志 |
| N2.1 | 浏览各类数据盘，导出一个文件 | Joliet 中文名 CD-R、UDF DVD、BD-R | 目录截图 + 日志 |
| N2.2 | 查看主片识别结果 | 自制 DVD-Video、自制 BDMV / AVCHD | 候选列表截图 |
| N2.3 | DVD 起播、拖动、切音轨 | 自制 DVD-Video | 起播 / 拖动耗时、解码器列表日志 |
| N2.4 | BD 起播、拖动 | 自制 BDMV | 同上 |
| N2.5 | 三类盘的插盘自动行为 | 音乐 CD / DVD / 数据盘 | 是否符合规则 |
| N3.0 | 切到 libusb 后端重测 N1.4 | 同 N1.4 | 两种后端的吞吐与稳定性对比 |
| N3.1 | 未导入组件时插加密盘；导入组件 | 商业 DVD / BD | 提示截图 + 日志 |
| N3.2 | 查看地区状态 | 不同地区的 DVD（如有） | RPC 状态日志 |
| N3.3–N3.4 | 播放商业 DVD 主片，切音轨 / 字幕 | 商业 DVD | 认证日志（载荷已打码）+ 播放情况 |
| N3.5 | 菜单导航（spike 后） | 带菜单的 DVD | 操作录屏 / 描述 |
| N3.6–N3.7 | 导入 KEYDB；播放商业 BD | 新旧不同年份的 BD 各一张 | AACS 认证全过程日志（含原始 sense） |
| N4.0–N4.1 | 查看写能力报告 | 空白盘 | 报告导出 |
| N4.2 | 模拟刻录 | CD-R / DVD-R | 日志 |
| N4.3 | RW 盘真实刻录 + 读回校验 | CD-RW / DVD±RW / BD-RE | 日志 + 校验结果 |
| N4.4–N4.5 | 一次性盘刻录 | CD-R / DVD±R / BD-R | 日志 + 校验结果 + 电脑读取结果 |
| N5.1–N5.2 | 抓轨；刻音乐 CD | 音乐 CD、CD-RW | 文件比对 / 听感 + 日志 |

---

## 附录 A：常用 Sense 码（SK/ASC/ASCQ）与中文提示

| SK/ASC/ASCQ | 含义 | App 行为 |
|---|---|---|
| 02/04/01 | 正在就绪 | 继续轮询 |
| 02/3A/00、01、02 | 无盘（托盘关 / 托盘开） | 显示"请放入光盘" |
| 06/28/00 | 介质可能已更换 | 重新识别 |
| 06/29/xx | 上电 / 复位 | 会话中途出现 → 疑似供电问题 |
| 03/11/xx | 无法恢复的读错误（05 L-EC、06 CIRC） | 重试 → 补零或静音 + 坏扇区记录 |
| 05/20/00 | 不支持的命令 | 降级处理 + 记录 |
| 05/21/00 | LBA 越界 | 检查大小判定 |
| 05/24/00 | CDB 字段无效 | 记录（多为实现问题） |
| 05/30/02 | 无法读取：格式不兼容 | 提示盘片 / 光驱不兼容 |
| 05/64/00 | 该轨道模式下非法（如对音轨发 READ(10)） | 改用 READ CD |
| 05/6F/00–05 | 版权保护：认证失败 / 密钥不存在 / 未建立 / 未认证读加密扇区 / 地区不符 / 地区设置次数 | P3 对应中文提示 |

---

## 7. 决策与进度记录

### 7.1 决策（你说"在云端自行开发"之后，按第 6 节的默认项执行；有异议随时可改）

| # | 事项 | 当前做法 |
|---|---|---|
| 1 | 构建与安装 | 你暂时没有电脑 → 全部在云端构建；APK 通过对话发送；测试签名放在仓库里，可直接覆盖安装 |
| 2 | 许可证 | 推迟到 P2 第一次链接 GPL 库（libdvdread）时再加 LICENSE，届时再跟你确认（默认 GPL-3.0-or-later） |
| 3 | P2 解析方式 | 用 libudfread / libdvdread / libbluray（到 P2 时生效） |
| 4 | 阶段顺序 | P3（加密盘）在 P4（刻录）之前，按你原定顺序 |
| 5 | 解密组件产出 | 你没有电脑编译 → 由我在容器里**单独**编译成独立 .so 文件，**不进 APK**，在对话里单独发给你，你在 App 里用"导入组件"导入 |
| 6 | 默认分支 | 仓库原本为空，`claude/jolly-tesla-c2qlm6` 已成为默认分支；以后想改成 `main` 告诉我 |
| 7 | 包名 | `io.github.felixchang67.diskandroid` |
| 9 | 曲目信息来源（你在 N1.5 之后提出：日本 CD 基本没有 CD-TEXT，参考 MusicTag 从网易云等平台同步） | 按光盘目录在 MusicBrainz 精确识别（日文原名、发行版本、封面），再到网易云音乐找时长吻合的同一张专辑补中文译名、歌词（含中文翻译）和封面；都查不到时读盘上条码再查，或手动搜索。gnudb（CDDB）要求用开发者邮箱注册应用，未经你同意不使用你的邮箱，所以暂不接入 |
| 8 | 日本盘优先（你在 N1.3 之后提出） | 你主要用日本发行的 CD / DVD / BD（音乐专辑、演唱会 DVD/BD、特典盘）→ 兼容性按日本盘优先（见 7.6）；**N1.5（音乐 CD 播放）提前到 N1.4（读成 ISO）之前** |

### 7.2 进度

| 日期 | 节点 | 版本 | 内容 | 状态 |
|---|---|---|---|---|
| 2026-11-09 | P0 + N1.1 | 0.1.0-N1.1（versionCode 1） | 工程骨架（Gradle 9.9 / AGP 9.5 / Kotlin 2.4 / Compose）、日志系统（内存环形缓冲 + 会话文件 .log/.jsonl + logcat，崩溃与上次退出原因记录，复制/导出/分享）、USB 枚举 / 热插拔 / 权限、BOT 接口选择（兼容 UAS 备用设置与子类 02/05）、原始描述符解析、usbfs ioctl 小 JNI（链路速度、能力、内核驱动、CLEAR_HALT、RESET）、"连接测试"（占用接口 + Get Max LUN） | ✅ 真机通过（见下） |
| 2026-11-09 | N1.2 | 0.2.0-N1.2（versionCode 2） | Bulk-Only Transport（CBW/CSW、自动 REQUEST SENSE、STALL/0 长度 CSW/跳过数据阶段/Phase Error 处理、复位恢复，参照 BOT 规范与 Linux usb-storage）；命令日志（CDB/sense hex + T10 官方 ASC/ASCQ 表解码 + 中文提示，轮询去重）；只读白名单守卫；INQUIRY、TUR、GET CONFIGURATION、MODE SENSE 2Ah、GESN、MECHANISM STATUS、REPORT KEY（RPC）、弹出；光驱会话与盘片状态机（UNIT ATTENTION、两次轮询间换盘、会话中途复位 → 供电提示）；自动连接 / 拔出释放；插入过滤器收窄到 0411:036D；诊断信息含原始响应 hex、上一个会话的警告/错误 | ✅ 真机通过（见 7.4） |
| 2026-11-09 | N1.3 | 0.3.0-N1.3（versionCode 3） | 盘片信息：READ CAPACITY、READ DISC INFORMATION、READ TOC 格式 0/1/2、READ TRACK INFORMATION、READ DISC STRUCTURE（DVD 物理/版权、BD DI）、GET PERFORMANCE；变长响应一律先读头再按确切长度读；CD-Extra 用完整 TOC 的区段 lead-out 定第 1 区段最后一轨结尾；DVD 盘片地区与光驱 RPC 不符时提前提示；守卫拦截密钥类盘片结构格式与写入性能类型；修正 N1.2 发现的问题（托盘事件重复、Lock 位误读、MODE SENSE STALL、AACS 位定义、读完整 INQUIRY）；用 N1.2 真机原始响应建立回放测试 | ✅ 真机通过（见 7.5） |
| 2026-11-10 | N1.6（补充） | 0.6.1-N1.6（versionCode 7） | 你要求的 gapless 切歌：自然播到下一首本来就是采样级连续，现在两首都带预加重时去加重滤波器也不在交界处清零；播放中手动切歌（下一首 / 上一首 / 点曲目 / 拖动）不再"停下 → 清空 → 等光驱 → 再播"，而是旧的声音一直播到新位置的数据就绪再直接接上（接缝只做 10 ms 平滑防"啪"声，没有静音、没有长交叉淡化）；缓冲满时趁光驱空闲预读下一首开头约 2.6 秒，按"下一首"不用等寻道；输出缓冲从 0.5 秒减到 0.2 秒，切歌响应更快 | 等待真机测试 |
| 2026-11-10 | N1.4 + N1.6 | 0.6.0-N1.6（versionCode 6） | 读成 ISO：READ(10)（对照 Linux sr.c）；镜像覆盖 LBA 0 到 READ CAPACITY，音轨 / 轨道与区段之间的空隙补零；两遍读取（第 1 遍大块、遇错跳过，第 2 遍逐扇区重试，补读回来的写回原位）；CD 轨道末尾读不出的 2 个 run-out 扇区算间隙；输出 .iso + .log 报告（含 ISO 9660 卷标 / 卷大小核对、MD5 / SHA-1 / CRC32）+ ddrescue 格式 .mapfile；SAF 选目录并持久授权、检查剩余空间、4 GB 写入失败提示 FAT32；前台服务 connectedDevice + 唤醒锁、通知栏进度与取消；取消 / 取盘 / 断开时保留已读部分并在 mapfile 标出；CSS 加密 DVD 拒绝并说明（需阶段 3 认证），意外遇到 05/6F 立即停止；读盘与播放互斥。整合：新放入的音乐 CD 显示"2 秒后播放"（取消 / 立即播放，可关闭），自动播放 1 秒淡入、手动点歌 30 ms 淡入；读不出的扇区静音前后各 5 ms 淡出 / 淡入；播放、读盘或起转时 USB 断开提示检查供电；日志补充识别结果、歌词、封面 | 等待真机测试 |
| 2026-11-10 | N1.5b | 0.5.0-N1.5b（versionCode 5） | 修 N1.5 真机发现的读盘卡顿（批量传输按 16 KB 分块、JNI 直接取 errno、ENOMEM 原地重试、非 STALL 错误直接复位而不是误读 CSW；复位后重读整块而不是静音；播放缓冲加到约 10 秒）；在线识别：MusicBrainz 光盘 ID（与 libdiscid 测试向量一致）/ TOC 模糊匹配 / 盘上条码（READ SUB-CHANNEL MCN），网易云音乐补中文译名、歌曲 ID、封面，歌词取网易云（含中文翻译）、LRCLIB 备选；候选切换与手动搜索；本机缓存；通知栏 / 锁屏显示在线曲名与封面 | ✅ 真机通过（见 7.9） |
| 2026-11-09 | N1.5（提前） | 0.4.0-N1.5（versionCode 4） | 音乐 CD 播放：READ CD（CD-DA，2352 字节/扇区）按块读取，整块重试 → 逐扇区重试 → 读不出的扇区静音并记录，每块 6 秒时间预算（划伤盘不卡死）；盘被取出 / 光驱断开立即停止；导入区 CD-TEXT（READ TOC 格式 5）：CRC 校验、双字节、TAB 省略、多语言块，**日文块优先，MS-JIS 用 windows-31j 解码，字符集标错的日本盘自动按 Shift-JIS 回退**；字节序自动检测（思路同 cdparanoia）+ 手动切换；预加重音轨做 50/15 µs 去加重（SoX deemph 参数，按定义逐点验证误差 < 0.07 dB）；播放时 SET CD SPEED 降到约 4 倍速，停止后恢复；AudioTrack 非阻塞输出、约 4 秒缓冲、跨轨无缝；上一首 / 下一首 / 暂停 / 拖动；音频焦点、拔耳机暂停、唤醒锁；前台服务 + MediaSession（通知栏 / 锁屏 / 蓝牙按键）。顺带修 N1.3：分配长度 4 字节对齐、完整 TOC 逐条校验、音乐 CD 显示时长、解出桥接芯片里的机芯型号 | ✅ 真机能播放（见 7.7，发现读盘卡顿，N1.5b 已修） |

### 7.3 N1.1 真机结果（2026-11-09）

- 平板实际系统：**Android 16（API 36），HyperOS 3.0（OS3.0.305.0.WOTCNXM）**，XRing O1，内核 6.6（GKI，4 KB 页）。App 仍以 targetSdk 35 运行。
- 光驱：BUFFALO Optical Drive，**VID 0411 / PID 036D**，bcdUSB 3.10，**SuperSpeed 5 Gbit/s**（内核确认）。
- 只有一个接口 0（08/06/50 BOT），**没有 UAS 备用设置**；bulk IN 0x81 / OUT 0x02，包长 1024，MaxBurst 15。
- 平板内核原本给它绑定 **usb-storage** 驱动，claimInterface(force) 36 ms 内成功断开并占用。
- Get Max LUN = 0（单 LUN）。
- 配置描述符声明"自供电、0 mA"——桥接芯片的写法，**不能据此判断耗电**，供电问题只能靠掉线/复位事件判断。
- usbfs 能力含 NO_PACKET_SIZE_LIM 与 BULK_SCATTER_GATHER：大块 bulk 传输没有 16 KB 限制。
- HyperOS 在后台划掉 App 会**强制停止**（SwipeUpClean）——读盘/刻录等长任务前要提示用户不要划掉，或在最近任务里锁定 App。

### 7.4 N1.2 真机结果（2026-11-09，会话 session-20261109-235130）

- **INQUIRY 只给出桥接芯片的身份**："BUFFALO / Optical Drive"，固件版本为空；真实机芯型号被隐藏。ADDITIONAL LENGTH = 5Bh（共 96 字节），N1.3 起读取完整 INQUIRY 看厂商自定义区。
- 物理接口：**Serial ATAPI**（USB 桥后面是 SATA 光驱）；光驱序列号 MOAOCCI1742；固件日期 2021-06-21（原始 "212106211333"）。
- 支持 18 种 profile：CD-ROM/R/RW、DVD-ROM/±R/±RW/RAM/±R DL、BD-ROM/R(SRM/RRM)/RE，以及 0002h "Removable disk"。42 个特性。
- CD：CD-Text、C2 错误指针、CD-DA 精确流、R-W 子通道（不支持去交错纠错的 R-W）、ISRC/UPC；**缓存 4064 KB**；CD 最大读速 4234 kB/s（24x）。
- **DVD CSS 版本 1；AACS 版本 1，2 个 AGID，支持总线加密、可读取光驱证书、绑定 nonce**（阶段 3 相关）。
- **DVD 地区码：RPC-II，已设置为地区 2，用户还可修改 4 次，厂商重置 4 次**。CSS 加密且不含 2 区的 DVD 会被光驱拒绝读取加密扇区（6F/04），App 不会修改地区码。
- GESN 正常（媒体事件 + 托盘状态）。就绪用时：CD 14.3 s，DVD 5.6 s，BD 7.8 s。
- BOT：405 条命令，1 次 STALL，0 超时，0 复位。STALL 出现在 MODE SENSE（分配 256、实际 76 字节）：桥接芯片发完短数据后 STALL bulk-in，再发 CSW（residue 176）——这是 BOT 规范允许的"Hi > Di"处理方式，已按规范清 HALT 并重读 CSW。N1.3 起变长响应先读头再按确切长度读，避开这一步。
- 问题（N1.3 已修）：一次开/关托盘记两条事件；Removable Medium 特性的 Lock 位被误显示为"已锁定"（实为"支持锁定"）。

### 7.5 N1.3 真机结果（2026-11-10，会话 session-20261110-002621）

- **光驱机芯确认**：完整 96 字节 INQUIRY 的附加区里有字节对调的 ATA 型号 "-DERB 4UN0" → **HL-DT-ST BD-RE BU40N**（Ultra Slim 机芯），固件 **BN14**（只在长响应里出现）。
- 三张盘全部读出，无错误：
  - 音乐 CD：10 轨、41:13，单区段，lead-out LBA 185437；
  - 日本 DVD：DVD-ROM 单层（数据约 2.0 GB），**CSS 加密，2 区**（与光驱地区 2 一致，可以播放）；
  - BD-ROM：23.88 GB，类型 "BDO"。
- GET PERFORMANCE：CD 24x；**DVD / BD 视频盘只报 2 倍速**——光驱固件对视频盘的读速限制（riplock），播放够用；以后读成 ISO 会比较慢（按 2 倍速算，满容量单层 DVD 约半小时，25 GB 的 BD 约 45 分钟）。
- **发现一个桥接芯片的问题**：READ TOC 格式 2（完整 TOC）用奇数分配长度 147 时，只有前 128 字节正确，后面是缓冲区里的旧数据（CSW 却报告成功、residue 0），于是轨道 9、10 丢失，还冒出一个假的"区段 0"。其余响应（92、34、36、492、76 字节…）都是 4 的倍数或较短，没有问题。
  - N1.5 起：所有变长读取的分配长度向上取 4 的倍数（Linux libata 对 ATAPI 也做同样的补齐），只取声明长度内的字节；完整 TOC 逐条校验（区段号、TNO、POINT、时间范围），丢弃坏条目并提示缺少哪些轨道；CD-TEXT 每包有 CRC。
  - 这次的回放测试用的就是这 147 字节原始数据。
- 其他小修：音乐 CD 的"容量"按 2048 字节/块算会误导，改为显示时长与 CD-DA 数据量；诊断信息里超长的原始响应（BD DI 4100 字节）只显示前 1 KB。

### 7.6 日本盘兼容（你在 N1.3 之后提出的优先事项）

| 盘 | 要点 | 处理 | 节点 |
|---|---|---|---|
| 音乐 CD | CD-TEXT 多为日文块（MS-JIS 双字节）+ 英文 / 罗马字块 | 日文块优先，windows-31j 解码（含 ①、Ⅱ、～ 等 Windows 扩展字符）；字符集标成 ISO-8859-1 但内容其实是 Shift-JIS 的盘自动回退；英文块作为副标题 | N1.5 ✅ 已实现 |
| 音乐 CD | 1980 年代的 CD 常有**预加重**（TOC 控制位） | 按轨道控制位做 50/15 µs 去加重，可在设置里关闭 | N1.5 ✅ |
| 音乐 CD | 2002–2004 年的 **CCCD**（防复制 CD，例如 avex、东芝 EMI） | 本质是故意带错误的多区段盘：用光驱的 CD-DA 读取 + 坏扇区静音处理，播放不会卡死；如果你手里有，请测一下 | N1.5 测试 |
| 音乐 CD | 加强版 CD（Enhanced CD，音轨 + 电脑数据） | 只播放第 1 区段音轨，按区段 lead-out 截止 | N1.5 ✅ |
| 音乐 CD | SACD 混合盘 | 光驱只能读 CD 层，按普通 CD 播放 | — |
| DVD | 日本是 **2 区**，你的光驱也是 2 区 | 直接可读（N1.3 已确认）；不修改地区码 | P3 |
| 演唱会 DVD | 音频多为 **LPCM**（无损）、AC-3、DTS | Media3 自带 LPCM；AC-3 / DTS 用 FFmpeg 音频解码器（P2 的 FFmpeg 构建包含 DTS） | P2 / P3 |
| 演唱会 BD | 音频多为 **LPCM、Dolby TrueHD、DTS-HD MA**；视频常为 1080i | FFmpeg 音频解码器解 TrueHD / DTS-HD MA；1080i 需要去隔行，P2 起评估 | P2 / P3 |
| BD | 日本是 **A 区**；BD 地区由播放器（软件）判断，不是光驱 | libbluray 的播放器地区设为 A、语言默认日语（PSR 20 / 16 / 17） | P3 |
| 商业 DVD / BD | 基本都有 CSS / AACS；BD+ 在日本盘上很少见 | 按原计划：外部解密组件 + 边读边播，不导出解密内容 | P3 |
| 特典盘 | 常见是 DVD-Video、BDMV 或电脑数据盘（ISO9660 / UDF，日文文件名） | P2 文件浏览按 Joliet / UDF 处理日文文件名；ISO9660 原始文件名按 Shift-JIS 回退显示 | P2 |

### 7.7 N1.5 真机结果（2026-11-10，会话 session-20261110-013235）

- 播放正常：字节序自动检测为小端；那张 CD 没有 CD-TEXT（光驱返回只有头部的空数据）。
- **读盘卡顿**：播放约 4 分钟里有 10 次 READ CD（一次 56 KB）在 1–2 ms 内失败，接着读 CSW 两次"STALL"，然后 BOT 复位、等待 6 秒，那 0.32 秒的音频被静音；其中一次是连续 5 条都失败（约 25 秒），之后又自行恢复。
  - 判断：公开 API `bulkTransfer` 只返回 -1。失败来得太快、又成串出现，很像 usbfs 为 56 KB 的单次传输在内核里分配 64 KB 连续内存失败（ENOMEM，内存碎片时常见）。这种情况下传输根本没有发出去，光驱还停在数据阶段；程序当成 STALL 去读 13 字节的 CSW，收到的却是 1024 字节的数据包（babble），于是又判成 STALL，最后复位。
  - N1.5b 的修复：
    - 新增 JNI 的 USBDEVFS_BULK，直接拿到 errno；
    - 每次传输最多 16 KB（是包长的整数倍，与 Android 9 以前公开 API 的上限相同）；
    - 遇到 ENOMEM 稍等后原地重试，设备那边不受影响；
    - 不是 STALL 的错误直接做复位恢复，不再去读 CSW；
    - 传输错误后整块重读，不再算进坏扇区的时间预算，不会把好数据静音；
    - 播放缓冲从约 4 秒加到约 10 秒，即使发生一次复位也不会断音；
    - 诊断信息里记录传输方式和 ENOMEM 重试次数，下一份日志可以验证这个判断。
- 这张 CD 用新的在线识别已经确认：**ヒトリエ《DEEPER》（2016-02-24，日本发行，通常盤，JAN 4547403043075）**——MusicBrainz 光盘 ID `beyzshJLWsTuHOEnc1BtgGwQoW0-` 精确匹配；网易云同名专辑 10 首时长全部吻合（差 0.05–0.07 秒），有中文译名和带中文翻译的同步歌词。

### 7.8 在线曲目信息（N1.5b）

| 步骤 | 服务 | 发送的内容 | 得到的内容 |
|---|---|---|---|
| 1 | 本机缓存 | — | 以前识别过的盘直接显示，不联网 |
| 2 | MusicBrainz（开放数据库，CC0） | 光盘 ID 与 TOC | 专辑、艺人、日文原名曲目、发行日期、厂牌、版本（通常盤 / 初回限定盤）、条码、封面（Cover Art Archive） |
| 3 | 光驱 READ SUB-CHANNEL | — | 盘上的 JAN 条码（MusicBrainz 没有这张盘的光盘 ID 时，按条码再查） |
| 4 | 网易云音乐（网页端接口，无需登录） | "艺人 专辑名" 搜索词、专辑 / 歌曲 ID | 时长吻合的同一张专辑：中文译名、歌曲 ID、封面；歌词（日文原文 + 中文翻译，按时间轴同步） |
| 5 | LRCLIB（开放歌词库） | 艺人、曲名、专辑、时长 | 网易云没有歌词时的备选 |
| 6 | 手动搜索 | 你输入的关键词 | 网易云 + MusicBrainz 的候选，按与 CD 的时长吻合程度排序，选用后保存 |

- 光盘 ID 算法与 MusicBrainz libdiscid 一致（官方测试向量 + 这张真机 CD 都已核对）；加强版 CD 只算音轨区段。
- MusicBrainz：每秒最多一个请求；User-Agent 写应用名、版本和项目地址，不含个人信息。
- 网易云的旧搜索接口 `/api/search/get/web` 现在返回加密结果，改用 `/api/cloudsearch/pc`。这些是非官方接口，网易云改版时可能需要跟着调整，所以都集中在 `core:meta` 的 `NetEaseClient` 里。
- 识别结果、歌词（包括"没有歌词"）、封面都缓存在本机；可以在播放设置里关闭联网。

### 7.9 N1.5b 真机结果（2026-11-10，会话 session-20261110-021351）

- **读盘卡顿已解决**：播放约 4 分钟（含多次拖动、换轨）共 1022 条 READ CD，**STALL 0、超时 0、I/O 错误 0、复位 0、重试 0、静音扇区 0**；传输方式为 usbfs ioctl、每次 ≤ 16 KB，ENOMEM 重试 0 次。上一版同样时长约 600 条里失败 10 次——分块传输后问题消失，与"大块传输在内核分配连续内存失败"的判断一致。
- 在线识别：这次是 ヒトリエ 的单曲《センスレス・ワンダー》（通常盤，3 首）。MusicBrainz 光盘 ID 精确匹配，网易云 3/3 时长吻合，补了中文译名与歌词来源；整个识别约 3 秒。
- 弹盘后播放器立即释放（"释放播放器：盘片已取出"），没有警告或错误。
- 日志里看不到歌词、封面是否成功（只记了失败）：0.6.0 起成功也会记一行。

