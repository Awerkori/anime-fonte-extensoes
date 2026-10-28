#!/usr/bin/env python3
"""
Project Nox Anime — Sync Upstream (Yuzono Nox-Wins & Full Propagation)

Rules:
  - Normal upstream updates (not protected by Nox) → applied completely to main
  - Nox-exclusive extensions (never existed upstream) → fully preserved
  - Nox-protected extensions (.github/nox-protected.txt) → Nox code preserved, monotonic version guard applied
  - Upstream deletions:
      - Protected / Nox-exclusive → preserved
      - Non-protected upstream extensions → deleted from source tree & catalog
  - Indirect impacts (lib, lib-multisrc) → detected via dependency graph and built
  - Version monotonicity → effective versionCode strictly > max(upstream, local, deployed)
  - Self-healing → if main is behind upstream (e.g. from prior failed builds or skipped sync),
    pending diffs are detected and applied automatically even if the sync ref matches upstream HEAD.

Upstream repository: https://github.com/yuzono/anime-extensions (branch master)
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
UPSTREAM_BRANCH = "master"
SYNC_BRANCH = "sync"
SYNC_STATE_FILE = Path(".github/sync-state.json")
NOX_PROTECTED_FILE = Path(".github/nox-protected.txt")

# ──────────────────────────────────────────────────────────────────────────────
# Versioning regex patterns — Groovy & Kotlin DSL
# ──────────────────────────────────────────────────────────────────────────────
_EXT_VERSION_CODE_RE = re.compile(r"extVersionCode\s*=\s*(\d+)")
_OVERRIDE_VERSION_CODE_RE = re.compile(r"overrideVersionCode\s*=\s*(\d+)")
_KTS_VERSION_CODE_RE = re.compile(r"(?:versionCode|overrideVersionCode)\s*=\s*(\d+)")
_THEME_RE = re.compile(r"""(?:theme|themePkg)\s*=\s*['"]([^'"]+)['"]""")
_BASE_VERSION_CODE_RE = re.compile(r"baseVersionCode\s*=\s*(\d+)")
_LIB_VERSION_RE = re.compile(r"""libVersion\s*=\s*['"]?(\d+)['"]?""")


# ──────────────────────────────────────────────────────────────────────────────
# Git helpers
# ──────────────────────────────────────────────────────────────────────────────

def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        sys.exit(result.returncode)
    return result.stdout


def ensure_upstream_remote() -> None:
    if subprocess.run(
        ["git", "remote", "get-url", UPSTREAM_REMOTE], capture_output=True
    ).returncode != 0:
        git("remote", "add", UPSTREAM_REMOTE, UPSTREAM_URL)


def ensure_clean_tree() -> None:
    if git("status", "--porcelain").strip():
        print("Working tree is not clean", file=sys.stderr)
        sys.exit(1)


def path_exists(ref: str, path: str) -> bool:
    return subprocess.run(
        ["git", "cat-file", "-e", f"{ref}:{path}"], capture_output=True
    ).returncode == 0


def read_file_at(ref: str | None, path: str) -> str | None:
    if ref is None:
        p = Path(path)
        return p.read_text(encoding="utf-8", errors="ignore") if p.exists() else None
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True
    )
    return result.stdout if result.returncode == 0 else None


# ──────────────────────────────────────────────────────────────────────────────
# Diff & Unit helpers
# ──────────────────────────────────────────────────────────────────────────────

def parse_name_status(output: str) -> list[tuple[str, list[str]]]:
    tokens = output.rstrip("\0").split("\0")
    entries = []
    i = 0
    while i < len(tokens) and tokens[i]:
        status = tokens[i]
        i += 1
        path_count = 2 if status[0] in {"R", "C"} else 1
        entries.append((status, tokens[i : i + path_count]))
        i += path_count
    return entries


def is_preserved(path: str) -> bool:
    """Paths that must NEVER be overwritten by upstream (our CI/scripts)."""
    return path == ".github" or path.startswith(".github/")


def sync_unit(path: str) -> str | None:
    """Normalize a file path to its semantic sync unit."""
    if is_preserved(path):
        return None
    parts = path.split("/")
    if len(parts) >= 3 and parts[0] == "src":
        return "/".join(parts[:3])
    if len(parts) >= 2 and parts[0] in {"lib", "lib-multisrc"}:
        return "/".join(parts[:2])
    return path


