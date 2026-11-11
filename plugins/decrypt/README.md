# 解密组件（阶段 3）

加密的商业 DVD（CSS）和蓝光（AACS）要用解密组件才能播放。组件**不进 APK、不进仓库**：

- 编译产物在 `plugins/decrypt/out/`（已加入 .gitignore）。
- 由用户在 App 的"解密组件"卡片里导入，保存在 App 私有目录，并设为只读。

组件只用于边读边播。App 不提供解密后导出；"读成 ISO"对加密盘保持原始密文。是否合法取决于所在地的法律。

| 组件 | 上游 | 补丁 | 导出的接口 |
|---|---|---|---|
| DVD：`libdvdcss.so` | libdvdcss 1.6.0（VideoLAN，SHA-256 固定） | `patches/libdvdcss-1.6.0-host-scsi.patch` | `dvdcss_set_host` |
| 蓝光：`libaacs.so` | libaacs 0.12.0 + libgcrypt + libgpg-error | `patches/libaacs-*.patch` | `aacs_set_host` |

## 为什么要打补丁

光驱接在 USB 上，没有内核驱动，组件不能像在电脑上那样自己打开设备发认证命令。补丁加了一个"宿主接口"（`native/diskplayer/src/dc_host.h`）：

- 组件把认证命令（MMC 的 REPORT KEY / SEND KEY / READ DISC STRUCTURE）交给 App。
- App 经光驱会话发出这些命令。命令同样要过只读守卫：只放行认证用到的格式，**设置地区码（SEND KEY 06h）永远发不出去**。所有命令都写进日志。
- 组件的日志也转给 App。

加解密算法都是上游库自己的，补丁没有改动。

## 编译

```bash
bash plugins/decrypt/build.sh android-arm64 css   # → plugins/decrypt/out/android-arm64/libdvdcss.so
bash plugins/decrypt/build.sh host css            # 电脑上测试用
bash plugins/decrypt/test/run_tests.sh            # 模拟光驱：检查认证命令的格式和顺序，确认不会发 SEND KEY 06h
```
