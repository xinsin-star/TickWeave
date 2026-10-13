"""检查提交标题是否遵守 README 中约定的表情与类型前缀。

本地可用 --subject 检查单条标题；GitHub Actions 默认从事件 JSON 中计算
本次 push 或 Pull Request 新增的提交范围，逐条检查而非只看 HEAD。
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


PREFIXES = (
    "➕ Feat",
    "🚧 Fix",
    "📝 Docs",
    "♻️ Refactor",
    "🧪 Test",
    "🔧 Chore",
    "🏗️ Build",
    "👷 CI",
    "⚡ Perf",
)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def valid(subject: str) -> bool:
    for prefix in PREFIXES:
        marker = prefix + ": "
        if subject.startswith(marker):
            description = subject[len(marker):]
            return bool(description) and description == description.strip()
    return False


def revision_range() -> str:
    event_path = os.getenv("GITHUB_EVENT_PATH")
    if not event_path or not Path(event_path).is_file():
        return "HEAD"
    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    event_name = os.getenv("GITHUB_EVENT_NAME")
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        head = event["pull_request"]["head"]["sha"]
        return f"{git('merge-base', base, head)}..{head}"
    if event_name == "push":
        before = event.get("before", "")
        after = event.get("after", "HEAD")
        # GitHub 在新分支第一次 push 时以全零 SHA 表示不存在的前一提交。
        return f"{before}..{after}" if before and set(before) != {"0"} else after
    return "HEAD"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subject", help="只检查一条提交标题，便于提交前本地自检")
    parser.add_argument("--range", dest="commit_range", help="手动指定 Git 修订范围")
    args = parser.parse_args()
    if args.subject is not None:
        subjects = [args.subject]
    else:
        commit_range = args.commit_range or revision_range()
        subjects = git("log", "--format=%s", commit_range).splitlines()
    if not subjects:
        print("没有需要检查的提交。")
        return 0
    failed = [subject for subject in subjects if not valid(subject)]
    if failed:
        print("提交标题不符合格式：", file=sys.stderr)
        for subject in failed:
            print(f"  {subject}", file=sys.stderr)
        print("允许的格式：'➕ Feat: 说明'、'🚧 Fix: 说明' 等；完整前缀见 README。", file=sys.stderr)
        return 1
    print(f"已检查 {len(subjects)} 条提交标题，格式正确。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
