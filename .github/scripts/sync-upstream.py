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
import tempfile
import time
import tomllib
import urllib.error
import urllib.request
from pathlib import Path
from typing import NamedTuple

class ConcurrencyRaceError(Exception):
    """Raised when origin/main advances concurrently during sync execution."""
    pass


class TransientNetworkError(RuntimeError):
    """Raised when Gradle/network fails transiently (HTTP 429/5xx, timeouts)."""
    pass


class StructuralIncompatibilityError(RuntimeError):
    """Raised when shared infra, catalogs, or modules fail structural validation."""

    def __init__(self, message: str, failed_units: list[str] | None = None):
        super().__init__(message)
        self.failed_units = failed_units or []


EXIT_TRANSIENT_CONCURRENCY = 75
EXIT_TRANSIENT_NETWORK = 76
STATE_EXCLUDED_PATHS = (
    ":!.github/sync-state.json",
    ":!.github/sync-deferred-incompatibilities.json",
)


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
DEFERRED_INCOMPATIBILITIES_FILE = Path(".github/sync-deferred-incompatibilities.json")
NOX_PROTECTED_FILE = Path(".github/nox-protected.txt")
LIBS_CATALOG_FILE = Path("gradle/libs.versions.toml")
KEI_CATALOG_FILE = Path("gradle/kei.versions.toml")
SETTINGS_FILE = Path("settings.gradle.kts")
INFRA_EXACT_PATHS = {
    "core",
    "compiler",
    "extensions-lib",
    "common",
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts",
    "ext-bootstrap.py",
}
INFRA_PREFIXES = ("core/", "compiler/", "extensions-lib/", "common/", "gradle/")
MAX_TRANSIENT_PUB_RETRIES = 3

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
    path_lower = path.lower()
    if (
        path_lower.startswith("readme")
        or path_lower.startswith("contributing")
        or path_lower.startswith("license")
        or path_lower.startswith("notice")
    ):
        return True
    if path in {
        ".gitignore",
        "gradle/libs.versions.toml",
        "gradle/build-logic/build.gradle.kts",
        "gradle/build-logic/src/main/kotlin/ExtensionPlugin.kt",
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
    """Capture sha256 checksum of every source/config file in src/pt/**."""
    snapshot: dict[str, str] = {}
    if ref is None:
        pt_dir = Path("src/pt")
        if not pt_dir.is_dir():
            return snapshot
        for p in sorted(pt_dir.rglob("*")):
            if p.is_file():
                rel_parts = p.relative_to(pt_dir).parts
                if any(part in {"build", ".cxx", ".gradle", ".kotlin"} for part in rel_parts):
                    continue
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
        ("url", "https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.min.json"),
        ("url", "https://cdn.jsdelivr.net/gh/ProjectNox-org/anime-extensoes@repo/index.min.json"),
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
# Sync State, Structural Reconciliation & Validation
# ──────────────────────────────────────────────────────────────────────────────

_COMMENT_OR_STRING_RE = re.compile(
    r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"',
    re.DOTALL,
)
_CATALOG_REF_RE = re.compile(r"\b(libs|kei)\.([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)*)")
_SETTINGS_INCLUDE_RE = re.compile(r'\binclude\s*\(\s*"(:?[^"$]+)"\s*\)')
_LOCAL_PROJECT_RE = re.compile(r"""project\s*\(\s*(?:path\s*=\s*)?["'](:[^"']+)["']""")
_TOML_SECTION_RE = re.compile(r"^\s*\[([a-zA-Z0-9_.-]+)\]\s*(?:#.*)?$")
_TOML_ASSIGN_RE = re.compile(r"^\s*([A-Za-z0-9_-]+)\s*=\s*(.*)$")
_CATALOG_PROVIDER_SUFFIXES = {
    "get",
    "orNull",
    "orElse",
    "version",
    "pluginId",
    "requiredVersion",
    "strictVersion",
    "preferredVersion",
    "displayName",
    "asProvider",
}
_CATALOG_IGNORED_PREFIXES = {
    "libs.versions",
    "libs.bundles",
    "libs.plugins",
    "kei.versions",
    "kei.bundles",
    "kei.plugins",
    "libs.class",
    "kei.class",
    "libs.java",
    "kei.java",
    "libs.extensionsLib",
    "libs.ktlint",
}
_TRANSIENT_GRADLE_MARKERS = (
    "Could not GET",
    "Could not HEAD",
    "Could not download",
    "Could not get resource",
    "Read timed out",
    "Connection reset",
    "Connection timed out",
    "Temporary failure in name resolution",
    "UnknownHostException",
    "Received status code 429",
    "Received status code 500",
    "Received status code 502",
    "Received status code 503",
    "Received status code 504",
    "status code: 429",
    "status code: 500",
    "status code: 502",
    "status code: 503",
    "status code: 504",
)
_GRADLE_FAILED_TASK_RE = re.compile(
    r"(?:> Task :((?:src:[^:]+:[^:]+)|(?:lib:[^:]+)|(?:lib-multisrc:[^:]+)|[a-zA-Z0-9_-]+):([a-zA-Z0-9]+)\s+FAILED|Execution failed for task ':((?:src:[^:]+:[^:]+)|(?:lib:[^:]+)|(?:lib-multisrc:[^:]+)|[a-zA-Z0-9_-]+):([a-zA-Z0-9]+)')",
)
_KOTLIN_ERROR_RE = re.compile(r"e:\s+file:///(.+?):(\d+):(\d+)\s*(.+)")


def set_github_output(key: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def merge_in_progress() -> bool:
    return subprocess.run(
        ["git", "rev-parse", "-q", "--verify", "MERGE_HEAD"],
        capture_output=True,
    ).returncode == 0


def get_code_sha(ref: str = "HEAD") -> str:
    """Return latest commit SHA touching any file other than sync-state files."""
    out = git(
        "log",
        "-1",
        "--format=%H",
        ref,
        "--",
        ".",
        *STATE_EXCLUDED_PATHS,
        check=False,
    ).strip()
    if out:
        return out
    return git("rev-parse", ref, check=False).strip()


def load_sync_state() -> dict:
    if SYNC_STATE_FILE.exists():
        try:
            data = json.loads(SYNC_STATE_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except Exception:
            pass
    return {}


def save_sync_state(state: dict) -> None:
    SYNC_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    SYNC_STATE_FILE.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def load_deferred_incompatibilities() -> dict[str, dict[str, object]]:
    if not DEFERRED_INCOMPATIBILITIES_FILE.exists():
        return {}
    try:
        data = json.loads(DEFERRED_INCOMPATIBILITIES_FILE.read_text(encoding="utf-8"))
        return data.get("incompatibilities", {})
    except (json.JSONDecodeError, OSError):
        return {}


def write_deferred_incompatibilities(incompatibilities: dict[str, dict[str, object]]) -> bool:
    if not incompatibilities:
        if DEFERRED_INCOMPATIBILITIES_FILE.exists():
            DEFERRED_INCOMPATIBILITIES_FILE.unlink()
            return True
        return False
    DEFERRED_INCOMPATIBILITIES_FILE.parent.mkdir(parents=True, exist_ok=True)
    payload = {"incompatibilities": incompatibilities}
    DEFERRED_INCOMPATIBILITIES_FILE.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return True


def compute_deterministic_signature(
    code_sha: str,
    upstream_head: str,
    failure_type: str,
    units: list[str],
    cause: str,
) -> str:
    seed = f"{code_sha}:{upstream_head}:{failure_type}:{','.join(sorted(units))}:{cause[:300]}"
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()


def commit_state_only(commit_msg: str, push: bool = False, start_main: str | None = None) -> None:
    """Persist sync-state.json updates with [skip ci] without advancing code_sha or triggering CI."""
    if not SYNC_STATE_FILE.exists():
        return
    status = git("status", "--porcelain", "--", str(SYNC_STATE_FILE), check=False).strip()
    if not status:
        return
    git("add", "--", str(SYNC_STATE_FILE))
    diff_cached = subprocess.run(["git", "diff", "--cached", "--quiet", "--", str(SYNC_STATE_FILE)])
    if diff_cached.returncode == 0:
        return
    git("commit", "-m", f"[skip ci] {commit_msg}")
    if push:
        if start_main:
            ensure_main_unchanged(start_main)
        git("push", "origin", "HEAD:main")


def _strip_comments_preserving_newlines(text: str, strip_strings: bool = False) -> str:
    def _replacer(match: re.Match[str]) -> str:
        token = match.group(0)
        if not strip_strings and token.startswith('"'):
            return token
        return "\n" * token.count("\n")

    return _COMMENT_OR_STRING_RE.sub(_replacer, text)


def discover_local_top_level_modules(root: Path = Path(".")) -> list[Path]:
    """Discover all top-level Gradle module directories containing build.gradle(.kts)."""
    modules: list[Path] = []
    for entry in sorted(root.iterdir()):
        if not entry.is_dir() or entry.name in {".git", ".gradle", "build", "gradle", "src", "lib", "lib-multisrc"}:
            continue
        if (entry / "build.gradle.kts").is_file() or (entry / "build.gradle").is_file():
            modules.append(entry)
    return modules


def discover_upstream_top_level_modules(upstream_ref: str) -> set[str]:
    """Discover top-level Gradle module directories present in upstream_ref."""
    out = git("ls-tree", "-r", "--name-only", upstream_ref, check=False)
    modules: set[str] = set()
    for line in out.splitlines():
        parts = line.split("/")
        if len(parts) == 2 and parts[1] in {"build.gradle.kts", "build.gradle"}:
            top = parts[0]
            if top not in {"gradle", "src", "lib", "lib-multisrc", "build"}:
                modules.add(top)
    return modules


def _catalog_accessor_paths(data: dict[str, object]) -> set[str]:
    def _norm(key: str) -> str:
        return re.sub(r"[-_.]+", ".", key)

    paths: set[str] = set()
    for section, prefix in (
        ("libraries", ""),
        ("versions", "versions."),
        ("bundles", "bundles."),
        ("plugins", "plugins."),
    ):
        entries = data.get(section, {})
        if isinstance(entries, dict):
            for key in entries:
                paths.add(f"{prefix}{_norm(str(key))}")
    return paths


def _extract_toml_section_blocks(toml_text: str) -> dict[str, dict[str, str]]:
    blocks: dict[str, dict[str, str]] = {}
    current_section: str | None = None
    lines = toml_text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        sec_m = _TOML_SECTION_RE.match(line)
        if sec_m:
            current_section = sec_m.group(1)
            blocks.setdefault(current_section, {})
            i += 1
            continue
        assign_m = _TOML_ASSIGN_RE.match(line)
        if current_section and assign_m and not line.lstrip().startswith("#"):
            key = assign_m.group(1)
            stmt_lines = [line]
            balance = line.count("[") - line.count("]") + line.count("{") - line.count("}")
            while balance > 0 and i + 1 < len(lines):
                i += 1
                stmt_lines.append(lines[i])
                balance += lines[i].count("[") - lines[i].count("]") + lines[i].count("{") - lines[i].count("}")
            blocks[current_section][key] = "\n".join(stmt_lines)
        i += 1
    return blocks


def _insert_toml_statements(toml_text: str, additions_by_section: dict[str, list[str]]) -> str:
    lines = toml_text.splitlines()
    pending = {sec: list(stmts) for sec, stmts in additions_by_section.items() if stmts}
    if not pending:
        return toml_text

    out: list[str] = []
    current_section: str | None = None

    def _flush_section(sec: str | None) -> None:
        if sec and sec in pending:
            trailing_blanks = 0
            while out and not out[-1].strip():
                out.pop()
                trailing_blanks += 1
            out.extend(pending.pop(sec))
            out.extend([""] * trailing_blanks)

    for line in lines:
        sec_m = _TOML_SECTION_RE.match(line)
        if sec_m:
            _flush_section(current_section)
            current_section = sec_m.group(1)
        out.append(line)

    _flush_section(current_section)
    for sec, stmts in pending.items():
        if out and out[-1].strip():
            out.append("")
        out.append(f"[{sec}]")
        out.extend(stmts)

    return "\n".join(out) + "\n"


def _format_toml_value(key: str, value: object) -> str:
    if isinstance(value, str):
        return f'{key} = "{value}"'
    if isinstance(value, list):
        items = ", ".join(f'"{item}"' for item in value)
        return f"{key} = [{items}]"
    if isinstance(value, dict):
        parts = []
        for sub_k, sub_v in value.items():
            if isinstance(sub_v, str):
                parts.append(f'{sub_k} = "{sub_v}"')
            else:
                parts.append(f"{sub_k} = {json.dumps(sub_v)}")
        return f"{key} = {{ {', '.join(parts)} }}"
    return f"{key} = {json.dumps(value)}"


def reconcile_version_catalog(
    upstream_ref: str,
    catalog_file: Path = LIBS_CATALOG_FILE,
    *,
    apply: bool = True,
) -> bool:
    """Selectively integrate missing upstream entries into a TOML catalog without clobbering Nox pins."""
    if not catalog_file.is_file():
        return False
    upstream_text = read_file_at(upstream_ref, catalog_file.as_posix())
    if not upstream_text:
        return False
    local_text = catalog_file.read_text(encoding="utf-8")

    try:
        local_data = tomllib.loads(local_text)
        upstream_data = tomllib.loads(upstream_text)
    except tomllib.TOMLDecodeError:
        return False

    upstream_blocks = _extract_toml_section_blocks(upstream_text)
    additions: dict[str, list[str]] = {}

    local_versions = local_data.get("versions", {})
    upstream_versions = upstream_data.get("versions", {})

    for key, val in upstream_versions.items():
        if key not in local_versions:
            stmt = upstream_blocks.get("versions", {}).get(key) or _format_toml_value(key, val)
            additions.setdefault("versions", []).append(stmt)

    for section in ("libraries", "plugins"):
        local_sec = local_data.get(section, {})
        upstream_sec = upstream_data.get(section, {})
        for key, val in upstream_sec.items():
            if key not in local_sec:
                if isinstance(val, dict) and "version.ref" in val:
                    ref_name = val["version.ref"]
                    if ref_name not in local_versions and ref_name in upstream_versions:
                        if not any(
                            s.startswith(f"{ref_name} ") or s.startswith(f"{ref_name}=")
                            for s in additions.get("versions", [])
                        ):
                            stmt_v = upstream_blocks.get("versions", {}).get(ref_name) or _format_toml_value(
                                ref_name, upstream_versions[ref_name]
                            )
                            additions.setdefault("versions", []).append(stmt_v)
                stmt = upstream_blocks.get(section, {}).get(key) or _format_toml_value(key, val)
                additions.setdefault(section, []).append(stmt)

    updated_text = _insert_toml_statements(local_text, additions)

    local_bundles = local_data.get("bundles", {})
    upstream_bundles = upstream_data.get("bundles", {})
    bundle_additions: list[str] = []
    for bundle_name, up_items in upstream_bundles.items():
        if not isinstance(up_items, list):
            continue
        if bundle_name not in local_bundles:
            bundle_additions.append(_format_toml_value(bundle_name, up_items))
        elif isinstance(local_bundles[bundle_name], list):
            missing_items = [item for item in up_items if item not in local_bundles[bundle_name]]
            if missing_items:
                merged_items = [*local_bundles[bundle_name], *missing_items]
                new_bundle_line = _format_toml_value(bundle_name, merged_items)
                pattern = re.compile(rf"(?m)^[ \t]*{re.escape(bundle_name)}\s*=\s*\[[^\]]*\]")
                updated_text = pattern.sub(new_bundle_line, updated_text, count=1)

    if bundle_additions:
        updated_text = _insert_toml_statements(updated_text, {"bundles": bundle_additions})

    if updated_text == local_text:
        return False

    tomllib.loads(updated_text)
    if apply:
        catalog_file.write_text(updated_text, encoding="utf-8")
        print(f"Reconciled missing entries in {catalog_file.as_posix()} from upstream while preserving Nox pins")
    return True


def reconcile_settings_modules(upstream_ref: str, *, apply: bool = True) -> bool:
    """Selectively add new upstream include(...) modules to settings.gradle.kts if their dir exists."""
    if not SETTINGS_FILE.is_file():
        return False
    upstream_text = read_file_at(upstream_ref, str(SETTINGS_FILE))
    if not upstream_text:
        return False
    local_text = SETTINGS_FILE.read_text(encoding="utf-8")

    local_clean = _strip_comments_preserving_newlines(local_text)
    upstream_clean = _strip_comments_preserving_newlines(upstream_text)
    local_includes = {m.group(1).lstrip(":") for m in _SETTINGS_INCLUDE_RE.finditer(local_clean)}
    upstream_includes = [m.group(1) for m in _SETTINGS_INCLUDE_RE.finditer(upstream_clean)]

    to_add: list[str] = []
    for inc in upstream_includes:
        norm = inc.lstrip(":")
        if norm in local_includes:
            continue
        mod_dir = Path(*norm.split(":"))
        if (mod_dir / "build.gradle.kts").is_file() or (mod_dir / "build.gradle").is_file():
            to_add.append(f'include(":{norm}")')
            local_includes.add(norm)

    if not to_add:
        return False

    updated_text = local_text
    core_match = re.search(r'(?m)^([ \t]*include\s*\(\s*":core"\s*\)\s*)$', updated_text)
    insert_block = "\n".join(to_add)
    if core_match:
        updated_text = updated_text[:core_match.end()] + "\n" + insert_block + updated_text[core_match.end():]
    else:
        updated_text = updated_text.rstrip() + "\n" + insert_block + "\n"

    if updated_text == local_text:
        return False
    if apply:
        SETTINGS_FILE.write_text(updated_text, encoding="utf-8")
        print(f"Reconciled new module includes in settings.gradle.kts: {', '.join(to_add)}")
    return True


def _sync_shared_project_deps_into_plugin(plugin_path: Path, dep_lines: list[str]) -> bool:
    """Ensure shared module dependencies (e.g. compileOnly(project(\":extensions-lib\"))) exist in a plugin."""
    if not plugin_path.is_file() or not dep_lines:
        return False
    text = plugin_path.read_text(encoding="utf-8")
    original = text
    missing = [line for line in dep_lines if line.strip() not in text]
    if not missing:
        return False
    anchor = re.search(r"(?m)^([ \t]*)compileOnly\(libs\.bundles\.common\)\s*$", text)
    if not anchor:
        anchor = re.search(r"(?m)^([ \t]*)implementation\(project\(\":core\"\)\)\s*$", text)
    if anchor:
        indent = anchor.group(1)
        block = "\n".join(f"{indent}{line.strip()}" for line in missing)
        text = text[:anchor.end()] + "\n" + block + text[anchor.end():]
    if text != original:
        plugin_path.write_text(text, encoding="utf-8")
        return True
    return False


def reconcile_build_logic_infra(upstream_ref: str) -> list[str]:
    """Selectively reconcile shared module dependencies in preserved build-logic plugins."""
    changed_files: list[str] = []
    ext_plugin_rel = "gradle/build-logic/src/main/kotlin/ExtensionPlugin.kt"
    up_ext_plugin = read_file_at(upstream_ref, ext_plugin_rel)
    if up_ext_plugin:
        dep_lines = re.findall(
            r'^[ \t]*((?:compileOnly|implementation|api|ksp)\s*\(\s*project\s*\(\s*"(:[^"]+)"\s*\)\s*\))[ \t]*$',
            up_ext_plugin,
            re.MULTILINE,
        )
        shared_deps = [
            expr for expr, mod in dep_lines
            if mod not in {":core", ":compiler"} and not mod.startswith(":lib-multisrc:")
        ]
        for target_rel in (
            ext_plugin_rel,
            "gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt",
        ):
            target_path = Path(target_rel)
            if _sync_shared_project_deps_into_plugin(target_path, shared_deps):
                changed_files.append(target_rel)
    return changed_files


def apply_infrastructure_updates(upstream_ref: str) -> list[str]:
    """Apply non-preserved infra updates + selective reconciliation of preserved build/catalog files."""
    applied_infra: list[str] = []
    infra_targets = sorted(INFRA_EXACT_PATHS | discover_upstream_top_level_modules(upstream_ref))

    # 1. Restore non-preserved top-level infra files/modules and non-preserved gradle/ files
    for target in infra_targets:
        if is_preserved(target) or not path_exists(upstream_ref, target):
            continue
        diff_files = git("diff", "--name-only", "HEAD", upstream_ref, "--", target, check=False).splitlines()
        if not diff_files:
            continue
        print(f"Applying global infrastructure update: {target}")
        git("rm", "-rf", "--ignore-unmatch", "--quiet", "--", target)
        git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", target)
        applied_infra.append(target)

    # 2. Selectively sync non-preserved files under gradle/ (e.g. LibraryPlugin.kt, ThemePlugin.kt, GenerateManifestTask.kt)
    gradle_diff = git("diff", "--name-only", "HEAD", upstream_ref, "--", "gradle", check=False).splitlines()
    for rel_path in gradle_diff:
        if not rel_path or is_preserved(rel_path):
            continue
        if path_exists(upstream_ref, rel_path):
            print(f"Applying upstream gradle update: {rel_path}")
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", rel_path)
            applied_infra.append(rel_path)
        elif Path(rel_path).exists():
            git("rm", "-f", "--ignore-unmatch", "--quiet", "--", rel_path)
            applied_infra.append(rel_path)

    # 3. Selectively reconcile preserved catalogs, settings.gradle.kts, and build-logic plugins
    for catalog_file in (LIBS_CATALOG_FILE, KEI_CATALOG_FILE):
        if reconcile_version_catalog(upstream_ref, catalog_file=catalog_file, apply=True):
            git("add", "--", str(catalog_file))
            applied_infra.append(str(catalog_file))

    if reconcile_settings_modules(upstream_ref, apply=True):
        git("add", "--", str(SETTINGS_FILE))
        applied_infra.append(str(SETTINGS_FILE))

    for bl_rel in reconcile_build_logic_infra(upstream_ref):
        git("add", "--", bl_rel)
        applied_infra.append(bl_rel)

    return sorted(set(applied_infra))


def validate_local_project_dependencies() -> list[str]:
    """Return project(':...') references whose local module directory/build file does not exist."""
    errors: list[str] = []
    build_files: list[Path] = []
    if Path("build.gradle.kts").is_file():
        build_files.append(Path("build.gradle.kts"))
    for mod in discover_local_top_level_modules():
        build_files.extend(mod.glob("build.gradle*"))
    for root_name in ("gradle/build-logic/src", "lib", "lib-multisrc", "src"):
        root = Path(root_name)
        if root.is_dir():
            build_files.extend(root.rglob("build.gradle.kts"))
            build_files.extend(root.rglob("build.gradle"))
            if root_name == "gradle/build-logic/src":
                build_files.extend(root.rglob("*.kt"))

    for build_file in sorted(set(build_files)):
        if not build_file.is_file() or ".gradle" in build_file.parts or "build" in build_file.parts:
            continue
        content = _strip_comments_preserving_newlines(build_file.read_text(encoding="utf-8", errors="ignore"))
        for match in _LOCAL_PROJECT_RE.finditer(content):
            project_path = match.group(1)
            if "$" in project_path:
                continue
            module_path = Path(*project_path.removeprefix(":").split(":"))
            has_build_file = any(
                (module_path / fn).is_file() for fn in ("build.gradle.kts", "build.gradle")
            )
            if not has_build_file:
                line_num = content.count("\n", 0, match.start()) + 1
                errors.append(f"{build_file.as_posix()}:{line_num}: módulo referenciado '{project_path}' não existe em {module_path}")
    return errors


def validate_settings_modules() -> list[str]:
    """Verify static include(...) declarations exist on disk and top-level modules are included."""
    if not SETTINGS_FILE.is_file():
        return []
    errors: list[str] = []
    content = _strip_comments_preserving_newlines(SETTINGS_FILE.read_text(encoding="utf-8", errors="ignore"))
    included_top: set[str] = set()
    for match in _SETTINGS_INCLUDE_RE.finditer(content):
        mod_ref = match.group(1)
        norm = mod_ref.lstrip(":")
        included_top.add(norm)
        mod_path = Path(*norm.split(":"))
        if not ((mod_path / "build.gradle.kts").is_file() or (mod_path / "build.gradle").is_file()):
            line_num = content.count("\n", 0, match.start()) + 1
            errors.append(f"settings.gradle.kts:{line_num}: módulo incluído '{mod_ref}' não existe em {mod_path}")

    for mod_dir in discover_local_top_level_modules():
        if mod_dir.name not in included_top:
            errors.append(f"settings.gradle.kts: módulo '{mod_dir.name}' existe no disco mas não possui include(\":{mod_dir.name}\")")
    return errors


def validate_version_catalog_references() -> list[str]:
    """Validate TOML catalogs and ensure every libs.*/kei.* reference across build scripts resolves."""
    if not LIBS_CATALOG_FILE.is_file() and not KEI_CATALOG_FILE.is_file():
        return []
    errors: list[str] = []
    catalog_paths: dict[str, set[str]] = {}

    for cat_name, cat_file in (("libs", LIBS_CATALOG_FILE), ("kei", KEI_CATALOG_FILE)):
        if not cat_file.is_file():
            continue
        try:
            data = tomllib.loads(cat_file.read_text(encoding="utf-8"))
        except tomllib.TOMLDecodeError as exc:
            errors.append(f"{cat_file.as_posix()}: erro de sintaxe TOML: {exc}")
            return errors
        catalog_paths[cat_name] = _catalog_accessor_paths(data)

        versions_tbl = data.get("versions", {})
        libs_tbl = data.get("libraries", {})
        plugins_tbl = data.get("plugins", {})
        bundles_tbl = data.get("bundles", {})

        for tbl_name, tbl in (("libraries", libs_tbl), ("plugins", plugins_tbl)):
            if isinstance(tbl, dict) and isinstance(versions_tbl, dict):
                for entry_k, entry_v in tbl.items():
                    if isinstance(entry_v, dict) and "version.ref" in entry_v:
                        ref_k = entry_v["version.ref"]
                        if ref_k not in versions_tbl:
                            errors.append(f"{cat_file.as_posix()}: {tbl_name} '{entry_k}' referencia version.ref '{ref_k}' ausente")
        if isinstance(bundles_tbl, dict) and isinstance(libs_tbl, dict):
            for b_k, b_items in bundles_tbl.items():
                if isinstance(b_items, list):
                    for item in b_items:
                        if item not in libs_tbl:
                            errors.append(f"{cat_file.as_posix()}: bundle '{b_k}' referencia biblioteca '{item}' ausente")

    script_files: list[Path] = []
    if Path("build.gradle.kts").is_file():
        script_files.append(Path("build.gradle.kts"))
    for mod in discover_local_top_level_modules():
        script_files.extend(mod.rglob("*.gradle.kts"))
        script_files.extend(mod.rglob("*.gradle"))
    for root_name in ("lib", "lib-multisrc", "src", "gradle/build-logic"):
        root = Path(root_name)
        if root.is_dir():
            script_files.extend(root.rglob("*.gradle.kts"))
            script_files.extend(root.rglob("*.gradle"))
            if root_name == "gradle/build-logic":
                script_files.extend(root.rglob("*.kt"))

    for script_file in sorted(set(script_files)):
        if not script_file.is_file():
            continue
        posix_path = script_file.as_posix()
        if "/build/" in posix_path or "/.gradle/" in posix_path or ".gradle" in script_file.parts or posix_path.startswith("gradle/build-logic/build/"):
            continue
        raw = script_file.read_text(encoding="utf-8", errors="ignore")
        cleaned = _strip_comments_preserving_newlines(raw, strip_strings=True)
        for line_num, line in enumerate(cleaned.splitlines(), 1):
            for m in _CATALOG_REF_RE.finditer(line):
                cat, chain = m.group(1), m.group(2)
                if cat not in catalog_paths:
                    continue
                parts = chain.split(".")
                while parts and parts[-1] in _CATALOG_PROVIDER_SUFFIXES:
                    parts.pop()
                if not parts:
                    continue
                cand = ".".join(parts)
                full = f"{cat}.{cand}"
                if full in _CATALOG_IGNORED_PREFIXES or cand in {"java", "class"}:
                    continue
                declared = catalog_paths[cat]
                if not any(cand == entry or cand.startswith(entry + ".") for entry in declared):
                    errors.append(f"{posix_path}:{line_num}: alias '{full}' não encontrado em gradle/{cat}.versions.toml")
    return errors


def validate_multisrc_compatibility() -> list[str]:
    """Validate theme/themePkg and libVersion compatibility between extensions and lib-multisrc."""
    errors: list[str] = []
    src_dir = Path("src")
    if not src_dir.is_dir():
        return errors
    for lang_dir in sorted(src_dir.iterdir()):
        if not lang_dir.is_dir():
            continue
        for ext_dir in sorted(lang_dir.iterdir()):
            if not ext_dir.is_dir():
                continue
            bf = ext_dir / "build.gradle.kts"
            if not bf.is_file():
                bf = ext_dir / "build.gradle"
            if not bf.is_file():
                continue
            unit = f"src/{lang_dir.name}/{ext_dir.name}"
            content = bf.read_text(encoding="utf-8", errors="ignore")
            theme_match = _THEME_RE.search(content)
            if not theme_match:
                continue
            theme = theme_match.group(1)
            ms_bf = Path(f"lib-multisrc/{theme}/build.gradle.kts")
            if not ms_bf.is_file():
                ms_bf = Path(f"lib-multisrc/{theme}/build.gradle")
            if not ms_bf.is_file():
                errors.append(f"{unit}: multisrc '{theme}' não existe")
                continue
            ext_lib = read_lib_version(None, unit)
            ms_match = _LIB_VERSION_RE.search(ms_bf.read_text(encoding="utf-8", errors="ignore"))
            ms_lib = int(ms_match.group(1)) if ms_match else 14
            if ext_lib < ms_lib:
                errors.append(f"{unit}: extensão libVersion {ext_lib} < multisrc {theme} libVersion {ms_lib}")
    return errors


def run_structural_validations() -> list[str]:
    """Run all static structural validations before invoking Gradle or pushing."""
    return (
        validate_local_project_dependencies()
        + validate_settings_modules()
        + validate_version_catalog_references()
        + validate_multisrc_compatibility()
    )


def classify_gradle_failure(output: str) -> str:
    """Distinguish transient network/Maven failures from deterministic compilation/config failures."""
    if any(marker in output for marker in _TRANSIENT_GRADLE_MARKERS):
        if "Script compilation error" not in output and "Unresolved reference" not in output:
            return "TRANSIENT_NETWORK"
    if "Script compilation error" in output:
        return "DETERMINISTIC_SCRIPT_ERROR"
    if "Unresolved reference" in output or "Compilation error" in output:
        return "DETERMINISTIC_COMPILATION_ERROR"
    if "Project with path" in output or "not found in root project" in output or "Plugin [id:" in output:
        return "DETERMINISTIC_CONFIG_ERROR"
    return "DETERMINISTIC_BUILD_ERROR"


def parse_gradle_failures(output: str) -> dict[str, dict[str, object]]:
    """Extract failing units and diagnostic messages from Gradle output."""
    failed_units: dict[str, dict[str, object]] = {}
    for match in _GRADLE_FAILED_TASK_RE.finditer(output):
        gradle_module = match.group(1) or match.group(3)
        unit = gradle_module.replace(":", "/")
        if unit not in failed_units:
            failed_units[unit] = {
                "unit": unit,
                "cause": "Compilation/build failure",
                "evidence": "",
                "incompatibility_type": "KOTLIN_COMPILATION_ERROR",
            }
    for m in _KOTLIN_ERROR_RE.finditer(output):
        file_path, _, _, msg = m.group(1), m.group(2), m.group(3), m.group(4)
        matched_unit = None
        for unit in list(failed_units.keys()):
            if unit in file_path:
                matched_unit = unit
                failed_units[unit]["cause"] = msg.strip()
                break
        if not matched_unit:
            parts = file_path.split("/")
            for infra_name in ("core", "compiler", "extensions-lib"):
                if infra_name in parts:
                    failed_units.setdefault(
                        infra_name,
                        {
                            "unit": infra_name,
                            "cause": msg.strip(),
                            "evidence": "",
                            "incompatibility_type": "CORE_INCOMPATIBILITY",
                        },
                    )
                    matched_unit = infra_name
                    break
            if not matched_unit and "gradle" in parts and "build-logic" in parts:
                failed_units.setdefault(
                    "gradle/build-logic",
                    {
                        "unit": "gradle/build-logic",
                        "cause": msg.strip(),
                        "evidence": "",
                        "incompatibility_type": "CORE_INCOMPATIBILITY",
                    },
                )
    lines = output.splitlines()
    for unit, info in failed_units.items():
        relevant = [ln for ln in lines if unit in ln or (info["cause"] in ln)]
        info["evidence"] = "\n".join(relevant[:30]) or str(info["cause"])
    return failed_units


def _run_gradle_in_sandbox(sandbox: Path, tasks: list[str]) -> tuple[int, str]:
    gradlew = sandbox / "gradlew"
    cmd = [str(gradlew), "--no-daemon", "--no-scan", "--console=plain", "--continue", *tasks]
    res = subprocess.run(cmd, cwd=sandbox, capture_output=True, text=True)
    combined = f"{res.stdout or ''}\n{res.stderr or ''}"
    return res.returncode, combined


def validate_candidate_with_gradle(
    candidate_ref: str,
    infra_changed: bool,
    validation_units: list[str],
) -> tuple[bool, list[str], str]:
    """
    Validate a candidate commit using real Gradle execution inside an isolated git worktree.
    Returns (ok, failed_units, output_tail_or_error).
    Raises TransientNetworkError if Gradle failed due to transient network/Maven outage.
    """
    if not Path("gradlew").is_file():
        return True, [], ""

    root = Path.cwd()
    with tempfile.TemporaryDirectory(prefix="nox-anime-preflight-") as tmpdir:
        sandbox = Path(tmpdir) / "repo"
        git("worktree", "add", "--detach", str(sandbox), candidate_ref)
        try:
            if Path("local.properties").is_file():
                shutil.copy2("local.properties", sandbox / "local.properties")

            infra_tasks: list[str] = []
            if infra_changed:
                for mod_dir in discover_local_top_level_modules(sandbox):
                    if mod_dir.name == "core":
                        infra_tasks.append(":core:testDebugUnitTest")
                    elif mod_dir.name == "compiler":
                        infra_tasks.append(":compiler:compileKotlin")
                    else:
                        infra_tasks.append(f":{mod_dir.name}:compileReleaseKotlin")
                for rep_pt in ("src/pt/animefire", "src/pt/redecanais", "src/pt/anikyuu"):
                    if (sandbox / rep_pt).is_dir():
                        if rep_pt in {"src/pt/animefire", "src/pt/redecanais"}:
                            infra_tasks.append(f":{rep_pt.replace('/', ':')}:mergeReleaseJavaResource")
                        infra_tasks.append(f":{rep_pt.replace('/', ':')}:compileReleaseKotlin")

            ext_tasks: list[str] = []
            for unit in sorted(set(validation_units)):
                unit_dir = sandbox / unit
                if not unit.startswith("src/") or not unit_dir.is_dir():
                    continue
                gradle_mod = ":" + unit.replace("/", ":")
                ext_tasks.append(f"{gradle_mod}:compileReleaseKotlin")
                if (unit_dir / "build.gradle.kts").is_file():
                    ext_tasks.append(f"{gradle_mod}:generateSourceInfo")

            if not (infra_tasks or ext_tasks):
                return True, [], ""

            if infra_tasks:
                print(f"Validating Gradle infrastructure tasks in sandbox: {' '.join(infra_tasks)}")
                rc, out = _run_gradle_in_sandbox(sandbox, infra_tasks)
                if rc != 0:
                    if classify_gradle_failure(out) == "TRANSIENT_NETWORK":
                        raise TransientNetworkError(out[-1500:])
                    failures = parse_gradle_failures(out)
                    failed_units = sorted(failures.keys()) or ["gradle-infra"]
                    return False, failed_units, out[-2000:]
            if ext_tasks:
                print(f"Validating {len(validation_units)} affected extension(s) in sandbox...")
                rc, out = _run_gradle_in_sandbox(sandbox, ext_tasks)
                if rc != 0:
                    if classify_gradle_failure(out) == "TRANSIENT_NETWORK":
                        raise TransientNetworkError(out[-1500:])
                    failures = parse_gradle_failures(out)
                    failed_units = sorted(failures.keys()) or sorted(validation_units)
                    return False, failed_units, out[-2000:]
            return True, [], ""
        finally:
            os.chdir(root)
            git("worktree", "remove", "--force", str(sandbox), check=False)


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
    deferred_incompatibilities: dict[str, dict[str, object]] | None = None,
) -> list[str]:
    """Apply upstream updates atomically, preserving Nox-protected code and validating structure before commit."""
    start_head = git("rev-parse", "HEAD").strip()
    try:
        return _apply_units_inner(
            upstream_ref=upstream_ref,
            units_to_apply=units_to_apply,
            conflict_set=conflict_set,
            protected=protected,
            upstream_deleted=upstream_deleted,
            protected_deletions=protected_deletions,
            deployed_codes=deployed_codes,
            new_units=new_units,
            updated_units=updated_units,
            removed_units=removed_units,
            indirect_units=indirect_units,
            deferred_incompatibilities=deferred_incompatibilities,
        )
    except BaseException:
        if merge_in_progress():
            git("merge", "--abort", check=False)
        git("reset", "--hard", start_head, check=False)
        raise


def _apply_units_inner(
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
    deferred_incompatibilities: dict[str, dict[str, object]] | None = None,
) -> list[str]:
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
    deferred_units = set((deferred_incompatibilities or {}).keys())

    for unit in units_to_apply:
        if unit in deferred_units:
            print(f"Deferring incompatible unit (preserving last functional state): {unit}")
            continue
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

    # Apply infrastructure & selective build/catalog updates from upstream
    apply_infrastructure_updates(upstream_ref)

    bumped: list[str] = []
    guard_units = sorted(
        {
            u for u in units_to_apply
            if u.startswith("src/")
            and not is_ptbr_unit(u)
            and u not in explicit_protection_set
            and u not in deferred_units
        }
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

    # Static structural validation BEFORE staging sync-state as validated (when Gradle workspace files exist)
    structural_errors = run_structural_validations() if (SETTINGS_FILE.is_file() or LIBS_CATALOG_FILE.is_file()) else []
    if structural_errors:
        print("Structural validation failed:", file=sys.stderr)
        for err in structural_errors:
            print(f"  - {err}", file=sys.stderr)
        failed_units = sorted({err.split(":", 1)[0] for err in structural_errors if ":" in err}) or ["gradle-infra"]
        raise StructuralIncompatibilityError(
            f"Structural validation failed: {structural_errors[0]}",
            failed_units=failed_units,
        )

    if deferred_incompatibilities is not None:
        if write_deferred_incompatibilities(deferred_incompatibilities):
            git("add", "--", str(DEFERRED_INCOMPATIBILITIES_FILE))

    # Record validated sync state (only cleared/updated after static checks pass; Gradle check runs next before push)
    upstream_head = git("rev-parse", upstream_ref).strip()
    sync_state = {
        "upstream_sha": upstream_head,
        "last_validated_upstream_sha": upstream_head,
        "upstream_branch": UPSTREAM_BRANCH,
        "synced_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "base_sha": base,
        "sync_status": "PARTIAL_DEFERRED" if deferred_incompatibilities else "VALIDATED",
        "blocked_candidate": None,
        "publication_block": None,
    }
    save_sync_state(sync_state)
    git("add", "--", str(SYNC_STATE_FILE))

    # Verify PT-BR snapshot integrity: MUST be 100% byte-for-byte identical
    verify_ptbr_unchanged(pre_ptbr_snapshot)

    git("diff", "--check")

    commit_msg = "Sync upstream"
    if deferred_incompatibilities:
        commit_msg += f" (partially synced, {len(deferred_incompatibilities)} deferred)"
    details = []
    if new_units:
        added_eff = [u for u in sorted(new_units) if u not in deferred_units]
        if added_eff:
            details.append("Added extensions:\n" + "\n".join(f"  - {u}" for u in added_eff))
    if updated_units:
        upd_eff = [u for u in sorted(updated_units) if u not in deferred_units]
        if upd_eff:
            details.append("Updated extensions:\n" + "\n".join(f"  - {u}" for u in upd_eff))
    if removed_units:
        details.append("Removed extensions:\n" + "\n".join(f"  - {u}" for u in sorted(removed_units)))
    if bumped:
        details.append("Nox-wins version bumps:\n" + "\n".join(f"  - {b}" for b in bumped))
    if deferred_incompatibilities:
        details.append(
            "Deferred incompatible units:\n"
            + "\n".join(f"  - {u}: {info.get('cause', 'incompatible')}" for u, info in sorted(deferred_incompatibilities.items()))
        )
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


def get_check_consistency_mod():
    mod = sys.modules.get("check_consistency_mod")
    if mod is None:
        import importlib.util
        script_path = Path(__file__).resolve().parent / "check-consistency.py"
        spec = importlib.util.spec_from_file_location("check_consistency_mod", str(script_path))
        mod = importlib.util.module_from_spec(spec)
        sys.modules["check_consistency_mod"] = mod
        spec.loader.exec_module(mod)
    return mod


def get_publish_pending_units(
    base: str | None = None,
    upstream_ref: str = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}",
    catalog_target: str | None = None,
    distribution_dir: Path | str | None = None,
    return_context: bool = False,
) -> list[str] | tuple[list[str], Path | None, bool]:
    """
    Detect units where source exists locally in Nox, but the extension is missing
    or incomplete in the deployed distribution catalog or APK artifacts.
    """
    resolved_dist_dir = None
    has_inconclusive = False
    target_kind = "file" if distribution_dir else "url"
    resolved_target = None
    try:
        mod = get_check_consistency_mod()
        res_ctx = mod.resolve_distribution_context(
            catalog_target=catalog_target,
            distribution_dir=distribution_dir,
        )
        resolved_target_url = None
        if len(res_ctx) >= 6:
            data, catalog_source, status, local_apk_dir, remote_apks, target = res_ctx[:6]
            resolved_target = target
            if target:
                if target.startswith("http://") or target.startswith("https://"):
                    target_kind = "url"
                    resolved_target_url = target
                else:
                    target_kind = "file"
                    resolved_dist_dir = Path(target).resolve().parent
            if local_apk_dir and not resolved_dist_dir:
                if (local_apk_dir / "index.min.json").is_file():
                    resolved_dist_dir = local_apk_dir
                elif (local_apk_dir.parent / "index.min.json").is_file():
                    resolved_dist_dir = local_apk_dir.parent
                else:
                    resolved_dist_dir = local_apk_dir.parent
        elif len(res_ctx) >= 5:
            data, catalog_source, status, local_apk_dir, remote_apks = res_ctx[:5]
        else:
            data, catalog_source, status = res_ctx[:3]
            local_apk_dir, remote_apks = None, None

        if status == "UNVERIFIED_ARTIFACTS":
            has_inconclusive = True

        if not data:
            return ([], resolved_dist_dir, True, target_kind, resolved_target) if return_context else []

        deployed_by_unit = {}
        for item in data:
            pkg = item.get("pkg", "")
            prefix = "eu.kanade.tachiyomi.animeextension."
            if pkg.startswith(prefix):
                parts = pkg[len(prefix):].split(".")
                if len(parts) >= 2:
                    deployed_by_unit[f"src/{parts[0]}/{parts[1]}"] = item

        # When Git Tree API is unavailable or APKs are missing from the Git tree, verify needed APKs
        # concurrently with bounded worker concurrency (Codex Findings P1 & P2)
        remote_probed_map = {}
        if local_apk_dir is None:
            target_url = resolved_target_url or catalog_target or DISTRIBUTION_INDEX_URL
            if target_url.startswith("http://") or target_url.startswith("https://"):
                all_deployed_apks = list({
                    dep.get("apk") for dep in deployed_by_unit.values()
                    if dep.get("apk")
                })
                if remote_apks is not None:
                    valid_apks = remote_apks[0] if isinstance(remote_apks, tuple) else remote_apks
                    empty_apks = remote_apks[1] if isinstance(remote_apks, tuple) else set()
                    apks_to_probe = [a for a in all_deployed_apks if a not in valid_apks and a not in empty_apks]
                else:
                    apks_to_probe = all_deployed_apks

                if apks_to_probe and hasattr(mod, "check_remote_apks_concurrent"):
                    remote_probed_map = mod.check_remote_apks_concurrent(target_url, apks_to_probe)
                    # Upgrade status if full probe conclusively reached the server (Codex Finding P2)
                    if status == "UNVERIFIED_ARTIFACTS" and any(st in ("EXISTS", "MISSING") for st in remote_probed_map.values()):
                        status = "VERIFIED"
                        has_inconclusive = False

        pending = []
        all_local = get_all_source_units(None)
        for u in sorted(all_local):
            if u not in deployed_by_unit:
                pending.append(u)
                continue
            dep = deployed_by_unit[u]
            eff = effective_version(None, u)
            if eff is not None:
                local_eff_code = eff[2]
                dep_code = dep.get("code", 0)
                if local_eff_code > dep_code:
                    pending.append(u)
                    continue
            apk_name = dep.get("apk")
            if not apk_name:
                pending.append(u)
                continue
            if local_apk_dir is not None:
                if not local_apk_dir.is_dir():
                    pending.append(u)
                    continue
                apk_path = local_apk_dir / apk_name
                if not apk_path.is_file() or apk_path.stat().st_size == 0:
                    pending.append(u)
                    continue
            elif remote_apks is not None:
                if isinstance(remote_apks, tuple):
                    valid_apks, empty_apks = remote_apks
                    if apk_name in empty_apks:
                        pending.append(u)
                        continue
                else:
                    valid_apks = remote_apks
                if apk_name not in valid_apks:
                    # Concurrent probe result for APKs missing from Git tree (Codex Finding P1)
                    apk_status = remote_probed_map.get(apk_name, "ERROR")
                    if apk_status == "MISSING":
                        pending.append(u)
                    elif apk_status == "ERROR":
                        has_inconclusive = True
            elif local_apk_dir is None:
                # Custom URL target without Git Tree API (Codex Findings P1 & P2)
                apk_status = remote_probed_map.get(apk_name, "ERROR")
                if apk_status == "MISSING":
                    pending.append(u)
                elif apk_status == "ERROR":
                    has_inconclusive = True
            # When status == "UNVERIFIED_ARTIFACTS" (service outage or rate limit), do not perform
            # unbounded sequential HEAD requests; catalog-proven missing/outdated units were collected above.
        return (pending, resolved_dist_dir, has_inconclusive, target_kind, resolved_target) if return_context else pending
    except Exception as e:
        print(f"Notice: get_publish_pending_units encountered {e}", file=sys.stderr)
        return ([], None, True, target_kind, resolved_target) if return_context else []


def is_valid_apk_file(p: Path | str) -> bool:
    """Validate that path exists, is non-empty, and starts with valid zip magic header PK\x03\x04."""
    try:
        p = Path(p)
        if not p.is_file() or p.stat().st_size <= 0:
            return False
        with open(p, "rb") as zf:
            return zf.read(4) == b"PK\x03\x04"
    except Exception:
        return False


def recover_pending_publications(
    units: list[str],
    push: bool = False,
    start_main: str | None = None,
    upstream_ref: str | None = None,
    distribution_dir: Path | str | None = None,
    staging_dir: Path | str | None = None,
    catalog_target: str | None = None,
    target_kind: str | None = None,
    resolved_target: str | None = None,
) -> str:
    """
    Recover incomplete publication for specified units:
    1. Locate or produce valid APK artifact for each pending unit.
    2. Verify package, version, and presence.
    3. Update distribution index.min.json, index.json, index.html.
    4. Commit and push recovered artifacts to distribution repo (if push=True and local clone exists).
    5. Or dispatch build_push.yml workflow via GitHub API if remote.

    Returns "RECOVERED", "DISPATCHED", "DEFERRED", or "FAILED".
    """
    if not units:
        return "RECOVERED"

    print(f"Starting publication recovery for {len(units)} units: {', '.join(units)}")

    is_remote_catalog = (
        target_kind == "url"
        or (catalog_target and (catalog_target.startswith("http://") or catalog_target.startswith("https://")))
        or (resolved_target and (resolved_target.startswith("http://") or resolved_target.startswith("https://")))
    )

    dist_path = None
    if distribution_dir:
        dist_path = Path(distribution_dir)
    elif catalog_target and not (catalog_target.startswith("http://") or catalog_target.startswith("https://")):
        cat_file = Path(catalog_target).resolve()
        if cat_file.is_file():
            dist_path = cat_file.parent
        elif cat_file.is_dir():
            dist_path = cat_file
    elif not is_remote_catalog:
        # If neither distribution_dir nor catalog_target was explicitly supplied,
        # and target was not detected as remote, check local fallback (Codex Finding P1)
        try:
            mod = get_check_consistency_mod()
            res_ctx = mod.resolve_distribution_context(catalog_target=None, distribution_dir=None)
            if len(res_ctx) >= 6 and res_ctx[5] and not (str(res_ctx[5]).startswith("http://") or str(res_ctx[5]).startswith("https://")):
                dist_path = Path(res_ctx[5]).resolve().parent
            elif len(res_ctx) >= 4 and res_ctx[3]:
                dist_path = res_ctx[3].parent if isinstance(res_ctx[3], Path) else Path(res_ctx[3]).parent
        except Exception:
            pass

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
            local_code = eff[2] if eff else 1
            lib = read_lib_version(None, u)
            local_version_str = f"{lib}.{local_code}"
            for fn in ("build.gradle.kts", "build.gradle"):
                bf = Path(f"{u}/{fn}")
                if bf.is_file():
                    content_gradle = bf.read_text(encoding="utf-8", errors="ignore")
                    m = re.search(r"""extVersion\s*=\s*['"]([^'"]+)['"]""", content_gradle)
                    if m:
                        local_version_str = m.group(1)

            # Preserve deployed version if deployed catalog version is ahead (avoid downgrade)
            dep = min_by_pkg.get(pkg) or full_by_pkg.get(pkg) or {}
            dep_code = dep.get("code", 0)
            dep_version = dep.get("version")
            if dep_code > local_code and dep_version:
                code = dep_code
                version_str = dep_version
                expected_apk_names = {
                    f"aniyomi-{lang}.{ext}-v{dep_version}.apk",
                    f"aniyomi-{lang}.{ext}-v{dep_version}-release.apk",
                }
                if dep.get("apk"):
                    expected_apk_names.add(dep["apk"])
                    expected_apk_names.add(dep["apk"].replace(".apk", "-release.apk"))
            else:
                code = local_code
                version_str = local_version_str
                expected_apk_names = {
                    f"aniyomi-{lang}.{ext}-v{version_str}.apk",
                    f"aniyomi-{lang}.{ext}-v{lib}.{code}.apk",
                    f"aniyomi-{lang}.{ext}-v{version_str}-release.apk",
                    f"aniyomi-{lang}.{ext}-v{lib}.{code}-release.apk",
                }
                if dep.get("apk") and dep_code >= local_code and dep.get("version") == version_str:
                    expected_apk_names.add(dep["apk"])
                    expected_apk_names.add(dep["apk"].replace(".apk", "-release.apk"))

            found_apk = None
            candidate_apk_dirs = []
            if staging_dir:
                candidate_apk_dirs.append(Path(staging_dir))
            candidate_apk_dirs.extend([
                Path(f"{u}/build/outputs/apk/release"),
                Path("repo/apk"),
                apk_dir,
            ])

            for c_dir in candidate_apk_dirs:
                if c_dir.is_dir():
                    for name in expected_apk_names:
                        f = c_dir / name
                        # Validate candidate before selecting it to avoid corrupt earlier artifacts blocking valid ones (Codex Finding P2)
                        if is_valid_apk_file(f):
                            found_apk = f
                            break
                if found_apk:
                    break

            if not found_apk and local_code < code:
                print(f"Error: local source version code ({local_code}) is behind deployed code ({code}) for {u}; cannot build outdated artifact.", file=sys.stderr)
                return "FAILED"

            if not found_apk and Path("./gradlew").is_file() and os.access("./gradlew", os.X_OK):
                print(f"Compiling APK for {u} via Gradle...")
                mod_selector = f":src:{lang}:{ext}:assembleRelease"
                res = subprocess.run(["./gradlew", mod_selector], capture_output=True, text=True)
                if res.returncode == 0:
                    out_dir = Path(f"{u}/build/outputs/apk/release")
                    if out_dir.is_dir():
                        for name in expected_apk_names:
                            f = out_dir / name
                            if is_valid_apk_file(f):
                                found_apk = f
                                break

            if not found_apk or not is_valid_apk_file(found_apk):
                print(f"Error: legitimate APK artifact for {u} cannot be located or produced.", file=sys.stderr)
                return "FAILED"

            # Normalize Gradle release APK name (e.g. *-release.apk -> *.apk)
            apk_name = found_apk.name.replace("-release.apk", ".apk")
            if not apk_name.endswith(".apk"):
                apk_name += ".apk"

            # Strictly verify package prefix and version tag in artifact name
            expected_prefix = f"aniyomi-{lang}.{ext}-v"
            if not apk_name.startswith(expected_prefix) or "-v" not in apk_name:
                print(f"Error: APK artifact name {apk_name} does not match expected package prefix {expected_prefix}", file=sys.stderr)
                return "FAILED"

            # Validate APK version corresponds strictly to expected catalog code and version
            v_part = apk_name.split("-v")[-1].replace(".apk", "")
            if dep_code > local_code and dep_version:
                if v_part != dep_version:
                    print(f"Error: APK artifact version ({v_part}) does not match deployed version ({dep_version}) for {u}.", file=sys.stderr)
                    return "FAILED"
                version_str = dep_version
            else:
                if v_part != version_str:
                    print(f"Error: APK artifact version ({v_part}) does not match expected version ({version_str}) for {u}.", file=sys.stderr)
                    return "FAILED"

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
            existing_disk_apks = {f.name for f in apk_dir.glob("*.apk") if f.stat().st_size > 0}
            f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
            for entry in sorted_min:
                if entry.get("apk") in existing_disk_apks:
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
    if not gh_token:
        try:
            mod = get_check_consistency_mod()
            gh_token = mod.get_github_auth_token()
        except Exception:
            pass
    if gh_token:
        try:
            repo_slug = os.environ.get("GITHUB_REPOSITORY", "ProjectNox-org/anime-fonte-extensoes")
            def _gh_api_json(api_url: str) -> dict:
                req_api = urllib.request.Request(
                    api_url,
                    headers={
                        "Authorization": f"token {gh_token}",
                        "Accept": "application/vnd.github+json",
                        "User-Agent": "Nox-Sync/1.0",
                    },
                )
                with urllib.request.urlopen(req_api, timeout=10) as api_resp:
                    return json.loads(api_resp.read().decode("utf-8"))

            def _classify_failed_ci_run(run_obj: dict) -> tuple[str, str]:
                run_id = run_obj.get("id")
                if not run_id:
                    return "DETERMINISTIC_BUILD_FAILURE", "CI run failed without run_id"
                try:
                    jobs_data = _gh_api_json(
                        f"https://api.github.com/repos/{repo_slug}/actions/runs/{run_id}/jobs"
                    )
                    failed_jobs = [
                        j for j in jobs_data.get("jobs", []) if j.get("conclusion") == "failure"
                    ]
                    if not failed_jobs:
                        return "TRANSIENT_BUILD_FAILURE", f"CI run {run_id} failed with no failed job detail"
                    for job in failed_jobs:
                        job_id = job.get("id")
                        if not job_id:
                            continue
                        try:
                            req_log = urllib.request.Request(
                                f"https://api.github.com/repos/{repo_slug}/actions/jobs/{job_id}/logs",
                                headers={
                                    "Authorization": f"token {gh_token}",
                                    "Accept": "application/vnd.github+json",
                                    "User-Agent": "Nox-Sync/1.0",
                                },
                            )
                            with urllib.request.urlopen(req_log, timeout=10) as log_resp:
                                log_text = log_resp.read(200_000).decode("utf-8", errors="ignore")
                            cls = classify_gradle_failure(log_text)
                            if cls == "TRANSIENT_NETWORK":
                                return "TRANSIENT_BUILD_FAILURE", f"Transient network failure in job {job.get('name')}"
                            return "DETERMINISTIC_BUILD_FAILURE", f"Deterministic failure ({cls}) in job {job.get('name')}"
                        except Exception:
                            failed_steps = [
                                s.get("name", "") for s in job.get("steps", []) if s.get("conclusion") == "failure"
                            ]
                            step_desc = ", ".join(failed_steps) or str(job.get("name"))
                            if any("Build extensions" in str(job.get("name", "")) or "Validate" in step_desc for _ in [1]):
                                return "DETERMINISTIC_BUILD_FAILURE", f"CI build failed in {job.get('name')} ({step_desc})"
                except Exception as exc:
                    return "DETERMINISTIC_BUILD_FAILURE", f"Unable to inspect failed CI run {run_id}: {exc}"
                return "DETERMINISTIC_BUILD_FAILURE", f"CI run {run_id} failed deterministically"

            def get_recent_runs() -> list[dict]:
                runs_url = f"https://api.github.com/repos/{repo_slug}/actions/workflows/build_push.yml/runs?branch=main&per_page=10"
                runs_data = _gh_api_json(runs_url)
                return [
                    r for r in runs_data.get("workflow_runs", [])
                    if r.get("head_branch") == "main"
                ]

            try:
                recent_runs = get_recent_runs()
            except Exception as e_runs:
                print(f"Warning: unable to query active runs ({e_runs}). Deferring publication recovery to avoid cancelling active publication.", file=sys.stderr)
                return "DEFERRED"

            active_runs = [r for r in recent_runs if r.get("status") != "completed"]

            if active_runs:
                print(f"Active workflow build_push.yml run(s) detected ({len(active_runs)}) in progress on main. Waiting briefly for completion before dispatching recovery...")
                max_wait = 20
                poll_interval = 5
                waited = 0
                while waited < max_wait:
                    time.sleep(poll_interval)
                    waited += poll_interval
                    try:
                        polled_runs = get_recent_runs()
                        current_active = [r for r in polled_runs if r.get("status") != "completed"]
                        if not current_active:
                            print(f"Active runs finished after {waited}s. Proceeding with recovery check.")
                            recent_runs = polled_runs
                            break
                    except Exception:
                        pass
                else:
                    active_ids = [str(r.get("id")) for r in active_runs]
                    print(f"Active build_push.yml run(s) ({', '.join(active_ids)}) still in progress on main after {max_wait}s. Deferring publication recovery to avoid cancelling active publication.")
                    return "DEFERRED"

            current_head = git("rev-parse", "HEAD", check=False).strip()
            current_code = get_code_sha("HEAD")
            matching_completed = [
                r for r in recent_runs
                if r.get("status") == "completed"
                and r.get("head_sha") in {current_head, current_code}
            ]
            if matching_completed and matching_completed[0].get("conclusion") == "failure":
                latest_failed = matching_completed[0]
                fail_kind, fail_cause = _classify_failed_ci_run(latest_failed)
                state = load_sync_state()
                prev_pub_block = state.get("publication_block") or {}
                prev_attempts = (
                    int(prev_pub_block.get("attempts", 0))
                    if prev_pub_block.get("code_sha") == current_code
                    else 0
                )
                if fail_kind == "DETERMINISTIC_BUILD_FAILURE" or prev_attempts >= MAX_TRANSIENT_PUB_RETRIES:
                    reason = fail_cause if fail_kind == "DETERMINISTIC_BUILD_FAILURE" else f"Transient CI failure persisted after {prev_attempts} attempts: {fail_cause}"
                    state["publication_block"] = {
                        "code_sha": current_code,
                        "local_head": current_head,
                        "run_id": latest_failed.get("id"),
                        "failure_type": fail_kind,
                        "cause": reason,
                        "attempts": prev_attempts + 1,
                        "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    }
                    save_sync_state(state)
                    print(f"Publication recovery suspended for {current_code[:10]}: {reason}")
                    return "BLOCKED_DETERMINISTIC"
                # Transient CI failure within retry budget: enforce bounded backoff
                next_retry_str = prev_pub_block.get("next_retry_at")
                now_utc = datetime.datetime.now(datetime.timezone.utc)
                if next_retry_str:
                    try:
                        next_retry_dt = datetime.datetime.fromisoformat(next_retry_str)
                        if now_utc < next_retry_dt:
                            print(f"Transient publication failure backoff active until {next_retry_str}; deferring dispatch.")
                            return "DEFERRED"
                    except ValueError:
                        pass
                next_attempt = prev_attempts + 1
                backoff_secs = 300 * next_attempt
                state["publication_block"] = {
                    "code_sha": current_code,
                    "local_head": current_head,
                    "run_id": latest_failed.get("id"),
                    "failure_type": fail_kind,
                    "cause": fail_cause,
                    "attempts": next_attempt,
                    "recorded_at": now_utc.isoformat(),
                    "next_retry_at": (now_utc + datetime.timedelta(seconds=backoff_secs)).isoformat(),
                }
                save_sync_state(state)

            url = f"https://api.github.com/repos/{repo_slug}/actions/workflows/build_push.yml/dispatches"
            payload = {"ref": "main"}
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode("utf-8"),
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

def _record_and_persist_block(
    *,
    code_sha: str,
    upstream_head: str,
    failure_type: str,
    cause: str,
    failed_units: list[str],
    push: bool,
    start_main: str,
) -> None:
    state = load_sync_state()
    last_valid = state.get("last_validated_upstream_sha") or state.get("upstream_sha") or ""
    sig = compute_deterministic_signature(code_sha, upstream_head, failure_type, failed_units, cause)
    state["upstream_sha"] = last_valid
    state["last_validated_upstream_sha"] = last_valid
    state["sync_status"] = "BLOCKED_DETERMINISTIC"
    state["blocked_candidate"] = {
        "upstream_sha": upstream_head,
        "code_sha": code_sha,
        "failure_type": failure_type,
        "cause": cause[:1000],
        "failed_units": failed_units,
        "signature": sig,
        "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "skip_count": 0,
    }
    save_sync_state(state)
    if push:
        try:
            commit_state_only(
                f"chore(sync): record blocked upstream candidate {upstream_head[:10]}",
                push=True,
                start_main=start_main,
            )
        except ConcurrencyRaceError:
            raise
        except Exception as exc:
            print(f"Warning: could not push sync-state block record ({exc})", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description="Sync Yuzono upstream (Nox-wins)")
    parser.add_argument("--dry-run", action="store_true", help="Print plan without modifying files")
    parser.add_argument("--push", action="store_true", help="Commit and push to origin/main and sync")
    parser.add_argument("--apply-no-push", action="store_true", help="Apply and validate locally without pushing")
    parser.add_argument("--validate-only", action="store_true", help="Run structural validations and exit")
    parser.add_argument("--force", action="store_true", help="Bypass deterministic block deduplication")
    parser.add_argument("--catalog-file", type=str, default=None, help="Path to catalog index.min.json")
    parser.add_argument("--distribution-dir", type=str, default=None, help="Path to distribution repository")
    parser.add_argument("--staging-dir", type=str, default=None, help="Path to APK staging directory")
    parser.add_argument("--recover-pending", action="store_true", help="Force publication recovery")
    args = parser.parse_args()

    if sum((args.dry_run, args.push, args.apply_no_push, args.validate_only)) > 1:
        print("Use only one of --dry-run, --push, --apply-no-push, or --validate-only", file=sys.stderr)
        sys.exit(1)

    if args.validate_only:
        errors = run_structural_validations()
        if errors:
            for err in errors:
                print(f"VALIDATION_ERROR: {err}", file=sys.stderr)
            sys.exit(1)
        print("STRUCTURAL_VALIDATION=PASS")
        return

    if not args.dry_run:
        ensure_clean_tree()

    ensure_upstream_remote()

    git("fetch", "origin")
    start_main = git("rev-parse", "origin/main").strip()
    start_head = git("rev-parse", "HEAD").strip()
    code_sha = get_code_sha("HEAD")
    git("fetch", UPSTREAM_REMOTE, UPSTREAM_BRANCH)

    upstream_ref = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}"
    upstream_head = git("rev-parse", upstream_ref).strip()

    sync_state = load_sync_state()
    blocked_cand = sync_state.get("blocked_candidate") if isinstance(sync_state.get("blocked_candidate"), dict) else None
    if (
        not args.force
        and blocked_cand
        and blocked_cand.get("code_sha") == code_sha
        and blocked_cand.get("upstream_sha") == upstream_head
    ):
        print("==================================================")
        print("DEDUPLICATED DETERMINISTIC FAILURE DETECTED")
        print(f"Failure Type: {blocked_cand.get('failure_type')}")
        print(f"Cause: {blocked_cand.get('cause')}")
        print(f"Inputs unchanged (code={code_sha[:10]}, upstream={upstream_head[:10]}).")
        print("Skipping redundant retry until main code or upstream advances.")
        print("==================================================")
        set_github_output("should_pulse", "false")
        print_observability_footer("BLOCKED_DETERMINISTIC", "SKIPPED", "SKIPPED")
        return

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

    infra_paths_in_diff = {
        path for _, paths in upstream_entries for path in paths
        if path in INFRA_EXACT_PATHS or path.startswith(INFRA_PREFIXES)
    }
    new_up_top_modules = discover_upstream_top_level_modules(upstream_ref) - {m.name for m in discover_local_top_level_modules()}
    catalog_pending = (
        reconcile_version_catalog(upstream_ref, LIBS_CATALOG_FILE, apply=False)
        or reconcile_version_catalog(upstream_ref, KEI_CATALOG_FILE, apply=False)
        or reconcile_settings_modules(upstream_ref, apply=False)
    )
    infra_changed = bool(infra_paths_in_diff or new_up_top_modules or catalog_pending)
    core_changed = bool(
        infra_changed
        or any(
            path in {"core", "common", "extractor", "extensions-lib"}
            or path.startswith(("core/", "common/", "extractor/", "extensions-lib/"))
            for _, paths in upstream_entries
            for path in paths
        )
    )
    multisrc_changed = any(
        path.startswith("lib-multisrc/")
        for _, paths in upstream_entries
        for path in paths
    )
    no_changes = (
        not upstream_units
        and not indirect
        and not upstream_deleted
        and not effective_diff
        and not infra_changed
    )

    trigger = os.environ.get("GITHUB_EVENT_NAME", "workflow_dispatch")
    local_head = start_head
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

    res_pending = get_publish_pending_units(
        base=base,
        upstream_ref=upstream_ref,
        catalog_target=args.catalog_file,
        distribution_dir=args.distribution_dir,
        return_context=True,
    )
    if isinstance(res_pending, tuple):
        pending_pub = res_pending[0]
        resolved_dist_dir = res_pending[1]
        has_inconclusive = res_pending[2]
        target_kind = res_pending[3] if len(res_pending) > 3 else ("file" if resolved_dist_dir else "url")
        resolved_target = res_pending[4] if len(res_pending) > 4 else None
    else:
        pending_pub = res_pending
        resolved_dist_dir = None
        has_inconclusive = False
        target_kind = "file" if args.distribution_dir else "url"
        resolved_target = None

    if no_changes and pending_pub:
        print("==================================================")
        print("PUBLICATION_PENDING_DETECTED")
        print(f"Pending publication units ({len(pending_pub)}): {', '.join(pending_pub)}")
        print("==================================================")

        pub_block = sync_state.get("publication_block") if isinstance(sync_state.get("publication_block"), dict) else None
        if (
            not args.force
            and pub_block
            and pub_block.get("code_sha") == code_sha
            and pub_block.get("failure_type") == "DETERMINISTIC_BUILD_FAILURE"
        ):
            print("==================================================")
            print("PUBLICATION_RECOVERY_SUSPENDED (DETERMINISTIC FAILURE)")
            print(f"Code SHA: {code_sha[:10]} | Cause: {pub_block.get('cause')}")
            print("Skipping redundant build_push.yml dispatch until code changes.")
            print("==================================================")
            set_github_output("should_pulse", "false")
            print_observability_footer("NO_CHANGES", "SKIPPED", "BLOCKED_DETERMINISTIC")
            return

        if args.dry_run or (not args.push and not args.apply_no_push and not args.recover_pending):
            print("Dry run — publication recovery planned without modification.")
            print_observability_footer("NO_CHANGES", "SKIPPED", "SKIPPED")
            return

        if args.push:
            try:
                ensure_main_unchanged(start_main)
            except ConcurrencyRaceError as e:
                print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                sys.exit(EXIT_TRANSIENT_CONCURRENCY)

        recovery_status = recover_pending_publications(
            pending_pub,
            push=args.push,
            start_main=start_main,
            upstream_ref=upstream_ref,
            distribution_dir=args.distribution_dir or resolved_dist_dir,
            staging_dir=args.staging_dir,
            catalog_target=args.catalog_file,
            target_kind=target_kind,
            resolved_target=resolved_target,
        )

        if recovery_status == "BLOCKED_DETERMINISTIC":
            if args.push:
                commit_state_only(
                    f"chore(sync): suspend deterministic publication loop on {code_sha[:10]}",
                    push=True,
                    start_main=start_main,
                )
            set_github_output("should_pulse", "false")
            print_observability_footer("NO_CHANGES", "SKIPPED", "BLOCKED_DETERMINISTIC")
            return
        elif recovery_status == "RECOVERED":
            if args.push:
                try:
                    publish_sync(upstream_ref, start_main)
                except ConcurrencyRaceError as e:
                    print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                    sys.exit(EXIT_TRANSIENT_CONCURRENCY)
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
            set_github_output("should_pulse", "true")
            if has_inconclusive:
                print("::warning::Publication recovery completed for known pending units, but some artifact checks were inconclusive due to network errors.", file=sys.stderr)
                print_observability_footer("NO_CHANGES", "SUCCESS", "RECOVERED_INCONCLUSIVE")
                return
            print_observability_footer("NO_CHANGES", "SUCCESS", "RECOVERED")
            return
        elif recovery_status == "DISPATCHED":
            if args.push:
                commit_state_only(
                    f"chore(sync): record publication recovery attempt on {code_sha[:10]}",
                    push=False,
                )
                try:
                    publish_sync(upstream_ref, start_main)
                except ConcurrencyRaceError as e:
                    print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                    sys.exit(EXIT_TRANSIENT_CONCURRENCY)
            set_github_output("should_pulse", "true")
            print("Publication recovery dispatched to CI workflow.")
            if has_inconclusive:
                print("::warning::Publication recovery dispatched for known pending units, but some artifact checks were inconclusive due to network errors.", file=sys.stderr)
                print_observability_footer("NO_CHANGES", "SUCCESS", "TRIGGERED_INCONCLUSIVE")
                return
            print_observability_footer("NO_CHANGES", "SUCCESS", "TRIGGERED")
            return
        elif recovery_status == "DEFERRED":
            if args.push:
                commit_state_only(
                    f"chore(sync): defer publication recovery on {code_sha[:10]}",
                    push=True,
                    start_main=start_main,
                )
            set_github_output("should_pulse", "true")
            print("Publication recovery deferred: active workflow build_push.yml is in progress or backoff is active.")
            if has_inconclusive:
                print("::warning::Publication recovery deferred while active build runs, and some artifact checks were inconclusive.", file=sys.stderr)
                print_observability_footer("NO_CHANGES", "SUCCESS", "DEFERRED_INCONCLUSIVE")
                return
            print_observability_footer("NO_CHANGES", "SUCCESS", "DEFERRED")
            return
        else:
            print("Publication recovery failed: artifact unavailable or workflow dispatch failed.", file=sys.stderr)
            set_github_output("should_pulse", "false")
            print_observability_footer("NO_CHANGES", "FAILURE", "FAILED")
            sys.exit(1)

    if no_changes:
        if has_inconclusive and not pending_pub:
            print("::error::Artifact verification checks were inconclusive due to network errors. Failing controlled to avoid false clean confirmation.", file=sys.stderr)
            set_github_output("should_pulse", "false")
            print_observability_footer("NO_CHANGES", "FAILURE", "INCONCLUSIVE")
            sys.exit(1)
        print("No upstream changes to apply.")
        set_github_output("should_pulse", "false")
        if args.push:
            try:
                publish_sync(upstream_ref, start_main)
            except ConcurrencyRaceError as e:
                print(f"Transient concurrency conflict: {e}", file=sys.stderr)
                sys.exit(EXIT_TRANSIENT_CONCURRENCY)
        print_observability_footer("NO_CHANGES", "SKIPPED", "SKIPPED")
        return

    if args.dry_run or not (args.push or args.apply_no_push):
        print("Dry run — no changes applied.")
        print_observability_footer("SUCCESS", "SKIPPED", "SKIPPED")
        return

    deployed_codes = fetch_deployed_version_codes()
    deferred_incompats = load_deferred_incompatibilities()
    units_to_apply = sorted(
        set(upstream_units)
        | set(upstream_deleted)
        | set(protected_deletions)
        | set(effective_diff)
        | set(indirect_clean)
        | set(deferred_incompats.keys())
    )
    validation_units = sorted(
        {
            u for u in (set(new_units) | set(updated_units) | set(indirect_clean))
            if u.startswith("src/") and not is_ptbr_unit(u) and u not in set(removed_units)
        }
    )

    try:
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
        ok, failed_units, err_out = validate_candidate_with_gradle(
            "HEAD",
            infra_changed=infra_changed,
            validation_units=validation_units,
        )
        if not ok:
            infra_failed = any(
                u in {"core", "compiler", "extensions-lib", "gradle/build-logic", "gradle-infra"}
                for u in failed_units
            )
            widespread = len(failed_units) >= 10 and len(failed_units) > max(1, len(validation_units)) * 0.4
            if infra_changed or infra_failed or widespread:
                raise StructuralIncompatibilityError(
                    f"Gradle preflight validation failed on {', '.join(failed_units)}: {err_out[-600:]}",
                    failed_units=failed_units,
                )
            newly_deferred: dict[str, dict[str, object]] = dict(deferred_incompats)
            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
            for fu in failed_units:
                if fu.startswith("src/"):
                    newly_deferred[fu] = {
                        "module": fu,
                        "upstream_commit": upstream_head[:10],
                        "cause": err_out[-400:],
                        "recorded_at": now_iso,
                    }
            if not newly_deferred:
                raise StructuralIncompatibilityError(
                    f"Gradle preflight failed: {err_out[-600:]}",
                    failed_units=failed_units,
                )
            git("reset", "--hard", start_head)
            remaining_val = [u for u in validation_units if u not in newly_deferred]
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
                deferred_incompatibilities=newly_deferred,
            )
            ok2, failed2, err2 = validate_candidate_with_gradle(
                "HEAD",
                infra_changed=infra_changed,
                validation_units=remaining_val,
            )
            if not ok2:
                raise StructuralIncompatibilityError(
                    f"Gradle preflight still failed after isolation ({', '.join(failed2)}): {err2[-600:]}",
                    failed_units=failed2,
                )
    except TransientNetworkError as exc:
        print(f"Transient network/Gradle failure before push: {exc}", file=sys.stderr)
        git("reset", "--hard", start_head, check=False)
        set_github_output("should_pulse", "false")
        print_observability_footer("TRANSIENT_FAILURE", "SKIPPED", "SKIPPED")
        sys.exit(EXIT_TRANSIENT_NETWORK)
    except StructuralIncompatibilityError as exc:
        print(f"BLOCKED_STRUCTURAL: {exc}", file=sys.stderr)
        git("reset", "--hard", start_head, check=False)
        _record_and_persist_block(
            code_sha=code_sha,
            upstream_head=upstream_head,
            failure_type="BLOCKED_STRUCTURAL",
            cause=str(exc),
            failed_units=exc.failed_units,
            push=args.push,
            start_main=start_main,
        )
        write_step_summary(
            protected=protected,
            bumped=[],
            new_units=[],
            updated_units=[],
            removed_units=[],
            sync_result="BLOCKED_STRUCTURAL",
        )
        set_github_output("should_pulse", "false")
        print_observability_footer("BLOCKED_STRUCTURAL", "SKIPPED", "SKIPPED")
        sys.exit(1)

    write_step_summary(
        protected=protected,
        bumped=bumped,
        new_units=new_units,
        updated_units=updated_units,
        removed_units=removed_units,
        sync_result="SUCCESS",
    )
    if args.push:
        try:
            publish_sync(upstream_ref, start_main)
        except ConcurrencyRaceError as e:
            print(f"Transient concurrency conflict: {e}", file=sys.stderr)
            git("reset", "--hard", start_head, check=False)
            sys.exit(EXIT_TRANSIENT_CONCURRENCY)
        set_github_output("should_pulse", "true")
        print_observability_footer("SUCCESS", "SUCCESS", "TRIGGERED")
    else:
        print("Applied and validated locally; no push requested.")
        print_observability_footer("SUCCESS", "SKIPPED", "SKIPPED")



if __name__ == "__main__":
    main()