def changed_entries(base: str, ref: str) -> list[tuple[str, list[str]]]:
    output = git("diff", "--name-status", "--find-renames", "-z", base, ref)
    return parse_name_status(output)


def collect_units(entries: list[tuple[str, list[str]]]) -> tuple[list[str], list[str]]:
    units: set[str] = set()
    preserved: set[str] = set()
    for _, paths in entries:
        for path in paths:
            unit = sync_unit(path)
            if unit is None:
                preserved.add(path)
            else:
                units.add(unit)
    return sorted(units), sorted(preserved)


# ──────────────────────────────────────────────────────────────────────────────
# Nox Protection & Exclusivity
# ──────────────────────────────────────────────────────────────────────────────

EXPLICIT_LOCKED_UNITS = frozenset({"src/pt/darkmahou"})
_UPSTREAM_HISTORY_CACHE: dict[tuple[str, str, str], bool] = {}


def get_explicit_delete_protections() -> list[str]:
    """Read units explicitly listed in .github/nox-protected.txt."""
    if not NOX_PROTECTED_FILE.exists():
        return []
    protected = []
    for raw_line in NOX_PROTECTED_FILE.read_text("utf-8").splitlines():
        unit = raw_line.split("#", 1)[0].strip()
        if not unit:
            continue
        if not re.fullmatch(r"src/[^/]+/[^/]+", unit):
            raise ValueError(f"Invalid protection entry: {unit}")
        protected.append(unit)
    return sorted(set(protected))


def get_explicit_code_protections(
    explicit_delete_protections: list[str] | None = None,
) -> set[str]:
    """Return all explicitly protected units."""
    base_list = (
        explicit_delete_protections
        if explicit_delete_protections is not None
        else get_explicit_delete_protections()
    )
    return set(base_list) | set(EXPLICIT_LOCKED_UNITS)


def get_nox_protected_units(base: str, upstream_ref: str) -> list[str]:
    """Protected Nox extensions: Read from .github/nox-protected.txt (excluding explicitly locked units)."""
    return [u for u in get_explicit_delete_protections() if u not in EXPLICIT_LOCKED_UNITS]


def unit_existed_in_upstream_history(
    unit: str,
    upstream_ref: str,
    base: str | None = None,
) -> bool:
    """Check if unit ever existed in upstream history."""
    repo_key = os.path.abspath(".")
    rev_key = git("rev-parse", upstream_ref, check=False).strip()
    cache_key = (repo_key, rev_key, unit)
    if cache_key in _UPSTREAM_HISTORY_CACHE:
        return _UPSTREAM_HISTORY_CACHE[cache_key]

    if path_exists(upstream_ref, unit):
        _UPSTREAM_HISTORY_CACHE[cache_key] = True
        return True

    if base and path_exists(base, unit):
        _UPSTREAM_HISTORY_CACHE[cache_key] = True
        return True

    output = git("rev-list", "-1", upstream_ref, "--", unit, check=False).strip()
    existed = bool(output)
    _UPSTREAM_HISTORY_CACHE[cache_key] = existed
    return existed


def is_nox_exclusive(
    unit: str,
    upstream_ref: str,
    base: str | None = None,
    local_ref: str | None = "HEAD",
) -> bool:
    """Return whether a unit is exclusive to Project Nox (created locally, never existed upstream)."""
    has_local = (
        (local_ref is not None and path_exists(local_ref, unit))
        or Path(unit).exists()
    )
    if not has_local:
        return False

    if path_exists(upstream_ref, unit):
        return False

    return not unit_existed_in_upstream_history(unit, upstream_ref, base=base)


def upstream_historical_units(upstream_ref: str) -> set[str]:
    """Return source units that were added at some point in upstream history."""
    paths = git(
        "log", "--diff-filter=A", "--name-only", "--format=",
        upstream_ref, "--", "src/*/*/build.gradle", "src/*/*/build.gradle.kts",
    ).splitlines()
    return {
        unit for path in paths
        if path.endswith(("/build.gradle", "/build.gradle.kts"))
        if (unit := sync_unit(path)) is not None and unit.startswith("src/")
    }


