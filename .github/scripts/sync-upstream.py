#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
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
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import NamedTuple

class ConcurrencyRaceError(Exception):
    """Raised when origin/main advances concurrently during sync execution."""
    pass


class ExtensionVersion(NamedTuple):
    raw: int
    base: int
    suffix: int
    lib: int

    @property
    def android_code(self) -> int:
        return self.lib * 100000 + self.suffix

    @property
    def name(self) -> str:
        return f"{self.lib}.{self.suffix}"


UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
UPSTREAM_BRANCH = "master"
SYNC_BRANCH = "sync"
SYNC_STATE_FILE = Path(".github/sync-state.json")
NOX_PROTECTED_FILE = Path(".github/nox-protected.txt")

# ──────────────────────────────────────────────────────────────────────────────
# Versioning regex patterns — Groovy & Kotlin DSL
# ──────────────────────────────────────────────────────────────────────────────
_EXT_VERSION_CODE_RE = re.compile(r"extVersionCode\s*=\s*(-?\d+)")
_OVERRIDE_VERSION_CODE_RE = re.compile(r"overrideVersionCode\s*=\s*(-?\d+)")
_KTS_VERSION_CODE_RE = re.compile(r"(?:versionCode|overrideVersionCode)\s*=\s*(-?\d+)")
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


def is_ptbr_unit(unit: str) -> bool:
    """Universal rule: ANY path in src/pt/** is an untouchable PT-BR extension."""
    return unit == "src/pt" or unit.startswith("src/pt/")


def is_preserved(path: str) -> bool:
    """Paths that must NEVER be overwritten by upstream (our CI/scripts/build logic/custom code)."""
    if path == ".github" or path.startswith(".github/"):
        return True
    if path.lower().startswith("readme"):
        return True
    if path in {
        ".gitignore",
        "gradle/build-logic/build.gradle.kts",
        "gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt",
        "gradle/build-logic/src/main/kotlin/ContentWarningLegacy.kt",
        "gradle/build-logic/src/main/kotlin/io/github/keiyoushi/gradle/api/dsl/BaseUrlConfig.kt",
        "gradle/build-logic/src/main/kotlin/io/github/keiyoushi/gradle/api/dsl/ExtensionSource.kt",
        "gradle/kei.versions.toml",
        "settings.gradle.kts",
    }:
        return True
    return False




