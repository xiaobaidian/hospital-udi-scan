#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API (Git Data) 推送本地改动到远程 main 分支，触发 Actions 构建。
沙箱 git 智能 HTTP 协议流被截断，故用 REST API 重建 commit 树推送。
用法：python3 push_commit.py "<token>" "<commit message>"
"""
import sys
import os
import json
import base64
import subprocess

REPO = "xiaobaidian/hospital-udi-scan"
BRANCH = "main"
API = "https://api.github.com"


def api(method, path, token, data=None):
    """用 curl 子进程调 GitHub REST API，返回 (json, http_code)。
    沙箱 urllib TLS 不稳（UNEXPECTED_EOF），改用 curl 子进程；网络间歇失败则重试。"""
    import subprocess
    import tempfile
    import time
    url = API + path
    code = 0
    last_json = {"raw": ""}
    for attempt in range(3):
        cmd = ["curl", "-sS", "-L", "-k", "--retry", "5", "--retry-all-errors",
               "--retry-delay", "3", "--retry-max-time", "120", "--max-time", "60",
               "-X", method, url,
               "-H", f"Authorization: token {token}",
               "-H", "Accept: application/vnd.github+json",
               "-H", "User-Agent: workbuddy-push",
               "-w", "\n__HTTP__%{http_code}"]
        if data is not None:
            tf = tempfile.NamedTemporaryFile(mode="w", suffix=".json",
                                             delete=False, encoding="utf-8")
            tf.write(json.dumps(data))
            tf.close()
            cmd += ["-H", "Content-Type: application/json",
                    "--data-binary", f"@{tf.name}"]
            try:
                proc = subprocess.run(cmd, capture_output=True)
            finally:
                os.unlink(tf.name)
        else:
            proc = subprocess.run(cmd, capture_output=True)
        out = proc.stdout.decode("utf-8", "replace")
        if "__HTTP__" in out:
            body, _, code_s = out.rpartition("__HTTP__")
            code = int(code_s.strip() or 0)
        else:
            body, code = out, proc.returncode
        try:
            last_json = json.loads(body)
        except Exception:
            last_json = {"raw": body}
        if code in (200, 201) or (method == "PATCH" and code == 200):
            return last_json, code
        # 非成功：等待后重试
        time.sleep(2 + attempt * 2)
    return last_json, code


def get_changed_files():
    """返回 (modified/added 文件路径列表, deleted 列表)。"""
    root = os.path.dirname(__file__)
    out = subprocess.check_output(
        ["git", "status", "--porcelain", "-z"], cwd=root
    ).decode("utf-8", "replace")
    added, deleted = [], []
    entries = [e for e in out.split("\0") if e]
    i = 0
    while i < len(entries):
        line = entries[i]
        if not line:
            i += 1
            continue
        code = line[:2]
        path = line[3:]
        full = os.path.join(root, path)
        if code in (" D", "D ", "DD"):
            deleted.append(path)
        elif code.startswith("??"):
            # 未跟踪：可能是目录，递归展开内部文件
            if os.path.isdir(full):
                for dp, _, fns in os.walk(full):
                    for fn in fns:
                        added.append(os.path.relpath(os.path.join(dp, fn), root).replace("\\", "/"))
            elif os.path.isfile(full):
                added.append(path)
        else:
            # 跟踪文件的修改/新增
            if os.path.isfile(full):
                added.append(path)
            elif not os.path.exists(full):
                deleted.append(path)
        i += 1
    return added, deleted


def main():
    if len(sys.argv) < 3:
        print("用法: python3 push_commit.py <token> <message>")
        sys.exit(1)
    token = sys.argv[1]
    msg = sys.argv[2]

    added, deleted = get_changed_files()
    if not added and not deleted:
        print("无改动，跳过")
        sys.exit(0)
    print(f"改动文件: +{len(added)} -{len(deleted)}")

    # 1) 获取当前分支引用
    ref, code = api("GET", f"/repos/{REPO}/git/refs/heads/{BRANCH}", token)
    if code != 200:
        print("获取 ref 失败:", code, ref)
        sys.exit(1)
    base_sha = ref["object"]["sha"]

    # 2) 获取 base commit 的 tree
    commit, code = api("GET", f"/repos/{REPO}/git/commits/{base_sha}", token)
    if code != 200:
        print("获取 commit 失败:", code, commit)
        sys.exit(1)
    base_tree_sha = commit["tree"]["sha"]

    # 3) 创建 blobs
    blobs = {}  # path -> sha
    for path in added:
        full = os.path.join(os.path.dirname(__file__), path)
        with open(full, "rb") as f:
            content = f.read()
        b64 = base64.b64encode(content).decode("ascii")
        # 判断文本/二进制
        try:
            content.decode("utf-8")
            is_binary = False
        except UnicodeDecodeError:
            is_binary = True
        data = {"content": b64, "encoding": "base64"}
        r, c = api("POST", f"/repos/{REPO}/git/blobs", token, data)
        if c != 201 or "sha" not in r:
            print(f"blob 失败 {path}:", c, r)
            sys.exit(1)
        blobs[path] = r["sha"]

    # 4) 构建新 tree（基于 base tree，覆盖 added，删除 deleted）
    tree_items = []
    for path in added:
        tree_items.append({"path": path, "mode": "100644",
                            "type": "blob", "sha": blobs[path]})
    for path in deleted:
        tree_items.append({"path": path, "mode": "100644",
                            "type": "blob", "sha": None})
    new_tree, c = api("POST", f"/repos/{REPO}/git/trees", token,
                      {"base_tree": base_tree_sha, "tree": tree_items})
    if c != 201:
        print("tree 失败:", c, new_tree)
        sys.exit(1)
    new_tree_sha = new_tree["sha"]

    # 5) 创建 commit
    new_commit, c = api("POST", f"/repos/{REPO}/git/commits", token,
                        {"message": msg, "tree": new_tree_sha,
                         "parents": [base_sha]})
    if c != 201:
        print("commit 失败:", c, new_commit)
        sys.exit(1)
    new_commit_sha = new_commit["sha"]

    # 6) 更新 ref
    r, c = api("PATCH", f"/repos/{REPO}/git/refs/heads/{BRANCH}", token,
               {"sha": new_commit_sha, "force": False})
    if c != 200:
        print("ref 更新失败:", c, r)
        sys.exit(1)
    print(f"已推送 commit {new_commit_sha[:10]} → 触发 Actions 构建")


if __name__ == "__main__":
    main()
