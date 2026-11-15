"""推送到 GitHub。

用法：
    python tools/push_to_github.py                    # 创建公开仓库并推送
    python tools/push_to_github.py --private          # 创建私有仓库
    python tools/push_to_github.py --dry-run          # 只检查，不推

前提：已经 `gh auth login` 过。没装 gh 就先 `winget install GitHub.cli`。
"""
import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run(cmd, cwd=None, quiet=False):
    """跑命令，返回 (returncode, stdout, stderr)。"""
    r = subprocess.run(cmd, cwd=cwd or ROOT, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if not quiet and r.returncode != 0:
        print("  ! %s" % " ".join(cmd))
        if r.stdout.strip():
            print("    stdout:", r.stdout.strip()[:400])
        if r.stderr.strip():
            print("    stderr:", r.stderr.strip()[:400])
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def find_gh():
    p = shutil.which("gh")
    if p:
        return p
    for c in [
        os.path.join(os.environ.get("ProgramFiles", ""), "GitHub CLI", "gh.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "GitHub CLI", "gh.exe"),
    ]:
        if c and os.path.isfile(c):
            return c
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo-name", default="comfyui-android")
    ap.add_argument("--private", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    print("== 检查 gh")
    gh = find_gh()
    if not gh:
        print("  没找到 gh（GitHub CLI）。先装：")
        print("      winget install GitHub.cli")
        print("  装完重开一个终端再跑。")
        return 1

    rc, out, err = run([gh, "auth", "status"], quiet=True)
    if rc != 0:
        print("  gh 还没登录。跑：")
        print("      gh auth login")
        print("  选 GitHub.com → HTTPS → Login with a web browser")
        print()
        print("  （浏览器打不开就用 token：gh auth login --with-token，需要 repo 权限）")
        return 1
    # 从输出里抠出用户名
    user = ""
    for line in (out + err).split("\n"):
        if "account" in line.lower() and "(" in line:
            user = line.split("(")[0].strip()
            break
    print("   OK  已登录 %s" % (user or ""))

    print("== 检查仓库状态")
    rc, out, _ = run(["git", "status", "--porcelain"])
    if out:
        print("   有未提交的改动：")
        for line in out.split("\n")[:10]:
            print("     " + line)
        print("   请先提交： git add -A && git commit -m \"...\"")
        return 1
    print("   工作区干净")

    print("== 确保分支叫 main")
    rc, branch, _ = run(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    if branch != "main":
        run(["git", "branch", "-M", "main"])
        print("   已把 %s 改名为 main" % branch)
    else:
        print("   已是 main")

    print("== 检查远程")
    rc, remotes, _ = run(["git", "remote"])
    has_origin = "origin" in remotes.split()

    if has_origin:
        rc, url, _ = run(["git", "remote", "get-url", "origin"])
        print("   origin 已存在：%s" % url)
        if args.dry_run:
            print("   [dry-run] 会执行： git push -u origin main")
            return 0
        print("== 推送")
        rc, _, _ = run(["git", "push", "-u", "origin", "main"])
        if rc != 0:
            return 1
    else:
        vis = "--private" if args.private else "--public"
        cmd = [gh, "repo", "create", args.repo_name, vis,
               "--source=.", "--remote=origin", "--push"]
        if args.dry_run:
            print("   [dry-run] 会执行： " + " ".join(cmd))
            return 0
        print("== 创建远程仓库（%s）并推送" %
              ("私有" if args.private else "公开"))
        rc, out, err = run(cmd)
        if rc != 0:
            return 1

    print()
    print("== 完成")
    rc, url, _ = run(["git", "remote", "get-url", "origin"], quiet=True)
    if url:
        # ssh 格式转成 https，方便点开
        if url.startswith("git@github.com:"):
            url = "https://github.com/" + url[len("git@github.com:"):]
        if url.endswith(".git"):
            url = url[:-4]
        print("   仓库：%s" % url)
    rc, log, _ = run(["git", "log", "--oneline", "-3"], quiet=True)
    for line in log.split("\n"):
        if line.strip():
            print("   " + line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