def get_ptbr_snapshot(ref: str | None = None) -> dict[str, str]:
    """Capture sha256 checksum of every file in src/pt/**."""
    snapshot: dict[str, str] = {}
    if ref is None:
        pt_dir = Path("src/pt")
        if not pt_dir.is_dir():
            return snapshot
        for p in sorted(pt_dir.rglob("*")):
            if p.is_file():
                snapshot[p.as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    else:
        out = git("ls-tree", "-r", ref, "src/pt", check=False)
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 4:
                sha = parts[2]
                path = parts[3]
                snapshot[path] = sha
    return snapshot


def verify_ptbr_unchanged(pre_snapshot: dict[str, str]) -> None:
    """Verify that src/pt/** has EXACTLY zero alterations."""
    post_snapshot = get_ptbr_snapshot(None)
    if pre_snapshot != post_snapshot:
        added = sorted(set(post_snapshot.keys()) - set(pre_snapshot.keys()))
        removed = sorted(set(pre_snapshot.keys()) - set(post_snapshot.keys()))
        modified = sorted(k for k in pre_snapshot.keys() & post_snapshot.keys() if pre_snapshot[k] != post_snapshot[k])
        errors = []
        if added:
            errors.append(f"added {len(added)} files: {added[:5]}")
        if removed:
            errors.append(f"removed {len(removed)} files: {removed[:5]}")
        if modified:
            errors.append(f"modified {len(modified)} files: {modified[:5]}")
        msg = f"CRITICAL: PT-BR protection violated during sync! Alterations detected: {'; '.join(errors)}"
        print(f"::error::{msg}", file=sys.stderr)
        raise RuntimeError(msg)


def get_all_source_units(ref: str | None = None) -> set[str]:
    """Discover all extension source units ('src/<lang>/<ext>')."""
    units = set()
    if ref is None:
        src = Path("src")
        if not src.is_dir():
            return units
        for lang_dir in src.iterdir():
            if not lang_dir.is_dir():
                continue
            for ext_dir in lang_dir.iterdir():
                if not ext_dir.is_dir():
                    continue
                if (ext_dir / "build.gradle.kts").is_file() or (ext_dir / "build.gradle").is_file():
                    units.add(f"src/{lang_dir.name}/{ext_dir.name}")
    else:
        out = git("ls-tree", "-r", "--name-only", ref, "src", check=False)
        for line in out.splitlines():
            parts = line.split("/")
            if len(parts) >= 3 and parts[-1] in {"build.gradle", "build.gradle.kts"}:
                units.add(f"{parts[0]}/{parts[1]}/{parts[2]}")
    return units



def sync_unit(path: str) -> str | None:
    """Normalize a file path to its semantic sync unit (src/<lang>/<name>, lib/<name>, lib-multisrc/<theme>)."""
    if is_preserved(path):
        return None
    parts = path.split("/")
    if len(parts) >= 3 and parts[0] == "src":
        return "/".join(parts[:3])
    if len(parts) >= 2 and parts[0] in {"lib", "lib-multisrc"}:
        return "/".join(parts[:2])
    return None


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
    """Return all explicitly protected units, universally including all local PT-BR sources."""
    base_list = (
        explicit_delete_protections
        if explicit_delete_protections is not None
        else get_explicit_delete_protections()
    )
    pt_local = {u for u in get_all_source_units(None) if is_ptbr_unit(u)}
    return set(base_list) | set(EXPLICIT_LOCKED_UNITS) | pt_local



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
            vc_match = _EXT_VERSION_CODE_RE.search(content)
        raw = int(vc_match.group(1)) if vc_match else 0
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


def effective_version_code(ref: str | None, unit: str) -> ExtensionVersion | None:
    """Read effective version and library compatibility metadata."""
    eff = effective_version(ref, unit)
    if eff is None:
        return None
    raw, base, suffix = eff
    lib = read_lib_version(ref, unit)
    return ExtensionVersion(raw, base, suffix, lib)


def unit_matches_ref_except_version_code(
    unit: str,
    reference: str,
    local_ref: str = "HEAD",
) -> bool:
    """Whether a complete unit differs from a reference only by versionCode."""
    local_tree = git("rev-parse", f"{local_ref}:{unit}", check=False).strip()
    if not local_tree:
        return False
    ref_tree = git("rev-parse", f"{reference}:{unit}", check=False).strip()
    if not ref_tree:
        return False
    if ref_tree == local_tree:
        return True

    changed_paths = git(
        "diff", "--name-only", reference, local_ref, "--", unit, check=False,
    ).splitlines()
    valid_gradle_files = {f"{unit}/build.gradle.kts", f"{unit}/build.gradle"}
    if not changed_paths or any(p not in valid_gradle_files for p in changed_paths):
        return False

    ref_gradle = read_file_at(reference, f"{unit}/build.gradle.kts") or read_file_at(reference, f"{unit}/build.gradle")
    loc_gradle = read_file_at(local_ref, f"{unit}/build.gradle.kts") or read_file_at(local_ref, f"{unit}/build.gradle")
    if ref_gradle is None or loc_gradle is None:
        return False

    def normalize_gradle(text: str) -> str:
        t = text.replace("\r\n", "\n")
        t = re.sub(r"(?:extVersionCode|overrideVersionCode|versionCode)\s*=\s*\d+", "versionCode = <VC>", t)
        return t.strip()

    return normalize_gradle(ref_gradle) == normalize_gradle(loc_gradle)


def matching_upstream_history_revision(
    unit: str,
    upstream_ref: str,
    local_ref: str = "HEAD",
) -> str | None:
    """Return the upstream snapshot matching a complete local unit."""
    revisions = git("log", "--format=%H", upstream_ref, "--", unit, check=False).splitlines()
    for revision in revisions:
        if unit_matches_ref_except_version_code(unit, revision, local_ref):
            return revision
    return None


def local_version_ahead_of_upstream(
    unit: str,
    upstream_ref: str,
    local_ref: str = "HEAD",
) -> tuple[ExtensionVersion, ExtensionVersion] | None:
    """Return local/upstream versions when local version is ahead of upstream."""
    local_info = effective_version_code(local_ref, unit)
    upstream_info = effective_version_code(upstream_ref, unit)
    if local_info is None or upstream_info is None:
        return None
    if local_info.android_code <= upstream_info.android_code:
        return None
    return local_info, upstream_info


def has_newer_upstream_for_version_only_unit(
    unit: str,
    upstream_ref: str,
    local_ref: str = "HEAD",
) -> bool:
    """Whether a version-only local divergence masks newer upstream content."""
    if not unit.startswith("src/"):
        return False
    local_info = effective_version_code(local_ref, unit)
    upstream_info = effective_version_code(upstream_ref, unit)
    if local_info is None or upstream_info is None:
        return False
    if unit_matches_ref_except_version_code(unit, upstream_ref, local_ref):
        return False
    matched_revision = matching_upstream_history_revision(unit, upstream_ref, local_ref)
    if matched_revision is None:
        return True
    matched_info = effective_version_code(matched_revision, unit)
    if matched_info is None:
        return True
    return upstream_info.android_code > matched_info.android_code


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
    all_upstream = get_all_source_units(upstream_ref)
    all_local = get_all_source_units("HEAD")

    # Candidates unite diff units with full inventory
    candidates = upstream_set | main_set | all_upstream | all_local
    historically_upstream = upstream_historical_units(upstream_ref)
    
    explicitly_protected = get_explicit_code_protections(explicit_delete_protections)

    # Missing foreign units that exist upstream but not locally:
    missing_foreign = {
        u for u in all_upstream
        if not is_ptbr_unit(u) and not path_exists("HEAD", u)
    }

    # Divergent foreign units present in both whose functional contents differ:
    divergent_foreign = set()
    for u in all_upstream & all_local:
        if is_ptbr_unit(u):
            continue
        # Expected divergence: if the local unit matches upstream except versionCode
        if unit_matches_ref_except_version_code(u, upstream_ref, "HEAD"):
            continue
        # Expected divergence: if local version is ahead of upstream and upstream has no newer content
        if local_version_ahead_of_upstream(u, upstream_ref) is not None:
            if not has_newer_upstream_for_version_only_unit(u, upstream_ref):
                continue
        diff = git("diff", "--name-only", "HEAD", upstream_ref, "--", u, check=False).strip()
        if diff:
            divergent_foreign.add(u)

    # Deletions: present locally but missing from upstream_ref (existed in base or historically upstream)
    deleted = {
        unit for unit in candidates
        if unit.startswith("src/")
        and path_exists("HEAD", unit)
        and not path_exists(upstream_ref, unit)
        and not is_nox_exclusive(unit, upstream_ref, base=base)
        and (path_exists(base, unit) or unit in historically_upstream)
    }

    deleted_protected = sorted({
        u for u in deleted
        if is_ptbr_unit(u) or u in explicitly_protected
    })
    deleted_remove = sorted({u for u in deleted if u not in deleted_protected})

    protected_updates = {
        unit for unit in (upstream_set & explicitly_protected) - deleted
        if path_exists("HEAD", unit)
    }

    # Conflicts: units with concurrent edits between base and HEAD
    conflicts = sorted(((upstream_set & main_set) | protected_updates) - deleted)
    upstream_only = sorted((set(upstream_units) | missing_foreign | divergent_foreign) - set(conflicts) - deleted - {u for u in candidates if is_ptbr_unit(u)})
    nox_only = sorted(main_set - upstream_set - deleted)
    return upstream_only, conflicts, nox_only, deleted_remove, deleted_protected


def pending_sync_units(
    diff_units: list[str],
    protected_units: list[str],
    upstream_ref: str,
    base: str | None = None,
) -> list[str]:
    """Return HEAD/upstream differences which still need a sync attempt."""
    if base is None:
        base = git("merge-base", "HEAD", upstream_ref).strip()
    upstream_head = git("rev-parse", upstream_ref).strip()

    all_upstream = get_all_source_units(upstream_ref)
    all_local = get_all_source_units("HEAD")
    explicit_protected = set(protected_units) | get_explicit_code_protections()

    pending: set[str] = set()

    # 1. Missing foreign units in local (added upstream)
    for u in all_upstream - all_local:
        if not is_ptbr_unit(u) and u not in explicit_protected:
            pending.add(u)

    # 2. Stale foreign units in local (removed upstream)
    for u in all_local - all_upstream:
        if not is_ptbr_unit(u) and u not in explicit_protected and not is_nox_exclusive(u, upstream_ref, base=base):
            pending.add(u)

    # 3. If upstream has commits not yet merged into HEAD, inspect changed units
    if base != upstream_head:
        upstream_diff_entries = changed_entries(base, upstream_ref)
        upstream_diff_units, _ = collect_units(upstream_diff_entries)
        for u in upstream_diff_units:
            if not is_ptbr_unit(u) and u not in explicit_protected:
                pending.add(u)
        for unit in diff_units:
            if is_ptbr_unit(unit) or unit in explicit_protected or is_nox_exclusive(unit, upstream_ref, base=base):
                continue
            if not path_exists(upstream_ref, unit):
                continue
            if local_version_ahead_of_upstream(unit, upstream_ref) is not None:
                if has_newer_upstream_for_version_only_unit(unit, upstream_ref):
                    print(f"Version-only divergence: applying upstream update for {unit}")
                    pending.add(unit)
                    continue
                print(f"Expected version divergence: local {unit} is ahead of upstream")
                continue
            pending.add(unit)

    return sorted(pending)



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


def fetch_deployed_version_codes() -> dict[str, int]:
    """Fetch currently deployed extension codes from distribution repo with multi-source fallback."""
    candidate_sources = [
        ("url", "https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json"),
        ("url", "https://cdn.jsdelivr.net/gh/Awerkori/anime-extensoes@repo/index.min.json"),
        ("file", str(Path(__file__).resolve().parents[3] / "anime-extensoes/index.min.json")),
        ("file", str(Path(__file__).resolve().parents[2] / "../anime-extensoes/index.min.json")),
    ]
    for kind, target in candidate_sources:
        try:
            raw = None
            if kind == "url":
                import urllib.request
                req = urllib.request.Request(target, headers={"User-Agent": "Nox-Sync/1.0"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    raw = resp.read().decode("utf-8")
            elif kind == "file":
                p = Path(target)
                if p.is_file():
                    raw = p.read_text(encoding="utf-8")

            if raw:
                data = json.loads(raw)
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
                if codes:
                    return codes
        except Exception:
            continue
    print("Notice: unable to fetch deployed version codes; continuing with local evaluation")
    return {}


def bump_version_if_needed(
    unit: str,
    upstream_ref: str,
    pre_sync_effective: tuple[int, int, int] | None = None,
    pre_sync_lib: int | None = None,
    deployed_code: int = 0,
    is_new: bool = False,
    is_protected: bool = False,
    is_indirect: bool = False,
) -> tuple[int, int, int, int, int, int, int] | None:
    """
    Ensure local version code and libVersion are monotonically ahead of upstream and deployed.
    PT-BR units are strictly forbidden from being bumped or modified here.

    Monotonicity Policy:
    - Case A (New unit): If the unit did not exist locally (is_new or pre_sync_effective is None):
      Keep upstream version as-is (16.1 stays 16.1) unless deployed_code >= up_eff.
    - Case B (Unchanged unit): If code matches upstream except versionCode and local >= deployed_code, no bump.
    - Case C (Upstream update): If upstream updated and up_eff > deployed_code, keep upstream version directly;
      only bump to deployed_code + 1 if up_eff <= deployed_code.
    - Case D (Protected unit): Nox modifications win. Bump to max(up_eff + 1, loc_eff + 1) only if upstream >= local.
    - Case E (libVersion upgrade): If upstream libVersion > local libVersion, upgrade libVersion.
    - Case F (Indirect library update): If shared lib changed, bump to max(disk_eff + 1, deployed_code + 1).
    """
    if is_ptbr_unit(unit):
        return None

    local = pre_sync_effective if pre_sync_effective is not None else effective_version(None, unit)
    upstream = effective_version(upstream_ref, unit)

    if local is None and upstream is None:
        return None

    loc_raw, loc_base, loc_eff = local if local is not None else (0, 0, 0)
    up_raw, up_base, up_eff = upstream if upstream is not None else (0, 0, 0)

    loc_lib = pre_sync_lib if pre_sync_lib is not None else read_lib_version(None, unit)
    up_lib = read_lib_version(upstream_ref, unit) if upstream is not None else loc_lib

    disk_eff_tuple = effective_version(None, unit)
    if disk_eff_tuple is None:
        return None
    disk_raw, cur_base, disk_eff = disk_eff_tuple
    disk_lib = read_lib_version(None, unit)

    target_lib = max(loc_lib, up_lib)
    is_lib_upgrade = up_lib > loc_lib
    needs_disk_lib_write = target_lib > disk_lib

    is_brand_new = is_new or (pre_sync_effective is None and not path_exists("HEAD", unit))

    if is_brand_new:
        if deployed_code >= up_eff and deployed_code > 0:
            target_eff = deployed_code + 1
        else:
            target_eff = up_eff
    elif is_protected:
        if up_eff >= loc_eff or is_lib_upgrade or loc_eff < deployed_code:
            target_eff = max(up_eff + 1, loc_eff + 1, deployed_code + 1 if loc_eff < deployed_code else 0)
        else:
            target_eff = loc_eff
    elif is_indirect:
        target_eff = max(disk_eff + 1, deployed_code + 1)
    else:
        if unit_matches_ref_except_version_code(unit, upstream_ref, "HEAD"):
            if disk_eff < deployed_code:
                target_eff = deployed_code + 1
            else:
                target_eff = disk_eff
        else:
            if up_eff > deployed_code:
                target_eff = up_eff
            else:
                target_eff = max(deployed_code + 1, up_eff)

    if target_eff <= disk_eff and not needs_disk_lib_write:
        return None

    new_raw = target_eff - cur_base

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

    if is_kts:
        if needs_disk_lib_write and target_lib > 14:
            if re.search(r'libVersion\s*=\s*["\']\d+["\']', text):
                text = re.sub(r'libVersion\s*=\s*["\']\d+["\']', f'libVersion = "{target_lib}"', text, count=1)
            elif re.search(r'libVersion\s*=\s*\d+', text):
                text = re.sub(r'libVersion\s*=\s*\d+', f'libVersion = "{target_lib}"', text, count=1)
            else:
                marker = re.search(r"(?m)^(\s*)yuzono\s*\{", text)
                if marker:
                    indent = marker.group(1) + "    "
                    text = text[:marker.end()] + f'\n{indent}libVersion = "{target_lib}"' + text[marker.end():]
        if target_eff > disk_eff:
            if re.search(r'(versionCode|overrideVersionCode)\s*=\s*\d+', text):
                text = re.sub(r'(versionCode|overrideVersionCode)\s*=\s*\d+', rf'\g<1> = {new_raw}', text, count=1)
            else:
                marker = re.search(r"(?m)^(\s*)yuzono\s*\{", text)
                if marker:
                    indent = marker.group(1) + "    "
                    field_name = "overrideVersionCode" if theme else "versionCode"
                    text = text[:marker.end()] + f'\n{indent}{field_name} = {new_raw}' + text[marker.end():]
    else:
        if needs_disk_lib_write and target_lib > 14:
            if re.search(r"libVersion\s*=\s*\d+", text):
                text = re.sub(r"libVersion\s*=\s*\d+", f"libVersion = {target_lib}", text, count=1)
            else:
                text = re.sub(r"(ext\s*\{)", rf"\1\n    libVersion = {target_lib}", text, count=1)
        if target_eff > disk_eff:
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
    upstream_deleted: list[str] | None = None,
    protected_deletions: list[str] | None = None,
    deployed_codes: dict[str, int] | None = None,
    new_units: list[str] | None = None,
    updated_units: list[str] | None = None,
    removed_units: list[str] | None = None,
    indirect_units: set[str] | None = None,
) -> list[str]:
    """Apply upstream updates, preserves Nox-protected code, and guarantees version monotonicity."""
    deployed_codes = deployed_codes or {}
    base = git("merge-base", "HEAD", upstream_ref).strip()

    # Pre-sync PT-BR snapshot to verify zero alterations
    pre_ptbr_snapshot = get_ptbr_snapshot(None)

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
        if is_ptbr_unit(unit):
            print(f"Preserving untouchable PT-BR unit: {unit}")
            continue
        if unit in deletion_protection_set:
            print(f"Preserving explicitly protected upstream deletion: {unit}")
            continue
        if unit in explicit_protection_set:
            print(f"Preserving explicitly protected Nox unit: {unit}")
            continue
        if unit in deleted_set:
            print(f"Removing upstream-deleted unit: {unit}")
            git("rm", "-rf", "--ignore-unmatch", "--quiet", "--", unit)
            continue
        if is_nox_exclusive(unit, upstream_ref, base=base):
            print(f"Preserving Nox-exclusive unit: {unit}")
            continue

        if unit in protected:
            if is_ptbr_unit(unit):
                print(f"Preserving untouchable PT-BR unit on conflict: {unit}")
                continue
            # Explicitly protected unit with upstream changes
            up_lib = read_lib_version(upstream_ref, unit)
            loc_lib = pre_sync_lib.get(unit, 14)
            if up_lib > loc_lib:
                print(f"Applying upstream structural lib migration {loc_lib} -> {up_lib} for {unit}")
                git("rm", "-rf", "--ignore-unmatch", "--quiet", "--", unit)
                if path_exists(upstream_ref, unit):
                    git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)
            else:
                print(f"Preserving protected Nox code on conflict: {unit}")
                continue
        else:
            # Common upstream unit (or structural theme)
            print(f"Applying {unit}")
            git("rm", "-rf", "--ignore-unmatch", "--quiet", "--", unit)
            if path_exists(upstream_ref, unit):
                git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

            # Ensure clean directory state if build file changed format (.gradle -> .gradle.kts)
            if path_exists(upstream_ref, f"{unit}/build.gradle.kts"):
                stale_gradle = Path(f"{unit}/build.gradle")
                if stale_gradle.exists():
                    stale_gradle.unlink(missing_ok=True)
                    git("rm", "-f", "--cached", "--ignore-unmatch", "--quiet", "--", str(stale_gradle))

    # Apply global structural updates from upstream (compiler, core, ext-bootstrap)
    for p in ["compiler", "core", "ext-bootstrap.py"]:
        if path_exists(upstream_ref, p):
            if git("diff", "--name-only", "HEAD", upstream_ref, "--", p).strip():
                print(f"Applying global infrastructure update: {p}")
                git("rm", "-rf", "--ignore-unmatch", "--quiet", "--", p)
                git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", p)

    bumped: list[str] = []
    guard_units = sorted(
        {u for u in units_to_apply if u.startswith("src/") and not is_ptbr_unit(u) and u not in explicit_protection_set}
    )

    for unit in guard_units:
        deployed_code = deployed_codes.get(unit, 0)
        res = bump_version_if_needed(
            unit,
            upstream_ref,
            pre_sync_effective=pre_sync_effective.get(unit),
            pre_sync_lib=pre_sync_lib.get(unit),
            deployed_code=deployed_code,
            is_new=(unit in (new_units or []) or unit not in pre_sync_effective),
            is_protected=(unit in protected),
            is_indirect=(unit in (indirect_units or set())),
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

    # Verify PT-BR snapshot integrity: MUST be 100% byte-for-byte identical
    verify_ptbr_unchanged(pre_ptbr_snapshot)

    git("diff", "--check")

    commit_msg = "Sync upstream"
    details = []
    if new_units:
        details.append("Added extensions:\n" + "\n".join(f"  - {u}" for u in sorted(new_units)))
    if updated_units:
        details.append("Updated extensions:\n" + "\n".join(f"  - {u}" for u in sorted(updated_units)))
    if removed_units:
        details.append("Removed extensions:\n" + "\n".join(f"  - {u}" for u in sorted(removed_units)))
    if bumped:
        details.append("Nox-wins version bumps:\n" + "\n".join(f"  - {b}" for b in bumped))
    if details:
        commit_msg += "\n\n" + "\n\n".join(details)
    git("commit", "-m", commit_msg)
    return bumped



def write_step_summary(
    protected: list[str],
    bumped: list[str],
    new_units: list[str] | None = None,
    updated_units: list[str] | None = None,
    removed_units: list[str] | None = None,
    sync_result: str = "SUCCESS",
) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    new_units = new_units or []
    updated_units = updated_units or []
    removed_units = removed_units or []
    lines = [f"\n## Project Nox Anime — Upstream Sync Status: `{sync_result}`\n\n"]
    if new_units:
        lines.append(f"### Added Extensions ({len(new_units)})\n\n")
        for u in sorted(new_units):
            lines.append(f"- `{u}` (imported from upstream)\n")
        lines.append("\n")
    if updated_units:
        lines.append(f"### Updated Extensions ({len(updated_units)})\n\n")
        for u in sorted(updated_units):
            lines.append(f"- `{u}`\n")
        lines.append("\n")
    if removed_units:
        lines.append(f"### Removed Extensions ({len(removed_units)})\n\n")
        for u in sorted(removed_units):
            lines.append(f"- `{u}` (removed upstream)\n")
        lines.append("\n")
    if protected:
        lines.append("### Nox Protected Extensions\n\n")
        for u in protected:
            tag = next((b for b in bumped if b.startswith(u)), None)
            if tag:
                lines.append(f"- `{u}` — kept Nox code, bumped to `{tag.split('→')[1].strip()}`\n")
            else:
                lines.append(f"- `{u}` — kept Nox code, version already ahead\n")
        lines.append("\n")
    with open(path, "a", encoding="utf-8") as f:
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
        raise ConcurrencyRaceError(
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


def get_publish_pending_units(
    base: str | None = None,
    upstream_ref: str = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}",
    catalog_target: str | None = None,
    distribution_dir: Path | str | None = None,
) -> list[str]:
    """
    Detect units where source exists locally in Nox, but the extension is missing
    or incomplete in the deployed distribution catalog or APK artifacts.
    """
    try:
        import importlib.util
        script_path = Path(__file__).resolve().parent / "check-consistency.py"
        spec = importlib.util.spec_from_file_location("check_consistency_mod", str(script_path))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        data, _, status = mod.get_deployed_catalog(catalog_target)
        if not data or status != "VERIFIED":
            return []

        deployed_by_unit = {}
        for item in data:
            pkg = item.get("pkg", "")
            prefix = "eu.kanade.tachiyomi.animeextension."
            if pkg.startswith(prefix):
                parts = pkg[len(prefix):].split(".")
                if len(parts) >= 2:
                    deployed_by_unit[f"src/{parts[0]}/{parts[1]}"] = item

        dist_path = Path(distribution_dir) if distribution_dir else None
        local_apk_dir = dist_path / "apk" if dist_path else None

        all_codes_are_one = bool(data and all(item.get("code") == 1 for item in data))

        pending = []
        all_local = get_all_source_units(None)
        for u in sorted(all_local):
            if u not in deployed_by_unit:
                pending.append(u)
                continue
            dep = deployed_by_unit[u]
            if not all_codes_are_one:
                eff = effective_version(None, u)
                if eff is not None:
                    local_eff_code = eff[2]
                    dep_code = dep.get("code", 0)
                    if local_eff_code > dep_code:
                        pending.append(u)
                        continue
            if local_apk_dir and local_apk_dir.is_dir():
                apk_name = dep.get("apk")
                if not apk_name:
                    pending.append(u)
                    continue
                apk_path = local_apk_dir / apk_name
                if not apk_path.is_file() or apk_path.stat().st_size == 0:
                    pending.append(u)
                    continue
        return pending
    except Exception as e:
        print(f"Notice: get_publish_pending_units encountered {e}", file=sys.stderr)
        return []


def recover_pending_publications(
    units: list[str],
    push: bool = False,
    start_main: str | None = None,
    upstream_ref: str | None = None,
    distribution_dir: Path | str | None = None,
    staging_dir: Path | str | None = None,
) -> str:
    """
    Recover incomplete publication for specified units:
    1. Locate or produce valid APK artifact for each pending unit.
    2. Verify package, version, and presence.
    3. Update distribution index.min.json, index.json, index.html.
    4. Commit and push recovered artifacts to distribution repo (if push=True and local clone exists).
    5. Or dispatch build_push.yml workflow via GitHub API if remote.

    Returns "RECOVERED", "DISPATCHED", or "FAILED".
    """
    if not units:
        return "RECOVERED"

    print(f"Starting publication recovery for {len(units)} units: {', '.join(units)}")

    dist_path = None
    if distribution_dir:
        dist_path = Path(distribution_dir)
    else:
        for candidate in [
            Path.cwd() / "repo",
            Path(__file__).resolve().parents[3] / "anime-extensoes",
            Path(__file__).resolve().parents[2] / "../anime-extensoes",
            Path("/home/awerkori/.Projects/Project-Nox-Anime/anime-extensoes"),
        ]:
            if candidate.is_dir() and (candidate / "index.min.json").is_file():
                dist_path = candidate
                break

    # If distribution repository is accessible locally, recover directly
    if dist_path and (dist_path / "index.min.json").is_file():
        apk_dir = dist_path / "apk"
        apk_dir.mkdir(parents=True, exist_ok=True)
        icon_dir = dist_path / "icon"
        icon_dir.mkdir(parents=True, exist_ok=True)

        try:
            with open(dist_path / "index.json", "r", encoding="utf-8") as f:
                full_catalog = json.load(f)
        except Exception:
            full_catalog = []

        try:
            with open(dist_path / "index.min.json", "r", encoding="utf-8") as f:
                min_catalog = json.load(f)
        except Exception:
            min_catalog = []

        full_by_pkg = {item["pkg"]: item for item in full_catalog if isinstance(item, dict) and "pkg" in item}
        min_by_pkg = {item["pkg"]: item for item in min_catalog if isinstance(item, dict) and "pkg" in item}

        deployed_apks = []

        for u in units:
            parts = u.split("/")
            if len(parts) < 3:
                continue
            lang, ext = parts[1], parts[2]
            pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{ext}"

            eff = effective_version(None, u)
            code = eff[2] if eff else 1
            lib = read_lib_version(None, u)
            version_str = f"{lib}.{code}"
            for fn in ("build.gradle.kts", "build.gradle"):
                bf = Path(f"{u}/{fn}")
                if bf.is_file():
                    content_gradle = bf.read_text(encoding="utf-8", errors="ignore")
                    m = re.search(r"""extVersion\s*=\s*['"]([^'"]+)['"]""", content_gradle)
                    if m:
                        version_str = m.group(1)

            found_apk = None
            candidate_apk_dirs = []
            if staging_dir:
                candidate_apk_dirs.append(Path(staging_dir))
            candidate_apk_dirs.extend([
                Path(f"{u}/build/outputs/apk/release"),
                Path("repo/apk"),
                apk_dir,
            ])

            apk_expected_prefix = f"aniyomi-{lang}.{ext}-v"
            for c_dir in candidate_apk_dirs:
                if c_dir.is_dir():
                    for f in c_dir.glob("*.apk"):
                        if f.name.startswith(apk_expected_prefix) and f.stat().st_size > 0:
                            found_apk = f
                            break
                if found_apk:
                    break

            if not found_apk and Path("./gradlew").is_file() and os.access("./gradlew", os.X_OK):
                print(f"Compiling APK for {u} via Gradle...")
                mod_selector = f":src:{lang}:{ext}:assembleRelease"
                res = subprocess.run(["./gradlew", mod_selector], capture_output=True, text=True)
                if res.returncode == 0:
                    out_dir = Path(f"{u}/build/outputs/apk/release")
                    if out_dir.is_dir():
                        for f in out_dir.glob("*.apk"):
                            if f.name.startswith(apk_expected_prefix) and f.stat().st_size > 0:
                                found_apk = f
                                break

            if not found_apk or not found_apk.is_file() or found_apk.stat().st_size == 0:
                print(f"Error: legitimate APK artifact for {u} cannot be located or produced.", file=sys.stderr)
                return "FAILED"

            apk_name = found_apk.name
            if "-v" in apk_name:
                v_part = apk_name.split("-v")[-1].replace(".apk", "")
                if v_part:
                    version_str = v_part

            dest_apk = apk_dir / apk_name
            if found_apk.resolve() != dest_apk.resolve():
                shutil.copy2(found_apk, dest_apk)
            print(f"Artifact deployed: {dest_apk.name} ({dest_apk.stat().st_size} bytes)")
            deployed_apks.append(dest_apk.name)

            # Preserve full-index source metadata if already present
            existing_min = min_by_pkg.get(pkg)
            existing_full = full_by_pkg.get(pkg)

            item_min = existing_min.copy() if existing_min else {
                "name": f"Aniyomi: {ext.capitalize()}",
                "pkg": pkg,
                "apk": apk_name,
                "lang": lang,
                "code": code,
                "version": version_str,
                "nsfw": 0,
                "sources": [{"name": ext.capitalize(), "lang": lang, "id": str(abs(hash(pkg))), "baseUrl": ""}],
            }
            item_min["apk"] = apk_name
            item_min["code"] = code
            item_min["version"] = version_str

            item_full = existing_full.copy() if existing_full else dict(item_min)
            item_full["apk"] = apk_name
            item_full["code"] = code
            item_full["version"] = version_str

            min_by_pkg[pkg] = item_min
            full_by_pkg[pkg] = item_full

        sorted_min = sorted(min_by_pkg.values(), key=lambda x: x["pkg"])
        sorted_full = sorted(full_by_pkg.values(), key=lambda x: x["pkg"])

        with open(dist_path / "index.min.json", "w", encoding="utf-8") as f:
            json.dump(sorted_min, f, ensure_ascii=False, separators=(",", ":"))
        with open(dist_path / "index.json", "w", encoding="utf-8") as f:
            json.dump(sorted_full, f, ensure_ascii=False, indent=2)

        with open(dist_path / "index.html", "w", encoding="utf-8") as f:
            import html
            f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
            for entry in sorted_min:
                apk_esc = 'apk/' + html.escape(entry["apk"])
                name_esc = html.escape(entry["name"])
                f.write(f'<a href="{apk_esc}">{name_esc}</a>\n')
            f.write('</pre>\n</body>\n</html>\n')

        # Verify correspondence between artifacts and catalog
        for u in units:
            parts = u.split("/")
            if len(parts) < 3:
                continue
            pkg = f"eu.kanade.tachiyomi.animeextension.{parts[1]}.{parts[2]}"
            if pkg not in min_by_pkg:
                print(f"Error: {pkg} not found in index.min.json after recovery", file=sys.stderr)
                return "FAILED"
            apk_file = apk_dir / min_by_pkg[pkg]["apk"]
            if not apk_file.is_file() or apk_file.stat().st_size == 0:
                print(f"Error: APK artifact {apk_file} missing or empty after recovery", file=sys.stderr)
                return "FAILED"

        if push and (dist_path / ".git").is_dir():
            files_to_stage = ["index.min.json", "index.json", "index.html"]
            for apk_n in deployed_apks:
                apk_rel = f"apk/{apk_n}"
                if (dist_path / apk_rel).is_file():
                    files_to_stage.append(apk_rel)

            subprocess.run(["git", "-C", str(dist_path), "add", "--"] + files_to_stage, check=True)
            git_status = subprocess.run(["git", "-C", str(dist_path), "diff", "--cached", "--name-only"], capture_output=True, text=True).stdout
            if git_status.strip():
                msg = f"Recover publication for {', '.join(units)}"
                subprocess.run(["git", "-C", str(dist_path), "commit", "-m", msg], check=True)
                subprocess.run(["git", "-C", str(dist_path), "push", "origin", "repo"], check=True)
                print(f"Pushed publication recovery to distribution repo: {msg}")

        return "RECOVERED"

    # If distribution repository is remote (GitHub Actions runner without repo clone):
    # Dispatch build_push.yml workflow via GitHub API to build and publish pending units
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if gh_token:
        try:
            repo_slug = os.environ.get("GITHUB_REPOSITORY", "Awerkori/anime-fonte-extensoes")
            url = f"https://api.github.com/repos/{repo_slug}/actions/workflows/build_push.yml/dispatches"
            req = urllib.request.Request(
                url,
                data=json.dumps({"ref": "main"}).encode("utf-8"),
                headers={
                    "Authorization": f"token {gh_token}",
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "Nox-Sync/1.0",
                },
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                print(f"Dispatched build_push.yml for pending units (HTTP {resp.status}).")
            return "DISPATCHED"
        except Exception as e:
            print(f"Warning: unable to dispatch build_push.yml: {e}", file=sys.stderr)
            return "FAILED"

    return "FAILED"
# ──────────────────────────────────────────────────────────────────────────────
# Main CLI
# ──────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Sync Yuzono upstream (Nox-wins)")
    parser.add_argument("--dry-run", action="store_true", help="Print plan without modifying files")
    parser.add_argument("--push", action="store_true", help="Commit and push to origin/main and sync")
    parser.add_argument("--catalog-file", type=str, default=None, help="Path to catalog index.min.json")
    parser.add_argument("--distribution-dir", type=str, default=None, help="Path to distribution repository")
    parser.add_argument("--staging-dir", type=str, default=None, help="Path to APK staging directory")
    parser.add_argument("--recover-pending", action="store_true", help="Force publication recovery")
    args = parser.parse_args()

    if args.dry_run and args.push:
        print("Cannot use --dry-run and --push together", file=sys.stderr)
        sys.exit(1)

    if not args.dry_run:
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

    if effective_diff:
        print(f"Notice: Main tree has {len(effective_diff)} pending inventory/upstream updates.")
        upstream_units = sorted(set(upstream_units) | set(effective_diff))
        upstream_only = sorted((set(upstream_only) | set(effective_diff)) - conflict_set - set(upstream_deleted) - set(protected_deletions) - {u for u in effective_diff if is_ptbr_unit(u)})

    indirect = detect_indirect_impacts(upstream_units)

    print_plan(
        base, upstream_ref,
        upstream_only, conflict_units, nox_only,
        protected, indirect, preserved_paths,
        upstream_deleted, protected_deletions,
    )

    new_units = [u for u in upstream_only if not path_exists(base, u)]
    indirect_clean = sorted({u for u in indirect if not is_ptbr_unit(u) and path_exists("HEAD", u)})
    updated_units = sorted(set([u for u in upstream_only if path_exists(base, u)] + conflict_units) | set(indirect_clean))
    removed_units = sorted({u for u in upstream_deleted if not is_ptbr_unit(u) and u not in protected_deletions})

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

    pending_pub = get_publish_pending_units(
        base=base,
        upstream_ref=upstream_ref,
        catalog_target=args.catalog_file,
        distribution_dir=args.distribution_dir,
    )

    if no_changes and pending_pub:
        print("==================================================")
        print("PUBLICATION_PENDING_DETECTED")
        print(f"Pending publication units ({len(pending_pub)}): {', '.join(pending_pub)}")
        print("==================================================")

        if args.dry_run or (not args.push and not args.recover_pending):
            print("Dry run — publication recovery planned without modification.")
            print_observability_footer("NO_CHANGES", "SKIPPED", "SKIPPED")
            return

        recovery_status = recover_pending_publications(
            pending_pub,
            push=args.push,
            start_main=start_main,
            upstream_ref=upstream_ref,
            distribution_dir=args.distribution_dir,
            staging_dir=args.staging_dir,
        )

        if args.push:
            try:
                publish_sync(upstream_ref, start_main)
            except ConcurrencyRaceError as e:
                print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                sys.exit(75)

        if recovery_status == "RECOVERED":
            print("==================================================")
            print("PUBLICATION_RECOVERED")
            print("==================================================")
            write_step_summary(
                protected=protected,
                bumped=[],
                new_units=[],
                updated_units=[],
                removed_units=[],
                sync_result="SUCCESS",
            )
            print_observability_footer("NO_CHANGES", "SUCCESS", "RECOVERED")
            return
        elif recovery_status == "DISPATCHED":
            print("Publication recovery dispatched to CI workflow.")
            print_observability_footer("NO_CHANGES", "SUCCESS", "TRIGGERED")
            return
        else:
            print("Publication recovery failed: artifact unavailable or workflow dispatch failed.", file=sys.stderr)
            print_observability_footer("NO_CHANGES", "FAILURE", "FAILED")
            sys.exit(1)

    if no_changes:
        print("No upstream changes to apply.")
        if args.push:
            try:
                publish_sync(upstream_ref, start_main)
            except ConcurrencyRaceError as e:
                print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                sys.exit(75)
        print_observability_footer("NO_CHANGES", "SKIPPED", "SKIPPED")
        return

    if args.dry_run or not args.push:
        print("Dry run — no changes applied.")
        print_observability_footer("SUCCESS", "SKIPPED", "SKIPPED")
        return

    deployed_codes = fetch_deployed_version_codes()
    units_to_apply = sorted(set(upstream_units) | set(upstream_deleted) | set(protected_deletions) | set(effective_diff) | set(indirect_clean))
    bumped = apply_units(
        upstream_ref,
        units_to_apply,
        conflict_set,
        protected,
        upstream_deleted,
        protected_deletions,
        deployed_codes=deployed_codes,
        new_units=new_units,
        updated_units=updated_units,
        removed_units=removed_units,
        indirect_units=set(indirect_clean),
    )
    write_step_summary(
        protected=protected,
        bumped=bumped,
        new_units=new_units,
        updated_units=updated_units,
        removed_units=removed_units,
        sync_result="SUCCESS",
    )
    try:
        publish_sync(upstream_ref, start_main)
    except ConcurrencyRaceError as e:
        print(f"Transient concurrency conflict: {e}", file=sys.stderr)
        sys.exit(75)
    print_observability_footer("SUCCESS", "SUCCESS", "TRIGGERED")



if __name__ == "__main__":
    main()
