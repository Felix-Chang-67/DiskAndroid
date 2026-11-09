#!/usr/bin/env python3
"""从 T10 官方 asc-num.txt 生成 Kotlin 的 ASC/ASCQ 表（只保留适用于 CD/DVD 设备的条目，即 R 列）。

用法：
  curl -sSL -o asc-num.txt https://www.t10.org/lists/asc-num.txt
  python3 -I tools/gen_asc_table.py asc-num.txt > core/scsi/src/main/kotlin/io/github/felixchang67/diskandroid/core/scsi/AscTable.kt
"""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="latin-1").read()
m = re.search(r"as of (.+)", text)
as_of = m.group(1).strip() if m else "unknown"

entries = []
for line in text.splitlines():
    mm = re.match(r"^([0-9A-F]{2})h/([0-9A-F]{2})h", line)
    if not mm:
        continue
    flags = line[9:22]
    desc = line[24:].strip()
    if len(flags) < 5 or flags[4] != "R":   # 第 5 列 = R：C/DVD Device (MMC-6)
        continue
    asc, ascq = int(mm.group(1), 16), int(mm.group(2), 16)
    entries.append(((asc << 8) | ascq, desc))

def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'

print("// 由 tools/gen_asc_table.py 根据 T10 asc-num.txt 自动生成，请勿手改。")
print(f"// 来源：https://www.t10.org/lists/asc-num.txt（as of {as_of}），仅包含适用于 C/DVD 设备（MMC-6，R 列）的 {len(entries)} 条。")
print("package io.github.felixchang67.diskandroid.core.scsi")
print()
print("internal object AscTable {")
print("    /** 键为 (ASC shl 8) or ASCQ。 */")
print("    val entries: Map<Int, String> = hashMapOf(")
for key, desc in entries:
    print(f"        0x{key:04X} to {kstr(desc)},")
print("    )")
print("}")
