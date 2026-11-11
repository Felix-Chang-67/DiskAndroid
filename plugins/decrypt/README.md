# 解密组件（阶段 3）

加密的商业 DVD（CSS）和蓝光（AACS）要用解密组件才能播放。组件**不进 APK、不进仓库**：

- 编译产物在 `plugins/decrypt/out/`（已加入 .gitignore）。
- 由用户在 App 的"解密组件"卡片里导入，保存在 App 私有目录，并设为只读。

组件只用于边读边播。App 不提供解密后导出；"读成 ISO"对加密盘保持原始密文。是否合法取决于所在地的法律。

| 组件 | 上游 | 补丁 | 导出的接口 |
|---|---|---|---|
| DVD：`libdvdcss.so` | libdvdcss 1.6.0（VideoLAN，SHA-256 固定） | `patches/libdvdcss-1.6.0-host-scsi.patch` | `dvdcss_set_host` |
| 蓝光：`libaacs.so` | libaacs 0.12.0（VideoLAN）+ libgcrypt 1.12.4 + libgpg-error 1.61（GnuPG，核对过发布签名），SHA-256 固定 | `patches/libaacs-0.12.0-host-scsi.patch` | `aacs_set_host` |

## 为什么要打补丁

光驱接在 USB 上，没有内核驱动，组件不能像在电脑上那样自己打开设备发认证命令。补丁加了一个"宿主接口"（`native/diskplayer/src/dc_host.h`）：

- 组件把认证命令（MMC 的 REPORT KEY / SEND KEY / READ DISC STRUCTURE）交给 App。
- App 经光驱会话发出这些命令。命令同样要过只读守卫：只放行认证用到的格式，**设置地区码（SEND KEY 06h）永远发不出去**。所有命令都写进日志。
- 组件的日志也转给 App。

加解密算法都是上游库自己的，补丁没有改动。

### libaacs 补丁的细节

- `src/file/mmc_device_linux.c` 换成"经宿主发命令"的实现：没有注册宿主就**完全不碰光驱**（Linux 的 ioctl 后端不编进去）。
  libbluray 按流读盘时没有设备路径，`mmc_open` 不再因为路径为空而放弃。
- 日志转给 App。密钥、证书、命令数据都是长十六进制串：连续 16 个以上十六进制字符一律换成 `<...>`，只保留盘 ID（查 KEYDB 用）。
- 只导出 `aacs_*`（版本脚本）；libgcrypt、libgpg-error 静态链接，符号不导出。

### 在 App 里怎么用

- 导入的组件保存为 `files/plugins/aacs/libaacs-<哈希>.so.0`：libbluray 按 `LIBAACS_PATH` + `.so.0` 加载它。
- KEYDB.cfg 保存为 `files/aacs-config/aacs/KEYDB.cfg`（`XDG_CONFIG_HOME`）；libaacs 算出的密钥缓存在 `cache/aacs-cache`
  （`XDG_CACHE_HOME`）。删除组件或 KEYDB 时缓存一起删。
- 打开盘时 libaacs 按顺序试：KEYDB 里这张盘的 VUK → 缓存 → 用处理密钥 / 设备密钥算媒体密钥，再向光驱认证读卷标识
  （要 KEYDB 里有可用的主机证书，较新的光驱会拒绝已吊销的证书）。
- 不支持 AACS 2.0（4K UHD，index.bdmv 版本 0300）和 BD+，遇到直接提示。

## 编译

```bash
bash plugins/decrypt/build.sh android-arm64 all   # → plugins/decrypt/out/android-arm64/libdvdcss.so、libaacs.so
bash plugins/decrypt/build.sh host all            # 电脑上测试用
bash plugins/decrypt/test/run_tests.sh            # 模拟光驱：CSS 认证命令的格式和顺序、不会发 SEND KEY 06h；
                                                  # AACS：KEYDB 里的 VUK / 媒体密钥 + 卷标识、密钥缓存位置、
                                                  # GET CONFIGURATION / REPORT KEY 的格式、错误码、日志打码
bash native/diskplayer/test/run_host_tests.sh     # 端到端：libbluray + 组件解密一张 AACS 加密的测试蓝光并播放
```

测试用的加密蓝光由 `native/diskplayer/test/make_bd.py --aacs` 生成，密钥是测试里随便定的，不是任何真盘的。
