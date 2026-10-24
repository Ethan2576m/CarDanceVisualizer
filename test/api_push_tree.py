#!/usr/bin/env python3
"""
Push the full local HEAD tree through the GitHub git-data API.

Unlike api_push.py (staged-diff only), this replays the *entire* tree diff
between the remote branch tip and local HEAD, so nothing is missed when the
local commit was created before this script ran.

Steps: remote tree listing vs `git ls-tree -r HEAD` -> blobs -> tree
(base_tree = remote tree) -> commit (parent = remote tip) -> ref update.
"""

import base64
import json
import subprocess
import sys
import urllib.request

REPO = "Ethan2576m/CarDanceVisualizer"
BRANCH = "main"


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()


def token():
    out = subprocess.run(
        "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill",
        shell=True, capture_output=True, text=True
    ).stdout
    for ln in out.splitlines():
        if ln.startswith("password="):
            return ln.split("=", 1)[1]
    raise SystemExit("凭据管理器里没有 github.com 的令牌")


TOK = token()


def api(method, path, body=None):
    url = f"https://api.github.com/repos/{REPO}/{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOK}")
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def local_tree():
    out = sh("git ls-tree -r HEAD")
    m = {}
    for ln in out.splitlines():
        meta, path = ln.split("\t", 1)
        mode, otype, sha = meta.split()
        if otype == "blob":
            m[path] = (mode, sha)
    return m


def main():
    msg = sys.argv[1] if len(sys.argv) > 1 else "chore: api push full tree"

    ref = api("GET", f"git/ref/heads/{BRANCH}")
    parent_sha = ref["object"]["sha"]
    parent = api("GET", f"git/commits/{parent_sha}")
    parent_tree_sha = parent["tree"]["sha"]
    print(f"远端 {BRANCH} = {parent_sha[:7]}")

    remote = {}
    tr = api("GET", f"git/trees/{parent_tree_sha}?recursive=1")
    for e in tr.get("tree", []):
        if e["type"] == "blob":
            remote[e["path"]] = e["sha"]
    print(f"远端树 {len(remote)} 个文件")

    local = local_tree()
    print(f"本地树 {len(local)} 个文件")

    changed = [p for p, (mode, sha) in local.items() if remote.get(p) != sha]
    deleted = [p for p in remote if p not in local]
    print(f"待上传 {len(changed)} 个变更文件, {len(deleted)} 个删除")

    entries = []
    for p in changed:
        mode, _ = local[p]
        with open(p, "rb") as fh:
            raw = fh.read()
        blob = api("POST", "git/blobs", {
            "content": base64.b64encode(raw).decode(),
            "encoding": "base64",
        })
        entries.append({"path": p, "mode": mode, "type": "blob", "sha": blob["sha"]})
        print(f"  blob {p} -> {blob['sha'][:7]}")
    for p in deleted:
        entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})
        print(f"  del  {p}")

    if not entries:
        print("远端已是最新，无需推送")
        return 0

    new_tree = api("POST", "git/trees", {"base_tree": parent_tree_sha, "tree": entries})
    commit = api("POST", "git/commits", {
        "message": msg,
        "tree": new_tree["sha"],
        "parents": [parent_sha],
    })
    print(f"新提交 {commit['sha'][:7]}")

    api("PATCH", f"git/refs/heads/{BRANCH}", {"sha": commit["sha"], "force": False})
    print(f"✅ {BRANCH} 已更新到 {commit['sha'][:7]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
