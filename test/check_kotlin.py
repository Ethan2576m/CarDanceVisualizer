#!/usr/bin/env python3
"""
Static sanity checks for the Kotlin sources.

This machine has no JDK / Kotlin compiler, so the cloud build is the only real
compiler. These checks catch the cheap failures *before* a push:

  1. braces / parens / brackets balance (comment- and string-aware)
  2. `package` declaration matches the file's directory
  3. cross-file member access: every `dancer.x` / `fig.x` used in the renderer
     must actually exist on Dancer / Figure
  4. required imports are present

Usage:  python test/check_kotlin.py
Exit code 0 = all good.
"""

import re
import sys
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
SRC_ROOT = HERE.parent / "app" / "src" / "main" / "java"

errors = []
checked = 0


def strip_code(src: str) -> str:
    """Remove comments and string literals so bracket counting is honest."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif c == '"':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
        elif c == "'":
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_balance(path: pathlib.Path, code: str) -> None:
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack:
                errors.append(f"{path.name}: 第 {line} 行多出 '{ch}'")
                return
            opener, oline = stack.pop()
            if opener != pairs[ch]:
                errors.append(
                    f"{path.name}: 第 {line} 行 '{ch}' 与第 {oline} 行 '{opener}' 不匹配"
                )
                return
    if stack:
        opener, oline = stack[-1]
        errors.append(f"{path.name}: '{opener}' 未闭合（起始于第 {oline} 行）")


def check_package(path: pathlib.Path, raw: str) -> None:
    m = re.search(r"^\s*package\s+([\w.]+)", raw, re.M)
    if not m:
        errors.append(f"{path.name}: 缺少 package 声明")
        return
    pkg = m.group(1)
    expected = (
        str(path.parent.relative_to(SRC_ROOT)).replace("\\", ".").replace("/", ".")
    )
    if pkg != expected:
        errors.append(f"{path.name}: package '{pkg}' 与目录不符（应为 '{expected}'）")


def members_of(path: pathlib.Path, class_name: str) -> set:
    raw = path.read_text(encoding="utf-8")
    m = re.search(r"(?:data\s+)?class\s+" + class_name + r"\b", raw)
    if not m:
        return set()
    start = m.start()
    # find the class body braces and take everything from the declaration to
    # the end of the body - for a data class the primary-constructor
    # properties live in the parameter list, before the body.
    i = raw.find("{", m.end())
    if i < 0:
        return set()
    depth, j = 0, i
    while j < len(raw):
        if raw[j] == "{":
            depth += 1
        elif raw[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = raw[start:j]
    found = set(re.findall(r"\b(?:var|val)\s+(\w+)", body))
    found |= set(re.findall(r"\bfun\s+(\w+)", body))
    return found


def check_members(path: pathlib.Path, code: str, owner: str, members: set) -> None:
    for name in sorted(set(re.findall(r"\b" + owner + r"\.(\w+)", code))):
        if name not in members:
            errors.append(f"{path.name}: 引用了不存在的成员 {owner}.{name}")


REQUIRED_IMPORTS = {
    "DancerRenderer.kt": [
        "android.graphics.Canvas", "android.graphics.Color",
        "android.graphics.LinearGradient", "android.graphics.Matrix",
        "android.graphics.Paint", "android.graphics.Path",
        "android.graphics.PointF", "android.graphics.RectF",
        "android.graphics.Shader",
        "com.cardance.visualizer.dance.Dancer",
        "com.cardance.visualizer.dance.Figure",
        "com.cardance.visualizer.dance.HairStyle",
        "com.cardance.visualizer.util.lerp",
        "kotlin.math.sin", "kotlin.math.sqrt",
    ],
}


def main() -> int:
    global checked
    files = sorted(SRC_ROOT.rglob("*.kt"))
    if not files:
        print("没有找到 Kotlin 源文件")
        return 1

    dancer_members = members_of(SRC_ROOT / "com/cardance/visualizer/dance/Dancer.kt", "Dancer")
    figure_members = members_of(SRC_ROOT / "com/cardance/visualizer/dance/Skeleton.kt", "Figure")

    for path in files:
        raw = path.read_text(encoding="utf-8")
        code = strip_code(raw)
        checked += 1
        check_balance(path, code)
        check_package(path, raw)
        check_members(path, code, "dancer", dancer_members)
        check_members(path, code, "fig", figure_members)
        for imp in REQUIRED_IMPORTS.get(path.name, []):
            if f"import {imp}" not in raw:
                errors.append(f"{path.name}: 缺少 import {imp}")

    print(f"检查了 {checked} 个 Kotlin 文件")
    if errors:
        print("\n发现问题：")
        for e in errors:
            print("  -", e)
        return 1
    print("全部通过：括号配对 / package 路径 / 成员引用 / import 均正常")
    return 0


if __name__ == "__main__":
    sys.exit(main())