def classify_sync_units(
    base: str,
    upstream_ref: str,
    upstream_units: list[str],
    main_units: list[str],
    explicit_delete_protections: list[str] | None = None,
) -> tuple[list[str], list[str], list[str], list[str], list[str]]:
    """Separate normal updates/conflicts from true upstream source deletions."""
    upstream_set = set(upstream_units)
    main_set = set(main_units)
    candidates = upstream_set | main_set
    historically_upstream = upstream_historical_units(upstream_ref)
    
    # Deletions: existed upstream historically or in base, but missing from upstream_ref
    deleted = {
        unit for unit in candidates
        if unit.startswith("src/")
        and (path_exists(base, unit) or unit in historically_upstream)
        and path_exists("HEAD", unit)
        and not path_exists(upstream_ref, unit)
    }
    
    explicitly_protected = get_explicit_code_protections(explicit_delete_protections)
    deleted_protected = sorted({
        u for u in deleted
        if u in explicitly_protected or is_nox_exclusive(u, upstream_ref, base=base)
    })
    deleted_remove = sorted(deleted - set(deleted_protected))

    protected_updates = {
        unit for unit in (upstream_set & explicitly_protected) - deleted
        if path_exists("HEAD", unit)
    }

    # Conflicts: units with concurrent edits between base and HEAD
    conflicts = sorted(((upstream_set & main_set) | protected_updates) - deleted)
    upstream_only = sorted(upstream_set - set(conflicts) - deleted)
    nox_only = sorted(main_set - upstream_set - deleted)
    return upstream_only, conflicts, nox_only, deleted_remove, deleted_protected


def pending_sync_units(
    diff_units: list[str],
    protected_units: list[str],
    upstream_ref: str,
    base: str | None = None,
) -> list[str]:
    """Return HEAD/upstream differences which still need a sync attempt."""
    protected = set(protected_units)
    pending = []
    for unit in diff_units:
        if unit in protected:
            continue
        if is_nox_exclusive(unit, upstream_ref, base=base):
            continue
        if not path_exists(upstream_ref, unit) and (
            unit in protected or is_nox_exclusive(unit, upstream_ref, base=base)
        ):
            continue
        pending.append(unit)
    return pending


# ──────────────────────────────────────────────────────────────────────────────
# Indirect impact detection
# ──────────────────────────────────────────────────────────────────────────────

def resolve_dependent_libs(libs: set[str]) -> set[str]:
    """Recursively find libs that depend on the given libs."""
    if not libs:
        return set()
    all_deps: set[str] = set()
    to_process = set(libs)
    while to_process:
        current = to_process
        to_process = set()
        pattern = re.compile(
            rf"project\((?:path(?: =|:) )?[\"']:(?:lib):({'|'.join(map(re.escape, current))})[\"']\)"
        )
        if not Path("lib").is_dir():
            break
        for lib_dir in Path("lib").iterdir():
            if not lib_dir.is_dir() or lib_dir.name in all_deps or lib_dir.name in libs:
                continue
            build_file = lib_dir / "build.gradle.kts"
            if not build_file.is_file():
                build_file = lib_dir / "build.gradle"
            if not build_file.is_file():
                continue
            if pattern.search(build_file.read_text("utf-8", errors="ignore")):
                all_deps.add(lib_dir.name)
                to_process.add(lib_dir.name)
    return all_deps


def resolve_multisrc_from_libs(libs: set[str]) -> set[str]:
    """Find lib-multisrc themes that depend on the given libs."""
    if not libs or not Path("lib-multisrc").is_dir():
        return set()
    pattern = re.compile(
        rf"project\((?:path(?: =|:) )?[\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
    )
    themes: set[str] = set()
    for ms_dir in Path("lib-multisrc").iterdir():
        if not ms_dir.is_dir():
            continue
        build_file = ms_dir / "build.gradle.kts"
        if not build_file.is_file():
            build_file = ms_dir / "build.gradle"
        if not build_file.is_file():
            continue
        if pattern.search(build_file.read_text("utf-8", errors="ignore")):
            themes.add(ms_dir.name)
    return themes


