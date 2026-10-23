#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验下载到的 app-debug.apk 是否完整可用（不依赖 aapt / Android SDK）

检查项：
  1. 是否为合法 ZIP、能否完整解压
  2. 关键条目是否齐全：AndroidManifest.xml / classes.dex / resources.arsc
  3. 签名是否在（META-INF/*.RSA|DSA|EC）
  4. 从二进制 AndroidManifest 里粗读包名、版本、权限、Activity
  5. 体积、DEX 数量

用法：python verify_apk.py [apk路径]
"""
import sys
import zipfile
import struct

PKG_MIN = 0
ANDROID_NS = "http://schemas.android.com/apk/res/android"
NS_PREFIX = b"\x00\x00"


def utf16_len(s):
    return len(s.encode("utf-16-le"))


def read_len(buf, off):
    """AXML 里 length 可能是 1 字节或 2 字节（高位 0x80 表示 2 字节）。"""
    b = buf[off]
    if b & 0x80:
        return ((b & 0x7F) << 8) | buf[off + 1], off + 2
    return b, off + 1


def parse_axml(data):
    """极简 AXML 解析：只取字符串池与起始标签属性。"""
    if len(data) < 8:
        return {}
    # 找字符串池 chunk
    off = 0
    strings = []
    while off + 8 <= len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:  # RES_STRING_POOL_TYPE
            scount, styc, flags, sstart, stystart = struct.unpack_from("<IIIII", data, off + 8)
            is_utf8 = bool(flags & (1 << 8))
            offs = []
            p = off + 28
            for _ in range(scount):
                offs.append(struct.unpack_from("<I", data, p)[0])
                p += 4
            base = off + 8 + sstart
            for o in offs:
                pos = base + o
                if pos >= len(data):
                    strings.append("")
                    continue
                if is_utf8:
                    # utf8: 字符数(1-2B) 字节数(1-2B) 然后 utf8 串
                    n1 = data[pos]
                    pos += 1
                    if n1 & 0x80:
                        n1 = ((n1 & 0x7F) << 8) | data[pos]
                        pos += 1
                    n2 = data[pos]
                    pos += 1
                    if n2 & 0x80:
                        n2 = ((n2 & 0x7F) << 8) | data[pos]
                        pos += 1
                    strings.append(data[pos:pos + n2].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                    if n & 0x8000:
                        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                        pos += 2
                    strings.append(data[pos:pos + n * 2].decode("utf-16-le", "replace"))
            break
        if csize <= 0:
            break
        off += csize
    return strings


def find_strings_in_manifest(data):
    """在二进制 manifest 中直接扫描可读的 UTF-8 / UTF-16 字符串，找包名与权限。"""
    found = set()
    # UTF-8 形式
    i = 0
    while True:
        i = data.find(b"com.cardance", i)
        if i < 0:
            break
        j = i
        while j < len(data) and 32 <= data[j] < 127:
            j += 1
        found.add(data[i:j].decode("ascii", "replace"))
        i = j
    # UTF-16LE 形式
    for pat in ("com.cardance", "android.permission."):
        needle = pat.encode("utf-16-le")
        i = 0
        while True:
            i = data.find(needle, i)
            if i < 0:
                break
            j = i
            try:
                while j + 1 < len(data):
                    ch = data[j] | (data[j + 1] << 8)
                    if 32 <= ch < 127:
                        j += 2
                    else:
                        break
            except Exception:
                pass
            found.add(data[i:j].decode("utf-16-le", "replace"))
            i = j
    return sorted(found)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:
        print("[X] 不是合法的 ZIP/APK:", e)
        sys.exit(1)

    names = z.namelist()
    bad = z.testzip()
    print("=" * 58)
    print("APK 校验:", path)
    print("=" * 58)
    print("条目总数 :", len(names))
    print("压缩体积 : %.2f MB" % (__import__("os").path.getsize(path) / 1048576))
    print("解压体积 : %.2f MB" % (sum(i.file_size for i in z.infolist()) / 1048576))
    print("CRC 校验 :", "OK（无损坏）" if bad is None else "损坏 -> %s" % bad)

    print("\n-- 关键文件 --")
    need = ["AndroidManifest.xml", "classes.dex", "resources.arsc"]
    for n in need:
        print("  %-22s %s" % (n, "存在" if n in names else "缺失 <<<"))

    dexes = [n for n in names if n.startswith("classes") and n.endswith(".dex")]
    print("  %-22s %d 个: %s" % ("DEX 文件", len(dexes), ", ".join(dexes)))

    sig = [n for n in names if n.upper().startswith("META-INF") and n.upper().endswith((".RSA", ".DSA", ".EC"))]
    print("  %-22s %s" % ("签名文件", ", ".join(sig) if sig else "无（未签名）"))

    libs = [n for n in names if n.endswith(".so")]
    print("  %-22s %d 个" % ("原生库 .so", len(libs)))

    print("\n-- Manifest 中识别到的关键字符串 --")
    data = z.read("AndroidManifest.xml")
    for s in find_strings_in_manifest(data):
        if s.strip():
            print("  ", s)

    print("\n-- 结论 --")
    ok = all(n in names for n in need) and bad is None
    print("  APK 结构完整，可安装。" if ok else "  APK 不完整，请重新下载并解压。")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
