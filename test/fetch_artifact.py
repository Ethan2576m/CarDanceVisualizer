#!/usr/bin/env python3
"""
Wait for a GitHub Actions run to finish, then pull the APK artifact back.

Usage:
  python test/fetch_artifact.py <repo> <run_id> <out_apk>

Proxies are explicitly disabled - the local sandbox proxy answers 502 for
github.com, so every request goes out directly.
"""

import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import zipfile
import io

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = sys.argv[1] if len(sys.argv) > 1 else "Ethan2576m/CarDanceVisualizer"
RUN_ID = sys.argv[2] if len(sys.argv) > 2 else ""
OUT = pathlib.Path(sys.argv[3] if len(sys.argv) > 3 else "app-debug.apk")

# No proxy for github: the sandbox proxy 502s on it.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(path):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}{path}",
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "cardance-fetch",
        },
    )
    with OPENER.open(req, timeout=40) as resp:
        return json.loads(resp.read().decode())


def wait_run(run_id, limit=900):
    deadline = time.time() + limit
    while time.time() < deadline:
        run = api(f"/actions/runs/{run_id}")
        status = run.get("status")
        concl = run.get("conclusion")
        print(f"  status={status} conclusion={concl}", flush=True)
        if status == "completed":
            return concl
        time.sleep(12)
    return None


def main():
    if not TOKEN:
        print("缺少 GH_TOKEN 环境变量")
        return 2

    print(f"等待 run {RUN_ID} 完成 …", flush=True)
    concl = wait_run(RUN_ID)
    if concl != "success":
        print(f"构建未成功：{concl}")
        return 1

    arts = api("/actions/artifacts").get("artifacts", [])
    target = None
    for a in arts:
        if not a.get("expired") and a.get("name") == "car-dance-apk":
            target = a
            break
    if target is None:
        print(f"没有找到 car-dance-apk 制品，现有：{[a['name'] for a in arts]}")
        return 1

    print(f"下载制品 id={target['id']} size={target['size_in_bytes']} …", flush=True)
    url = f"https://api.github.com/repos/{REPO}/actions/artifacts/{target['id']}/zip"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "cardance-fetch",
        },
    )
    with OPENER.open(req, timeout=180) as resp:
        blob = resp.read()

    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        names = [n for n in z.namelist() if n.lower().endswith(".apk")]
        if not names:
            print(f"制品内没有 apk：{z.namelist()}")
            return 1
        data = z.read(names[0])

    OUT.write_bytes(data)
    print(f"已保存 {OUT}  ({len(data)} 字节)  内部条目 {names[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