def resolve_extensions_from(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    """Find extensions that depend on given multisrcs or libs."""
    if not multisrcs and not libs:
        return set()
    patterns = []
    if multisrcs:
        patterns.append(
            rf"""(?:theme|themePkg)\s*=\s*['"]({'|'.join(map(re.escape, multisrcs))})['"]"""
        )
    if libs:
        patterns.append(
            rf"project\((?:path(?: =|:) )?[\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
        )
    regex = re.compile("|".join(patterns))
    extensions: set[tuple[str, str]] = set()
    if not Path("src").is_dir():
        return extensions
    for lang_dir in Path("src").iterdir():
        if not lang_dir.is_dir():
            continue
        for ext_dir in lang_dir.iterdir():
            if not ext_dir.is_dir():
                continue
            build_file = ext_dir / "build.gradle.kts"
            if not build_file.is_file():
                build_file = ext_dir / "build.gradle"
            if not build_file.is_file():
                continue
            if regex.search(build_file.read_text("utf-8", errors="ignore")):
                extensions.add((lang_dir.name, ext_dir.name))
    return extensions


def detect_indirect_impacts(upstream_units: list[str]) -> set[str]:
    """Given upstream changes, expand impact to include extensions affected indirectly."""
    changed_libs = {
        u.split("/")[1] for u in upstream_units if u.startswith("lib/")
    }
    changed_multisrcs = {
        u.split("/")[1] for u in upstream_units if u.startswith("lib-multisrc/")
    }

    changed_libs |= resolve_dependent_libs(changed_libs)
    changed_multisrcs |= resolve_multisrc_from_libs(changed_libs)
    affected_exts = resolve_extensions_from(changed_multisrcs, changed_libs)
    return {f"src/{lang}/{ext}" for lang, ext in affected_exts}


# ──────────────────────────────────────────────────────────────────────────────
# Versioning & Monotonic Version Guard
# ──────────────────────────────────────────────────────────────────────────────

def read_base_version_code(ref: str | None, theme: str) -> int:
    """Read baseVersionCode from lib-multisrc/<theme>/build.gradle.kts (or .gradle)."""
    for fn in ("build.gradle.kts", "build.gradle"):
        content = read_file_at(ref, f"lib-multisrc/{theme}/{fn}")
        if content:
            m = _BASE_VERSION_CODE_RE.search(content)
            if m:
                return int(m.group(1))
    return 0


def effective_version(ref: str | None, unit: str) -> tuple[int, int, int] | None:
    """
    Returns (raw, base, effective) for an extension unit.
    Works for both Groovy and Kotlin DSL.
    """
    content = read_file_at(ref, f"{unit}/build.gradle.kts")
    if not content:
        content = read_file_at(ref, f"{unit}/build.gradle")
    if not content:
        return None

    theme_match = _THEME_RE.search(content)
    if theme_match:
        theme = theme_match.group(1)
        vc_match = _KTS_VERSION_CODE_RE.search(content)
        if not vc_match:
            return None
        raw = int(vc_match.group(1))
        base = read_base_version_code(ref, theme)
        return raw, base, raw + base
    else:
        vc_match = _KTS_VERSION_CODE_RE.search(content)
        if not vc_match:
            vc_match = _EXT_VERSION_CODE_RE.search(content)
        if not vc_match:
            return None
        raw = int(vc_match.group(1))
        return raw, 0, raw


def read_lib_version(ref: str | None, unit: str) -> int:
    """Read libVersion from extension build file. Defaults to 14 if omitted."""
    for fn in ("build.gradle.kts", "build.gradle"):
        content = read_file_at(ref, f"{unit}/{fn}")
        if content:
            m = _LIB_VERSION_RE.search(content)
            if m:
                return int(m.group(1))
    return 14


def fetch_deployed_version_codes() -> dict[str, int]:
    """Fetch currently deployed extension codes from distribution repo."""
    try:
        import urllib.request
        url = "https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json"
        req = urllib.request.Request(url, headers={"User-Agent": "Nox-Sync/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            codes = {}
            for item in data:
                pkg = item.get("pkg", "")
                prefix = "eu.kanade.tachiyomi.animeextension."
                if pkg.startswith(prefix):
                    module = pkg[len(prefix):]
                    parts = module.split(".")
                    if len(parts) >= 2:
                        unit = f"src/{parts[0]}/{parts[1]}"
                        codes[unit] = int(item.get("code", 0))
            return codes
    except Exception as e:
        print(f"Notice: unable to fetch deployed version codes ({e}); continuing with local evaluation")
        return {}


def bump_version_if_needed(
    unit: str,
    upstream_ref: str,
    pre_sync_effective: tuple[int, int, int] | None = None,
    pre_sync_lib: int | None = None,
    deployed_code: int = 0,
) -> tuple[int, int, int, int, int, int, int] | None:
    """
    Ensure local version code and libVersion are monotonically ahead of upstream and deployed.
    """
    local = pre_sync_effective if pre_sync_effective is not None else effective_version(None, unit)
    upstream = effective_version(upstream_ref, unit)

    if local is None and upstream is None:
        return None

    loc_raw, loc_base, loc_eff = local if local is not None else (0, 0, 0)
    up_raw, up_base, up_eff = upstream if upstream is not None else (0, 0, 0)

    loc_lib = pre_sync_lib if pre_sync_lib is not None else read_lib_version(None, unit)
    up_lib = read_lib_version(upstream_ref, unit) if upstream is not None else loc_lib

    needs_vc_bump = (up_eff >= loc_eff) or (loc_eff <= deployed_code)
    needs_lib_upgrade = up_lib > loc_lib

    if not needs_vc_bump and not needs_lib_upgrade:
        return None

    target_eff = max(up_eff + 1, loc_eff + 1, deployed_code + 1)

    kts_path = Path(f"{unit}/build.gradle.kts")
    groovy_path = Path(f"{unit}/build.gradle")

    if kts_path.exists():
        build_file = kts_path
        is_kts = True
    elif groovy_path.exists():
        build_file = groovy_path
        is_kts = False
    else:
        return None

    text = build_file.read_text(encoding="utf-8")
    theme_match = _THEME_RE.search(text)
    theme = theme_match.group(1) if theme_match else None
    cur_base = read_base_version_code(None, theme) if theme else 0
    new_raw = target_eff - cur_base

    target_lib = max(loc_lib, up_lib)
    if is_kts:
        if target_lib > 14:
            if re.search(r'libVersion\s*=\s*["\']\d+["\']', text):
                text = re.sub(r'libVersion\s*=\s*["\']\d+["\']', f'libVersion = "{target_lib}"', text, count=1)
            elif re.search(r'libVersion\s*=\s*\d+', text):
                text = re.sub(r'libVersion\s*=\s*\d+', f'libVersion = "{target_lib}"', text, count=1)
            else:
                marker = re.search(r"(?m)^(\s*)yuzono\s*\{", text)
                if marker:
                    indent = marker.group(1) + "    "
                    text = text[:marker.end()] + f'\n{indent}libVersion = "{target_lib}"' + text[marker.end():]
        if re.search(r'(versionCode|overrideVersionCode)\s*=\s*\d+', text):
            text = re.sub(r'(versionCode|overrideVersionCode)\s*=\s*\d+', rf'\g<1> = {new_raw}', text, count=1)
    else:
        if target_lib > 14:
            if re.search(r"libVersion\s*=\s*\d+", text):
                text = re.sub(r"libVersion\s*=\s*\d+", f"libVersion = {target_lib}", text, count=1)
            else:
                text = re.sub(r"(ext\s*\{)", rf"\1\n    libVersion = {target_lib}", text, count=1)
        if theme:
            if re.search(r"overrideVersionCode\s*=\s*\d+", text):
                text = re.sub(r"overrideVersionCode\s*=\s*\d+", f"overrideVersionCode = {new_raw}", text, count=1)
            else:
                text = re.sub(r"(ext\s*\{)", rf"\1\n    overrideVersionCode = {new_raw}", text, count=1)
        else:
            if re.search(r"extVersionCode\s*=\s*\d+", text):
                text = re.sub(r"extVersionCode\s*=\s*\d+", f"extVersionCode = {new_raw}", text, count=1)
            else:
                text = re.sub(r"(ext\s*\{)", rf"\1\n    extVersionCode = {new_raw}", text, count=1)

    build_file.write_text(text, encoding="utf-8")
    return loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw


# ──────────────────────────────────────────────────────────────────────────────
# Application & Merge
# ──────────────────────────────────────────────────────────────────────────────

def apply_units(
    upstream_ref: str,
    units_to_apply: list[str],
    conflict_set: set[str],
    protected: list[str],
    upstream_deleted: list[str],
    protected_deletions: list[str],
    deployed_codes: dict[str, int] | None = None,
) -> list[str]:
    """Apply upstream updates, preserves Nox-protected code, and guarantees version monotonicity."""
    deployed_codes = deployed_codes or {}
    base = git("merge-base", "HEAD", upstream_ref).strip()

    pre_sync_effective: dict[str, tuple[int, int, int]] = {}
    pre_sync_lib: dict[str, int] = {}
    for unit in set(protected) | set(units_to_apply):
        eff = effective_version(None, unit)
        if eff is not None:
            pre_sync_effective[unit] = eff
        pre_sync_lib[unit] = read_lib_version(None, unit)

    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    deleted_set = set(upstream_deleted or [])
    deletion_protection_set = set(protected_deletions or [])
    explicit_protection_set = deletion_protection_set | get_explicit_code_protections()

    for unit in units_to_apply:
        if unit in deletion_protection_set:
            print(f"Preserving explicitly protected upstream deletion: {unit}")
            continue
        if unit in explicit_protection_set:
            print(f"Preserving explicitly protected Nox unit: {unit}")
            continue
        if unit in deleted_set:
            print(f"Removing upstream-deleted unit: {unit}")
            git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)
            continue
        if is_nox_exclusive(unit, upstream_ref, base=base):
            print(f"Preserving Nox-exclusive unit: {unit}")
            continue

        if unit in protected:
            # Explicitly protected unit with upstream changes
            up_lib = read_lib_version(upstream_ref, unit)
            loc_lib = pre_sync_lib.get(unit, 14)
            if up_lib > loc_lib:
                print(f"Applying upstream structural lib migration {loc_lib} -> {up_lib} for {unit}")
                git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)
                if path_exists(upstream_ref, unit):
                    git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)
            else:
                print(f"Preserving protected Nox code on conflict: {unit}")
                continue
        else:
            # Common upstream unit (or structural theme)
            print(f"Applying {unit}")
            git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)
            if path_exists(upstream_ref, unit):
                git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

            # Ensure clean directory state if build file changed format (.gradle -> .gradle.kts)
            if path_exists(upstream_ref, f"{unit}/build.gradle.kts"):
                stale_gradle = Path(f"{unit}/build.gradle")
                if stale_gradle.exists():
                    stale_gradle.unlink(missing_ok=True)
                    git("rm", "--cached", "--ignore-unmatch", "--quiet", "--", str(stale_gradle))

    bumped: list[str] = []
    guard_units = sorted(
        set(protected)
        | {u for u in units_to_apply if u.startswith("src/") and u not in explicit_protection_set}
    )

    for unit in guard_units:
        deployed_code = deployed_codes.get(unit, 0)
        res = bump_version_if_needed(
            unit,
            upstream_ref,
            pre_sync_effective=pre_sync_effective.get(unit),
            pre_sync_lib=pre_sync_lib.get(unit),
            deployed_code=deployed_code,
        )
        if res is not None:
            loc_raw, _, _, _, _, _, new_raw = res
            for fn in ("build.gradle.kts", "build.gradle"):
                target_f = Path(f"{unit}/{fn}")
                if target_f.exists():
                    git("add", "--", str(target_f))
            bumped.append(f"{unit} → {new_raw}")
            print(f"Version guard: bumped {unit} ({loc_raw} → {new_raw})")

    # Record sync state
    upstream_head = git("rev-parse", upstream_ref).strip()
    head_sha = git("rev-parse", "HEAD").strip()
    sync_state = {
        "upstream_sha": upstream_head,
        "upstream_branch": UPSTREAM_BRANCH,
        "synced_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "base_sha": base,
    }
    SYNC_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    SYNC_STATE_FILE.write_text(json.dumps(sync_state, indent=2) + "\n", encoding="utf-8")
    git("add", "--", str(SYNC_STATE_FILE))

    git("diff", "--check")

    commit_msg = "Sync upstream"
    if bumped:
        commit_msg += "\n\nNox-wins version bumps:\n" + "\n".join(f"  - {b}" for b in bumped)
    git("commit", "-m", commit_msg)
    return bumped


