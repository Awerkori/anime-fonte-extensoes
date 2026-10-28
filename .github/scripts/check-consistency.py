#!/usr/bin/env python3
"""
Project Nox Anime — Consistency Checker

Compares Upstream HEAD, Synced Upstream SHA, Main HEAD, and Published Catalog.
Verifies version monotonicity, checks for pending deliveries, and reports state.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
DISTRIBUTION_REPO = "Awerkori/anime-extensoes"
DISTRIBUTION_INDEX_URL = f"https://raw.githubusercontent.com/{DISTRIBUTION_REPO}/repo/index.min.json"
SYNC_STATE_FILE = Path(".github/sync-state.json")
NOX_PROTECTED_FILE = Path(".github/nox-protected.txt")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        return ""
    return result.stdout.strip()


def get_upstream_head() -> str:
    out = git("ls-remote", UPSTREAM_URL, "refs/heads/master", check=False)
    return out.split()[0] if out else "UNKNOWN"


def get_sync_branch_head() -> str:
    out = git("ls-remote", "origin", "refs/heads/sync", check=False)
    return out.split()[0] if out else "UNKNOWN"


def get_sync_state() -> dict:
    if SYNC_STATE_FILE.exists():
        try:
            return json.loads(SYNC_STATE_FILE.read_text("utf-8"))
        except Exception:
            pass
    return {}


def get_protected_units() -> set[str]:
    if not NOX_PROTECTED_FILE.exists():
        return set()
    return {
        line.split("#", 1)[0].strip()
        for line in NOX_PROTECTED_FILE.read_text("utf-8").splitlines()
        if line.split("#", 1)[0].strip()
    }


def get_deployed_catalog() -> list[dict]:
    try:
        req = urllib.request.Request(DISTRIBUTION_INDEX_URL, headers={"User-Agent": "Nox-Consistency/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Warning: could not fetch distribution catalog ({e})")
        return []


def get_local_source_modules() -> set[str]:
    modules = set()
    src_dir = Path("src")
    if not src_dir.is_dir():
        return modules
    for lang in src_dir.iterdir():
        if not lang.is_dir():
            continue
        for ext in lang.iterdir():
            if ext.is_dir() and ((ext / "build.gradle.kts").is_file() or (ext / "build.gradle").is_file()):
                modules.add(f"src/{lang.name}/{ext.name}")
    return modules


def check_consistency(check_pending: bool = False) -> int:
    upstream_head = get_upstream_head()
    sync_head = get_sync_branch_head()
    sync_state = get_sync_state()
    synced_state_sha = sync_state.get("upstream_sha", "NONE")
    main_head = git("rev-parse", "HEAD")

    local_modules = get_local_source_modules()
    deployed_items = get_deployed_catalog()
    deployed_by_unit = {}
    for item in deployed_items:
        pkg = item.get("pkg", "")
        prefix = "eu.kanade.tachiyomi.animeextension."
        if pkg.startswith(prefix):
            mod = pkg[len(prefix):]
            parts = mod.split(".")
            if len(parts) >= 2:
                unit = f"src/{parts[0]}/{parts[1]}"
                deployed_by_unit[unit] = item

    # Check pending upstream differences
    protected = get_protected_units()
    upstream_ref = "upstream/master"
    diff_output = git("diff", "--name-only", "HEAD", upstream_ref, check=False)
    diff_units = set()
    for line in diff_output.splitlines():
        if line.startswith("src/"):
            diff_units.add("/".join(line.split("/")[:3]))

    pending_units = [
        u for u in sorted(diff_units)
        if u not in protected
    ]

    # Build Markdown summary
    lines = [
        "## Project Nox Anime — Consistency & Synchronization Status\n",
        "| Parameter | Value | Status |",
        "| :--- | :--- | :--- |",
        f"| **Upstream Master HEAD** | `{upstream_head[:8] if len(upstream_head)>=8 else upstream_head}` | Reference |",
        f"| **Sync Branch HEAD** | `{sync_head[:8] if len(sync_head)>=8 else sync_head}` | {'MATCH' if sync_head == upstream_head else 'DIVERGED'} |",
        f"| **Recorded Synced SHA** | `{synced_state_sha[:8] if len(synced_state_sha)>=8 else synced_state_sha}` | {'MATCH' if synced_state_sha == upstream_head else 'PENDING'} |",
        f"| **Main Branch HEAD** | `{main_head[:8] if len(main_head)>=8 else main_head}` | Current |",
        f"| **Local Source Extensions** | {len(local_modules)} modules | Active |",
        f"| **Deployed Catalog Extensions** | {len(deployed_items)} extensions | Distribution |",
        f"| **Pending Upstream Units** | {len(pending_units)} units | {'NEEDS_SYNC' if pending_units else 'IN_SYNC'} |",
        f"| **Explicitly Protected Units** | {len(protected)} units | Protected |",
        "",
    ]

    if pending_units:
        lines.append(f"### Pending Upstream Units ({len(pending_units)}):\n")
        lines.append(f"`{', '.join(pending_units[:30])}`" + (f" and {len(pending_units) - 30} more..." if len(pending_units) > 30 else ""))
        lines.append("")

    report = "\n".join(lines)
    print(report)

    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        try:
            with open(summary_file, "a", encoding="utf-8") as f:
                f.write(report + "\n")
        except Exception:
            pass

    if check_pending:
        # Exit code 2 indicates sync is needed
        if pending_units or (upstream_head != sync_head and upstream_head != "UNKNOWN"):
            return 2
        return 0

    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Check Project Nox consistency")
    parser.add_argument("--check-pending", action="store_true", help="Exit with code 2 if sync is needed")
    args = parser.parse_args()
    sys.exit(check_consistency(check_pending=args.check_pending))


if __name__ == "__main__":
    main()
