#!/usr/bin/env python3
"""
Project Nox Anime — Sync Upstream (Yuzono Nox-Wins)

Regras:
  - upstream normal (não tocado pelo Nox) → entra normalmente
  - extensão Nox modificada + upstream não tocou → Nox permanece
  - conflito (ambos modificaram mesma unidade) → Nox vence (código Nox fica, versão é guardada)
  - upstream efetivo >= Nox efetivo → mantém código Nox, sobe versão para upstream_efetivo + 1
  - extensão exclusiva Nox → preservada
  - nova extensão Yuzono → importada
  - mudança indireta (lib/lib-multisrc/core/common/extractor) → detectada via grafo de dependências

Versionamento real do anime (Yuzono):
  Standalone:  versionCode = extVersionCode (em build.gradle)
  Multisrc:    versionCode = baseVersionCode (theme) + overrideVersionCode (extension)
  Efetivo:     standalone → extVersionCode
               multisrc   → baseVersionCode + overrideVersionCode

Upstream definitivo: https://github.com/yuzono/anime-extensions (branch master)
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
UPSTREAM_BRANCH = "master"
SYNC_BRANCH = "sync"

# ──────────────────────────────────────────────────────────────────────────────
# Versioning patterns — anime/Yuzono architecture
# ──────────────────────────────────────────────────────────────────────────────
# In standalone extensions (build.gradle Groovy):
#   extVersionCode = <N>
_EXT_VERSION_CODE_RE = re.compile(r"extVersionCode\s*=\s*(\d+)")

# In multisrc extensions (build.gradle Groovy):
#   overrideVersionCode = <N>
_OVERRIDE_VERSION_CODE_RE = re.compile(r"overrideVersionCode\s*=\s*(\d+)")

# themePkg in extension build.gradle
_THEME_PKG_RE = re.compile(r"""themePkg\s*=\s*['"]([^'"]+)['"]""")

# In lib-multisrc/<theme>/build.gradle.kts (Kotlin):
#   baseVersionCode = <N>
_BASE_VERSION_CODE_RE = re.compile(r"baseVersionCode\s*=\s*(\d+)")


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
        return p.read_text() if p.exists() else None
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"], capture_output=True, text=True
    )
    return result.stdout if result.returncode == 0 else None


# ──────────────────────────────────────────────────────────────────────────────
# Diff helpers
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
        for lib_dir in Path("lib").iterdir():
            if lib_dir.name in all_deps or lib_dir.name in libs:
                continue
            build_file = lib_dir / "build.gradle.kts"
            if not build_file.is_file():
                continue
            if pattern.search(build_file.read_text("utf-8")):
                all_deps.add(lib_dir.name)
                to_process.add(lib_dir.name)
    return all_deps


def resolve_multisrc_from_libs(libs: set[str]) -> set[str]:
    """Find lib-multisrc themes that depend on the given libs."""
    if not libs:
        return set()
    pattern = re.compile(
        rf"project\((?:path(?: =|:) )?[\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
    )
    themes: set[str] = set()
    for ms_dir in Path("lib-multisrc").iterdir():
        build_file = ms_dir / "build.gradle.kts"
        if not build_file.is_file():
            continue
        if pattern.search(build_file.read_text("utf-8")):
            themes.add(ms_dir.name)
    return themes


def resolve_extensions_from(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    """Find extensions that depend on given multisrcs or libs (via build.gradle)."""
    if not multisrcs and not libs:
        return set()
    patterns = []
    if multisrcs:
        patterns.append(
            rf"themePkg\s*=\s*['\"]({'|'.join(map(re.escape, multisrcs))})['\"]"
        )
    if libs:
        patterns.append(
            rf"project\((?:path(?: =|:) )?[\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
        )
    regex = re.compile("|".join(patterns))
    extensions: set[tuple[str, str]] = set()
    for lang_dir in Path("src").iterdir():
        if not lang_dir.is_dir():
            continue
        for ext_dir in lang_dir.iterdir():
            build_file = ext_dir / "build.gradle"
            if not build_file.is_file():
                continue
            if regex.search(build_file.read_text("utf-8")):
                extensions.add((lang_dir.name, ext_dir.name))
    return extensions


def detect_indirect_impacts(upstream_units: list[str]) -> set[str]:
    """
    Given upstream changes, expand impact to include extensions affected indirectly
    via lib/lib-multisrc dependency chains.
    Returns additional src/ units affected indirectly.
    """
    changed_libs = {
        u.split("/")[1] for u in upstream_units if u.startswith("lib/")
    }
    changed_multisrcs = {
        u.split("/")[1] for u in upstream_units if u.startswith("lib-multisrc/")
    }

    # Expand libs transitively
    changed_libs |= resolve_dependent_libs(changed_libs)
    # Expand multisrcs from libs
    changed_multisrcs |= resolve_multisrc_from_libs(changed_libs)
    # Find affected extensions
    affected_exts = resolve_extensions_from(changed_multisrcs, changed_libs)
    return {f"src/{lang}/{ext}" for lang, ext in affected_exts}


# ──────────────────────────────────────────────────────────────────────────────
# Version guard — Yuzono/anime real versioning model
# ──────────────────────────────────────────────────────────────────────────────

def read_base_version_code(ref: str | None, theme: str) -> int:
    """Read baseVersionCode from lib-multisrc/<theme>/build.gradle.kts."""
    content = read_file_at(ref, f"lib-multisrc/{theme}/build.gradle.kts")
    if not content:
        return 0
    m = _BASE_VERSION_CODE_RE.search(content)
    return int(m.group(1)) if m else 0


def effective_version(ref: str | None, unit: str) -> tuple[int, int, int] | None:
    """
    Returns (raw, base, effective) for an extension unit.

    Standalone: raw=extVersionCode, base=0, effective=extVersionCode
    Multisrc:   raw=overrideVersionCode, base=baseVersionCode(theme), effective=base+raw
    """
    # Extensions use build.gradle (Groovy), not build.gradle.kts
    content = read_file_at(ref, f"{unit}/build.gradle")
    if not content:
        return None

    # Is it multisrc?
    theme_match = _THEME_PKG_RE.search(content)
    if theme_match:
        theme = theme_match.group(1)
        override_match = _OVERRIDE_VERSION_CODE_RE.search(content)
        if not override_match:
            return None
        raw = int(override_match.group(1))
        base = read_base_version_code(ref, theme)
        return raw, base, raw + base
    else:
        # Standalone
        vc_match = _EXT_VERSION_CODE_RE.search(content)
        if not vc_match:
            return None
        raw = int(vc_match.group(1))
        return raw, 0, raw


def bump_version_if_needed(
    unit: str, upstream_ref: str
) -> tuple[int, int, int, int, int, int, int] | None:
    """
    If upstream effective >= local effective, bump local version so that
    local effective = upstream effective + 1.

    Returns (loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw) if bumped.
    Rewrites the file on disk. Caller must git add.
    """
    local = effective_version(None, unit)
    upstream = effective_version(upstream_ref, unit)

    if local is None or upstream is None:
        return None

    loc_raw, loc_base, loc_eff = local
    up_raw, up_base, up_eff = upstream

    if up_eff < loc_eff:
        return None  # local is already ahead

    new_raw = (up_eff + 1) - loc_base
    build_file = Path(f"{unit}/build.gradle")
    text = build_file.read_text()

    # Replace in multisrc: overrideVersionCode
    # Replace in standalone: extVersionCode
    if _THEME_PKG_RE.search(text):
        new_text = _OVERRIDE_VERSION_CODE_RE.sub(
            lambda m: m.group(0).replace(str(loc_raw), str(new_raw), 1), text
        )
    else:
        new_text = _EXT_VERSION_CODE_RE.sub(
            lambda m: m.group(0).replace(str(loc_raw), str(new_raw), 1), text
        )

    build_file.write_text(new_text)
    return loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw


def get_nox_protected_units(base: str, upstream_ref: str) -> list[str]:
    """
    Detect extensions that Nox has modified since the merge-base.
    These are 'protected' — Nox wins on conflict, version guard applies.
    Detection is automatic (no manual list).
    """
    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)
    return [
        u
        for u in sorted(main_units)
        if u.startswith("src/")
        and path_exists(upstream_ref, f"{u}/build.gradle")
    ]


# ──────────────────────────────────────────────────────────────────────────────
# Sync plan printing
# ──────────────────────────────────────────────────────────────────────────────

def print_plan(
    base: str,
    upstream_ref: str,
    upstream_only: list[str],
    conflict_units: list[str],
    nox_only: list[str],
    protected: list[str],
    indirect: set[str],
    preserved_paths: list[str],
) -> None:
    commits = git("rev-list", "--count", f"{base}..{upstream_ref}").strip()
    print(f"\nBase: {base}")
    print(f"Upstream commits since base: {commits}")
    print(f"\nUpstream-only units to apply: {len(upstream_only)}")
    for u in upstream_only:
        print(f"  [upstream] {u}")
    print(f"\nConflict units (Nox wins): {len(conflict_units)}")
    for u in conflict_units:
        print(f"  [conflict→Nox] {u}")
    print(f"\nNox-only units (preserved): {len(nox_only)}")
    for u in nox_only:
        print(f"  [nox-only] {u}")
    print(f"\nIndirectly affected by lib/multisrc: {len(indirect)}")
    for u in sorted(indirect):
        print(f"  [indirect] {u}")
    if preserved_paths:
        print(f"\n.github paths preserved: {len(preserved_paths)}")
    print(f"\nVersion guard on protected units ({len(protected)}):")
    for u in protected:
        local = effective_version(None, u)
        up = effective_version(upstream_ref, u)
        if not local or not up:
            continue
        loc_raw, loc_base, loc_eff = local
        up_raw, up_base, up_eff = up
        print(f"  {u}:")
        print(f"    local  raw={loc_raw} base={loc_base} eff={loc_eff}")
        print(f"    upstream raw={up_raw} base={up_base} eff={up_eff}")
        if up_eff >= loc_eff:
            new_raw = (up_eff + 1) - loc_base
            print(f"    action: BUMP raw {loc_raw} → {new_raw}")
        else:
            print(f"    action: keep (local ahead)")


# ──────────────────────────────────────────────────────────────────────────────
# Apply
# ──────────────────────────────────────────────────────────────────────────────

def apply_units(
    upstream_ref: str,
    upstream_units: list[str],
    conflict_set: set[str],
    protected: list[str],
) -> list[str]:
    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    for unit in upstream_units:
        if unit in conflict_set:
            continue  # Nox wins — skip upstream version of this unit
        print(f"Applying {unit}")
        git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)
        if path_exists(upstream_ref, unit):
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

    bumped: list[str] = []
    for unit in protected:
        res = bump_version_if_needed(unit, upstream_ref)
        if res is not None:
            loc_raw, _, _, _, _, _, new_raw = res
            git("add", "--", f"{unit}/build.gradle")
            bumped.append(f"{unit} → {new_raw}")
            print(f"Version guard: bumped {unit} ({loc_raw} → {new_raw})")
        else:
            print(f"Version guard: {unit} already ahead, no bump needed")

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


# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Project Nox Anime — Sync Yuzono upstream")
    parser.add_argument("--dry-run", action="store_true", help="Show plan only, no changes")
    parser.add_argument("--push", action="store_true", help="Apply and push")
    args = parser.parse_args()

    if args.dry_run and args.push:
        print("Use either --dry-run or --push", file=sys.stderr)
        sys.exit(1)

    ensure_clean_tree()
    ensure_upstream_remote()

    git("fetch", "origin")
    git("fetch", UPSTREAM_REMOTE, UPSTREAM_BRANCH)

    upstream_ref = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}"
    base = git("merge-base", "HEAD", upstream_ref).strip()

    upstream_entries = changed_entries(base, upstream_ref)
    upstream_units, preserved_paths = collect_units(upstream_entries)

    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)

    conflict_units = sorted(set(upstream_units) & set(main_units))
    conflict_set = set(conflict_units)
    upstream_only = sorted(set(upstream_units) - conflict_set)
    nox_only = sorted(set(main_units) - set(upstream_units))

    protected = get_nox_protected_units(base, upstream_ref)
    indirect = detect_indirect_impacts(upstream_units)

    print_plan(
        base, upstream_ref,
        upstream_only, conflict_units, nox_only,
        protected, indirect, preserved_paths,
    )

    # Update sync branch to mirror upstream tip
    if args.push:
        git("push", "origin", f"{upstream_ref}:refs/heads/{SYNC_BRANCH}", "--force")
    else:
        print(f"\nWould update origin/{SYNC_BRANCH} from {upstream_ref}")

    if not upstream_units and not indirect:
        print("No upstream changes to apply.")
        return

    if args.dry_run or not args.push:
        print("Dry run — no changes applied.")
        return

    bumped = apply_units(upstream_ref, upstream_units, conflict_set, protected)
    write_step_summary(protected, bumped)
    git("push", "origin", "HEAD:main")


if __name__ == "__main__":
    main()
