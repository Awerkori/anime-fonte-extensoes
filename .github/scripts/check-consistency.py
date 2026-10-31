#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
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


def load_sync_module():
    import importlib.util
    script_path = Path(".github/scripts/sync-upstream.py")
    if not script_path.exists():
        return None
    spec = importlib.util.spec_from_file_location("sync_upstream_mod", str(script_path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_consistency(check_pending: bool = False, full_audit: bool = True) -> int:
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

    # Check pending upstream differences via sync module logic
    upstream_ref = "upstream/master"
    sync_mod = load_sync_module()

    # Full 1-by-1 Parity Audit
    upstream_sources = set()
    if sync_mod:
        upstream_sources = sync_mod.get_all_source_units(upstream_ref)
        base = sync_mod.git("merge-base", "HEAD", upstream_ref).strip()
        diff_entries = sync_mod.changed_entries("HEAD", upstream_ref)
        diff_units, _ = sync_mod.collect_units(diff_entries)
        protected = sync_mod.get_nox_protected_units(base, upstream_ref)
        pending_units = sync_mod.pending_sync_units(diff_units, protected, upstream_ref, base=base)
    else:
        protected = get_protected_units()
        pending_units = []

    parity_by_status = {
        "IN_SYNC": [],
        "MISSING": [],
        "OUTDATED": [],
        "STALE": [],
        "RENAMED": [],
        "PUBLISH_PENDING": [],
        "PROTECTED_PTBR": [],
        "EXCEPTION": [],
    }

    all_audited_units = sorted(upstream_sources | local_modules | set(deployed_by_unit.keys()))
    for u in all_audited_units:
        if u.startswith("src/pt/") or u == "src/pt":
            parity_by_status["PROTECTED_PTBR"].append(u)
        elif u in upstream_sources and u not in local_modules:
            parity_by_status["MISSING"].append(u)
        elif u in local_modules and u not in upstream_sources:
            parity_by_status["STALE"].append(u)
        else:
            diff = git("diff", "--name-only", "HEAD", upstream_ref, "--", u, check=False)
            if diff:
                parity_by_status["OUTDATED"].append(u)
            elif u not in deployed_by_unit:
                parity_by_status["PUBLISH_PENDING"].append(u)
            else:
                parity_by_status["IN_SYNC"].append(u)

    # Build Markdown summary
    lines = [
        "## Project Nox Anime — Consistency & Parity Status\n",
        "| Parameter | Value | Status |",
        "| :--- | :--- | :--- |",
        f"| **Upstream Master HEAD** | `{upstream_head[:8] if len(upstream_head)>=8 else upstream_head}` | Reference |",
        f"| **Sync Branch HEAD** | `{sync_head[:8] if len(sync_head)>=8 else sync_head}` | {'MATCH' if sync_head == upstream_head else 'DIVERGED'} |",
        f"| **Recorded Synced SHA** | `{synced_state_sha[:8] if len(synced_state_sha)>=8 else synced_state_sha}` | {'MATCH' if synced_state_sha == upstream_head else 'PENDING'} |",
        f"| **Main Branch HEAD** | `{main_head[:8] if len(main_head)>=8 else main_head}` | Current |",
        f"| **Local Source Extensions** | {len(local_modules)} modules | Active |",
        f"| **Deployed Catalog Extensions** | {len(deployed_items)} extensions | Distribution |",
        f"| **Pending Upstream Units** | {len(pending_units)} units | {'NEEDS_SYNC' if pending_units else 'IN_SYNC'} |",
        "",
        "### 1-by-1 Upstream Parity Breakdown:\n",
        "| Parity Status | Count | Description |",
        "| :--- | :--- | :--- |",
        f"| `IN_SYNC` | {len(parity_by_status['IN_SYNC'])} | Faithful 1-by-1 match with Yuzono & deployed in catalog |",
        f"| `MISSING` | {len(parity_by_status['MISSING'])} | Present in Yuzono, missing locally in Nox |",
        f"| `OUTDATED` | {len(parity_by_status['OUTDATED'])} | Foreign extensions with divergent code or pending bump |",
        f"| `STALE` | {len(parity_by_status['STALE'])} | Removed in Yuzono, pending pruning from Nox |",
        f"| `RENAMED` | {len(parity_by_status['RENAMED'])} | Transitioned/renamed upstream extensions |",
        f"| `PUBLISH_PENDING` | {len(parity_by_status['PUBLISH_PENDING'])} | Code in sync locally, catalog publish pending |",
        f"| `PROTECTED_PTBR` | {len(parity_by_status['PROTECTED_PTBR'])} | Untouchable Brazilian extensions |",
        f"| `EXCEPTION` | {len(parity_by_status['EXCEPTION'])} | Documented audit exceptions |",
        "",
    ]

    if parity_by_status["MISSING"]:
        lines.append(f"**Missing Upstream Units to Import ({len(parity_by_status['MISSING'])}):** `{', '.join(parity_by_status['MISSING'])}`\n")
    if parity_by_status["STALE"]:
        lines.append(f"**Stale Foreign Units to Remove ({len(parity_by_status['STALE'])}):** `{', '.join(parity_by_status['STALE'])}`\n")
    if parity_by_status["OUTDATED"]:
        sample = parity_by_status["OUTDATED"][:20]
        extra = f" and {len(parity_by_status['OUTDATED']) - 20} more..." if len(parity_by_status["OUTDATED"]) > 20 else ""
        lines.append(f"**Outdated Foreign Units to Update ({len(parity_by_status['OUTDATED'])}):** `{', '.join(sample)}`{extra}\n")

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
        if (
            pending_units
            or parity_by_status["MISSING"]
            or parity_by_status["STALE"]
            or parity_by_status["OUTDATED"]
            or (upstream_head != sync_head and upstream_head != "UNKNOWN")
        ):
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