def write_step_summary(protected: list[str], bumped: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path or not protected:
        return
    lines = ["\n## Nox-Wins Summary\n\n"]
    for u in protected:
        tag = next((b for b in bumped if b.startswith(u)), None)
        if tag:
            lines.append(f"- `{u}` — kept Nox code, bumped to `{tag.split('→')[1].strip()}`\n")
        else:
            lines.append(f"- `{u}` — kept Nox code, version already ahead\n")
    with open(path, "a") as f:
        f.writelines(lines)


def print_observability_header(
    project: str,
    trigger: str,
    local_head: str,
    upstream_remote: str,
    upstream_branch: str,
    upstream_head: str,
    last_synced_upstream: str,
    new_units: list[str],
    updated_units: list[str],
    removed_units: list[str],
    core_changed: bool,
    multisrc_changed: bool,
    no_changes: bool,
) -> None:
    print("==================================================")
    print("PROJECT NOX — UPSTREAM SYNC")
    print("==================================================")
    print(f"SYNC_PROJECT={project}")
    print(f"SYNC_TRIGGER={trigger}")
    print(f"LOCAL_HEAD={local_head}")
    print(f"UPSTREAM_REMOTE={upstream_remote}")
    print(f"UPSTREAM_BRANCH={upstream_branch}")
    print(f"UPSTREAM_HEAD={upstream_head}")
    print(f"LAST_SYNCED_UPSTREAM={last_synced_upstream}")
    print(f"NEW_COUNT={len(new_units)}")
    print(f"UPDATED_COUNT={len(updated_units)}")
    print(f"REMOVED_COUNT={len(removed_units)}")
    print(f"CORE_CHANGED={str(core_changed).lower()}")
    print(f"MULTISRC_CHANGED={str(multisrc_changed).lower()}")
    print(f"NO_CHANGES={str(no_changes).lower()}")
    print("==================================================")
    if not no_changes:
        print("Detected units:")
        if new_units:
            print(f"  NEW ({len(new_units)}): {', '.join(sorted(new_units))}")
        if updated_units:
            print(f"  UPDATED ({len(updated_units)}): {', '.join(sorted(updated_units))}")
        if removed_units:
            print(f"  REMOVED ({len(removed_units)}): {', '.join(sorted(removed_units))}")
        print("==================================================")


def print_plan(
    base: str,
    upstream_ref: str,
    upstream_only: list[str],
    conflict_units: list[str],
    nox_only: list[str],
    protected: list[str],
    indirect: set[str],
    preserved_paths: list[str],
    upstream_deleted: list[str],
    protected_deletions: list[str],
) -> None:
    commits = git("rev-list", "--count", f"{base}..{upstream_ref}").strip()
    print(f"\nBase: {base}")
    print(f"Upstream commits: {commits}")
    print(f"Upstream units: {len(upstream_only) + len(conflict_units)}")

    for u in upstream_only:
        print(f"  [upstream only] {u}")
    for u in conflict_units:
        print(f"  [conflict→Nox] {u}")
    for u in upstream_deleted:
        print(f"  [deleted upstream] {u} → REMOVE")
    for u in protected_deletions:
        print(f"  [deleted upstream] {u} → KEEP (explicitly protected)")

    if nox_only:
        print(f"Nox only (preserved): {len(nox_only)}")
        for u in nox_only:
            print(f"  [Nox only] {u}")

    if protected:
        print(f"Nox protected (version guard): {len(protected)}")
        for u in protected:
            print(f"  [protected] {u}")

    if indirect:
        print(f"Indirect impacts via lib/multisrc: {len(indirect)}")
        for u in sorted(indirect):
            print(f"  [indirect] {u}")

    if preserved_paths:
        print(f"Preserved paths: {len(preserved_paths)}")
        for p in preserved_paths:
            print(f"  [preserved] {p}")
    print()


def print_observability_footer(
    sync_result: str,
    push_result: str,
    publication_result: str,
) -> None:
    print("==================================================")
    print(f"SYNC_RESULT={sync_result}")
    print(f"PUSH_RESULT={push_result}")
    print(f"PUBLICATION_RESULT={publication_result}")
    print("==================================================")


def ensure_main_unchanged(start_main: str) -> None:
    """Refuse to publish if main advanced while this sync was running."""
    git("fetch", "origin")
    current_main = git("rev-parse", "origin/main").strip()
    if current_main != start_main:
        raise RuntimeError(
            "origin/main advanced during sync; refusing to publish "
            f"over {start_main} (now {current_main})"
        )


def publish_sync(upstream_ref: str, start_main: str) -> None:
    """Publish main first, then mirror upstream into the sync branch."""
    ensure_main_unchanged(start_main)
    print("Push origin/main: HEAD -> main")
    git("push", "origin", "HEAD:main")
    print("Push origin/main: PASS")
    try:
        git("push", "origin", f"{upstream_ref}:refs/heads/{SYNC_BRANCH}")
        print("Push origin/sync: PASS")
    except Exception as e:
        print(f"Warning: unable to update sync branch ref ({e}); main was successfully pushed.")


# ──────────────────────────────────────────────────────────────────────────────
# Main CLI
# ──────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Sync Yuzono upstream (Nox-wins)")
    parser.add_argument("--dry-run", action="store_true", help="Print plan without modifying files")
    parser.add_argument("--push", action="store_true", help="Commit and push to origin/main and sync")
    args = parser.parse_args()

    if args.dry_run and args.push:
        print("Cannot use --dry-run and --push together", file=sys.stderr)
        sys.exit(1)

    ensure_clean_tree()
    ensure_upstream_remote()

    git("fetch", "origin")
    start_main = git("rev-parse", "origin/main").strip()
    git("fetch", UPSTREAM_REMOTE, UPSTREAM_BRANCH)

    upstream_ref = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}"
    base = git("merge-base", "HEAD", upstream_ref).strip()

    upstream_entries = changed_entries(base, upstream_ref)
    upstream_units, preserved_paths = collect_units(upstream_entries)

    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)

    explicit_delete_protections = get_explicit_delete_protections()
    (
        upstream_only,
        conflict_units,
        nox_only,
        upstream_deleted,
        protected_deletions,
    ) = classify_sync_units(
        base,
        upstream_ref,
        upstream_units,
        main_units,
        explicit_delete_protections,
    )
    conflict_set = set(conflict_units)
    protected = get_nox_protected_units(base, upstream_ref)

    # Self-healing: check if HEAD has pending differences from upstream_ref
    # (e.g. from previously skipped syncs or mass-bumps)
    diff_entries = changed_entries("HEAD", upstream_ref)
    diff_units, _ = collect_units(diff_entries)
    effective_diff = pending_sync_units(diff_units, protected, upstream_ref, base=base)

    if not upstream_units and effective_diff:
        print(f"Notice: Main tree is missing {len(effective_diff)} upstream updates. Proceeding with recovery sync.")
        upstream_units = sorted(effective_diff)
        upstream_only = [u for u in upstream_units if u not in conflict_set]

    indirect = detect_indirect_impacts(upstream_units)

    print_plan(
        base, upstream_ref,
        upstream_only, conflict_units, nox_only,
        protected, indirect, preserved_paths,
        upstream_deleted, protected_deletions,
    )

    new_units = [u for u in upstream_only if not path_exists(base, u)]
    updated_units = [u for u in upstream_only if path_exists(base, u)] + conflict_units
    removed_units = sorted(set(upstream_deleted) | set(protected_deletions))

    core_changed = any(
        path in {"core", "common", "extractor"} or path.startswith(("core/", "common/", "extractor/"))
        for _, paths in upstream_entries
        for path in paths
    )
    multisrc_changed = any(
        path.startswith("lib-multisrc/")
        for _, paths in upstream_entries
        for path in paths
    )
    no_changes = (not upstream_units and not indirect and not upstream_deleted and not effective_diff)

    trigger = os.environ.get("GITHUB_EVENT_NAME", "workflow_dispatch")
    local_head = git("rev-parse", "HEAD").strip()
    upstream_head = git("rev-parse", upstream_ref).strip()
    last_synced = git("rev-parse", f"origin/{SYNC_BRANCH}", check=False).strip() or base

    print_observability_header(
        project="anime",
        trigger=trigger,
        local_head=local_head,
        upstream_remote=UPSTREAM_URL,
        upstream_branch=UPSTREAM_BRANCH,
        upstream_head=upstream_head,
        last_synced_upstream=last_synced,
        new_units=new_units,
        updated_units=updated_units,
        removed_units=removed_units,
        core_changed=core_changed,
        multisrc_changed=multisrc_changed,
        no_changes=no_changes,
    )

    if no_changes:
        print("No upstream changes to apply.")
        if args.push:
            publish_sync(upstream_ref, start_main)
        print_observability_footer("NO_CHANGES", "SKIPPED", "SKIPPED")
        return

    if args.dry_run or not args.push:
        print("Dry run — no changes applied.")
        print_observability_footer("SUCCESS", "SKIPPED", "SKIPPED")
        return

    deployed_codes = fetch_deployed_version_codes()
    units_to_apply = sorted(set(upstream_units) | set(upstream_deleted) | set(protected_deletions))
    bumped = apply_units(
        upstream_ref,
        units_to_apply,
        conflict_set,
        protected,
        upstream_deleted,
        protected_deletions,
        deployed_codes=deployed_codes,
    )
    write_step_summary(protected, bumped)
    publish_sync(upstream_ref, start_main)
    print_observability_footer("SUCCESS", "SUCCESS", "TRIGGERED")


if __name__ == "__main__":
    main()
