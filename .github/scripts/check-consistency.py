#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
"""
Project Nox Anime — Consistency Checker

Compares Upstream HEAD, Synced Upstream SHA, Main HEAD, and Published Catalog.
Verifies version monotonicity, checks for pending deliveries, and reports state.
"""

import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
DISTRIBUTION_REPO = "Awerkori/anime-extensoes"
DISTRIBUTION_INDEX_URL = f"https://raw.githubusercontent.com/{DISTRIBUTION_REPO}/repo/index.min.json"
SYNC_STATE_FILE = Path(".github/sync-state.json")
NOX_PROTECTED_FILE = Path(".github/nox-protected.txt")
AUDIT_EXCEPTIONS_FILE = Path(".github/audit-exceptions.json")


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


def parse_github_repo_and_branch(url: str) -> tuple[str, str, str] | None:
    """Extract (owner/repo, branch, subdir) from GitHub raw, jsdelivr, api, or tree URLs."""
    m = re.search(r"raw\.githubusercontent\.com/([^/]+/[^/]+)/([^/]+)(?:/(.*))?", url)
    if m:
        path = m.group(3) or ""
        subdir = "/".join(path.split("/")[:-1]) if "/" in path else ""
        return m.group(1), m.group(2), subdir
    m = re.search(r"cdn\.jsdelivr\.net/gh/([^/@]+/[^/@]+)@([^/]+)(?:/(.*))?", url)
    if m:
        path = m.group(3) or ""
        subdir = "/".join(path.split("/")[:-1]) if "/" in path else ""
        return m.group(1), m.group(2), subdir
    m = re.search(r"api\.github\.com/repos/([^/]+/[^/]+)/git/trees/([^?]+)", url)
    if m:
        return m.group(1), m.group(2), ""
    m = re.search(r"github\.com/([^/]+/[^/]+)/(?:raw|tree)/([^/]+)(?:/(.*))?", url)
    if m:
        path = m.group(3) or ""
        subdir = "/".join(path.split("/")[:-1]) if "/" in path else ""
        return m.group(1), m.group(2), subdir
    return None


def get_github_auth_token() -> str | None:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    try:
        res = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
        if res.returncode == 0 and res.stdout.strip():
            return res.stdout.strip()
    except Exception:
        pass
    try:
        # Strictly query extraheader scoped to https://github.com
        res = subprocess.run(["git", "config", "--get-urlmatch", "http.extraheader", "https://github.com"], capture_output=True, text=True)
        if res.returncode != 0 or not res.stdout.strip():
            res = subprocess.run(["git", "config", "--get-regexp", r"^http\.https://github\.com/\.extraheader$"], capture_output=True, text=True)
        if res.returncode == 0 and res.stdout.strip():
            import base64
            m = re.search(r"basic\s+([A-Za-z0-9+/=]+)", res.stdout, re.IGNORECASE)
            if m:
                decoded = base64.b64decode(m.group(1)).decode("utf-8", errors="ignore")
                if ":" in decoded:
                    return decoded.split(":", 1)[1]
    except Exception:
        pass
    return None


def get_remote_distribution_apks(catalog_url: str | None = None) -> tuple[set[str], set[str]] | None:
    """
    Fetch the ground-truth list of deployed APK file names from the distribution server.
    Queries the GitHub Git Tree API for actual Git blob objects with size > 0.
    Returns (valid_apks, empty_apks), or None if Git Tree API is unavailable or non-GitHub.
    """
    if not catalog_url:
        catalog_url = DISTRIBUTION_INDEX_URL

    clean_url = catalog_url.split("?", 1)[0]
    parsed = parse_github_repo_and_branch(clean_url)

    if parsed:
        repo_slug, branch_ref, subdir = parsed
        gh_token = get_github_auth_token()
        tree_url = f"https://api.github.com/repos/{repo_slug}/git/trees/{branch_ref}?recursive=1"
        try:
            headers = {"User-Agent": "Nox-Consistency/1.0"}
            if gh_token:
                headers["Authorization"] = f"token {gh_token}"
            req = urllib.request.Request(tree_url, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if "tree" in data:
                    tree = data["tree"]
                    tree_prefix = f"{subdir}/apk/" if subdir else "apk/"
                    valid_apks = set()
                    empty_apks = set()
                    for x in tree:
                        if (
                            x.get("path", "").startswith(tree_prefix)
                            and x.get("path", "").endswith(".apk")
                            and x.get("type") == "blob"
                        ):
                            apk_n = x["path"].removeprefix(tree_prefix)
                            if (x.get("size") or 0) > 0:
                                valid_apks.add(apk_n)
                            else:
                                empty_apks.add(apk_n)
                    return valid_apks, empty_apks
        except Exception:
            pass

    return None


def check_remote_apk_status(catalog_url: str, apk_name: str) -> str:
    """
    Check remote APK existence via HTTP HEAD.
    Returns:
    - 'EXISTS': HTTP 200 and Content-Length > 0
    - 'MISSING': HTTP 404 Not Found, or Content-Length == 0
    - 'ERROR': Connection error, timeout, rate limit, or server error (5xx)
    """
    clean_url = catalog_url.split("?", 1)[0]
    parent_url = clean_url.rsplit("/", 1)[0] if "/" in clean_url else clean_url
    apk_url = f"{parent_url}/apk/{apk_name}"
    try:
        req = urllib.request.Request(
            apk_url,
            method="HEAD",
            headers={"User-Agent": "Nox-Consistency/1.0"},
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                content_length = resp.headers.get("Content-Length")
                if content_length is not None:
                    try:
                        if int(content_length) <= 0:
                            return "MISSING"
                        return "EXISTS"
                    except (ValueError, TypeError):
                        return "MISSING"
                # If Content-Length header is omitted, treat as ERROR (inconclusive) to avoid falsely accepting 0-byte APKs
                return "ERROR"
            elif resp.status == 404:
                return "MISSING"
            return "ERROR"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return "MISSING"
        return "ERROR"
    except Exception:
        return "ERROR"


def verify_remote_apk_exists(catalog_url: str, apk_name: str) -> bool:
    """Directly verify remote APK existence via HTTP HEAD, rejecting 0-byte or empty artifacts."""
    return check_remote_apk_status(catalog_url, apk_name) == "EXISTS"


def check_remote_apks_concurrent(
    catalog_url: str,
    apk_names: list[str],
    max_workers: int = 16,
    timeout_seconds: float = 5.0,
) -> dict[str, str]:
    """
    Concurrently check existence of multiple remote APKs via HTTP HEAD requests
    with bounded worker concurrency (Codex Finding P1).
    Returns mapping of apk_name -> ("EXISTS" | "MISSING" | "ERROR").
    """
    clean_url = catalog_url.split("?", 1)[0]
    parent_url = "/".join(clean_url.split("/")[:-1])

    def probe_one(name: str) -> tuple[str, str]:
        apk_url = f"{parent_url}/apk/{name}"
        try:
            req = urllib.request.Request(
                apk_url,
                method="HEAD",
                headers={"User-Agent": "Nox-Consistency/1.0"},
            )
            with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
                if resp.status == 200:
                    cl = resp.headers.get("Content-Length")
                    if cl is not None:
                        try:
                            if int(cl) <= 0:
                                return name, "MISSING"
                            return name, "EXISTS"
                        except (ValueError, TypeError):
                            return name, "MISSING"
                    return name, "ERROR"
                elif resp.status == 404:
                    return name, "MISSING"
                return name, "ERROR"
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return name, "MISSING"
            return name, "ERROR"
        except Exception:
            return name, "ERROR"

    results = {}
    if not apk_names:
        return results

    workers = max(1, min(max_workers, len(apk_names)))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        future_to_name = {pool.submit(probe_one, name): name for name in apk_names}
        for future in concurrent.futures.as_completed(future_to_name):
            try:
                name, status = future.result()
                results[name] = status
            except Exception:
                name = future_to_name[future]
                results[name] = "ERROR"

    return results


def get_deployed_catalog(
    catalog_target: str | None = None,
    distribution_dir: str | Path | None = None,
) -> tuple[list[dict] | None, str, str, Path | None, set[str] | None, str | None]:
    """
    Fetch catalog and resolve artifact verification context strictly matching
    the resolved catalog source:
    - Explicit distribution_dir or local file catalog -> local_apk_dir bound strictly to that directory
    - Remote URL catalog -> remote_apks bound strictly to distribution server Git Tree API
    Returns (deployed_items, catalog_source, catalog_status, local_apk_dir, remote_apks, resolved_target_url).
    """
    candidate_sources = []
    if catalog_target:
        if catalog_target.startswith("http://") or catalog_target.startswith("https://"):
            candidate_sources.append(("url", catalog_target, "Custom URL Target"))
        else:
            candidate_sources.append(("file", catalog_target, "Custom File Target"))
    elif distribution_dir:
        candidate_sources.append((
            "file",
            str(Path(distribution_dir) / "index.min.json"),
            "Explicit Distribution Dir Catalog",
        ))
    else:
        env_target = os.environ.get("NOX_CATALOG_TARGET")
        if env_target:
            if env_target.startswith("http://") or env_target.startswith("https://"):
                candidate_sources.append(("url", env_target, "Env URL Target"))
            else:
                candidate_sources.append(("file", env_target, "Env File Target"))

        import time
        ts = int(time.time())
        candidate_sources.extend([
            ("url", DISTRIBUTION_INDEX_URL, "GitHub Raw (Primary)"),
            ("url", f"https://cdn.jsdelivr.net/gh/{DISTRIBUTION_REPO}@repo/index.min.json?_t={ts}", "jsDelivr CDN (Fallback 1)"),
            ("file", str(Path("../anime-extensoes/index.min.json")), "Local Clone (Fallback 2)"),
            ("file", str(Path(__file__).resolve().parents[2].parent / "anime-extensoes/index.min.json"), "Local Clone (Fallback 2)"),
            ("file", str(Path(__file__).resolve().parents[3] / "anime-extensoes/index.min.json"), "Local Clone (Fallback 2)"),
        ])

    first_catalog_fallback = None

    for kind, target, label in candidate_sources:
        try:
            raw = None
            if kind == "url":
                req = urllib.request.Request(
                    target,
                    headers={
                        "User-Agent": "Nox-Consistency/1.0",
                        "Cache-Control": "no-cache, no-store, must-revalidate",
                        "Pragma": "no-cache",
                    },
                )
                with urllib.request.urlopen(req, timeout=10) as resp:
                    raw = resp.read().decode("utf-8")
            elif kind == "file":
                p = Path(target)
                if p.is_file():
                    raw = p.read_text(encoding="utf-8")
            if not raw:
                continue

            data = json.loads(raw)
            if not isinstance(data, list) or len(data) == 0 or not isinstance(data[0], dict) or "pkg" not in data[0]:
                continue

            local_apk_dir = None
            remote_apks = None
            if kind == "file":
                if distribution_dir:
                    dist_p = Path(distribution_dir).resolve()
                    if (dist_p / "apk").is_dir():
                        local_apk_dir = dist_p / "apk"
                    elif any(dist_p.glob("*.apk")):
                        local_apk_dir = dist_p
                    else:
                        local_apk_dir = dist_p / "apk"
                else:
                    parent_dir = Path(target).resolve().parent
                    if (parent_dir / "apk").is_dir():
                        local_apk_dir = parent_dir / "apk"
                    elif any(parent_dir.glob("*.apk")):
                        local_apk_dir = parent_dir
                    else:
                        local_apk_dir = parent_dir / "apk"
                return data, label, "VERIFIED", local_apk_dir, None, target
            elif kind == "url":
                if distribution_dir:
                    dist_p = Path(distribution_dir).resolve()
                    if (dist_p / "apk").is_dir():
                        local_apk_dir = dist_p / "apk"
                    elif any(dist_p.glob("*.apk")):
                        local_apk_dir = dist_p
                    else:
                        local_apk_dir = dist_p / "apk"
                    return data, label, "VERIFIED", local_apk_dir, None, target
                remote_apks = get_remote_distribution_apks(target)
                if remote_apks is not None:
                    return data, label, "VERIFIED", None, remote_apks, target
                # Probe artifact availability via bounded HEAD check:
                # If any sample returns EXISTS or MISSING, the server is actively serving the apk directory.
                sample_apks = [x.get("apk") for x in data[:3] if isinstance(x, dict) and x.get("apk")]
                if sample_apks and any(check_remote_apk_status(target, apk) in ("EXISTS", "MISSING") for apk in sample_apks):
                    return data, label, "VERIFIED", None, None, target
                # Reachable remote catalog URL succeeded, but artifact blobs could not be confirmed.
                # Do NOT fall through to an unrelated or stale local clone (Section 5).
                return data, label, "UNVERIFIED_ARTIFACTS", None, None, target
        except Exception:
            continue

    if first_catalog_fallback is not None:
        return first_catalog_fallback

    print("Warning: could not fetch distribution catalog from any source. Catalog integrity UNVERIFIED.")
    return None, "NONE", "Catalog fetch failed from all sources", None, None, None


def resolve_distribution_context(
    catalog_target: str | None = None,
    distribution_dir: str | Path | None = None,
) -> tuple[list[dict] | None, str, str, Path | None, set[str] | None, str | None]:
    return get_deployed_catalog(catalog_target=catalog_target, distribution_dir=distribution_dir)


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


def check_consistency(
    check_pending: bool = False,
    full_audit: bool = True,
    catalog_target: str | None = None,
    distribution_dir: str | Path | None = None,
) -> int:
    upstream_head = get_upstream_head()
    sync_head = get_sync_branch_head()
    sync_state = get_sync_state()
    synced_state_sha = sync_state.get("upstream_sha", "NONE")
    main_head = git("rev-parse", "HEAD")

    local_modules = get_local_source_modules()
    res_ctx = resolve_distribution_context(
        catalog_target=catalog_target,
        distribution_dir=distribution_dir,
    )
    resolved_target_url = None
    if len(res_ctx) >= 6:
        deployed_items, catalog_source, catalog_status, local_apk_dir, remote_apks, target = res_ctx[:6]
        if target and (target.startswith("http://") or target.startswith("https://")):
            resolved_target_url = target
    elif len(res_ctx) >= 5:
        deployed_items, catalog_source, catalog_status, local_apk_dir, remote_apks = res_ctx[:5]
    else:
        deployed_items, catalog_source, catalog_status = res_ctx[:3]
        local_apk_dir, remote_apks = None, None
    catalog_verified = (deployed_items is not None and catalog_status != "NONE")
    deployed_items = deployed_items or []

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
    base = sync_mod.git("merge-base", "HEAD", upstream_ref).strip() if sync_mod else ""
    upstream_head_sha = sync_mod.git("rev-parse", upstream_ref).strip() if sync_mod else ""
    upstream_has_unmerged = bool(upstream_head_sha and base != upstream_head_sha)
    unmerged_units = set()
    renamed_units = set()
    if upstream_has_unmerged and sync_mod:
        upstream_diff_entries = sync_mod.changed_entries(base, upstream_ref)
        unmerged_units, _ = sync_mod.collect_units(upstream_diff_entries)
        for status, paths in upstream_diff_entries:
            if status.startswith("R") and len(paths) >= 2:
                u_old = sync_mod.sync_unit(paths[0])
                u_new = sync_mod.sync_unit(paths[1])
                if u_old:
                    renamed_units.add(u_old)
                if u_new:
                    renamed_units.add(u_new)

    audit_exceptions = set()
    if AUDIT_EXCEPTIONS_FILE.exists():
        try:
            exc_data = json.loads(AUDIT_EXCEPTIONS_FILE.read_text("utf-8"))
            if isinstance(exc_data, list):
                audit_exceptions = set(exc_data)
        except Exception:
            pass

    has_inconclusive_artifacts = False

    # When Git Tree API is unavailable or APKs are missing from the Git tree, verify needed APKs
    # concurrently with bounded worker concurrency (Codex Findings P1 & P2)
    remote_probed_map: dict[str, str] = {}
    if catalog_verified and local_apk_dir is None:
        target_url = resolved_target_url or catalog_target or DISTRIBUTION_INDEX_URL
        if target_url.startswith("http://") or target_url.startswith("https://"):
            all_deployed_apks = list({
                item.get("apk") for item in deployed_items
                if item.get("apk")
            })
            if remote_apks is not None:
                valid_apks = remote_apks[0] if isinstance(remote_apks, tuple) else remote_apks
                empty_apks = remote_apks[1] if isinstance(remote_apks, tuple) else set()
                apks_to_probe = [a for a in all_deployed_apks if a not in valid_apks and a not in empty_apks]
            else:
                apks_to_probe = all_deployed_apks

            if apks_to_probe:
                remote_probed_map = check_remote_apks_concurrent(target_url, apks_to_probe)
                # Upgrade status if full probe conclusively reached the server (Codex Finding P2)
                if catalog_status == "UNVERIFIED_ARTIFACTS" and any(st in ("EXISTS", "MISSING") for st in remote_probed_map.values()):
                    catalog_status = "VERIFIED"

    def is_publish_pending(unit: str) -> bool:
        nonlocal has_inconclusive_artifacts
        if not catalog_verified:
            return False
        if unit not in deployed_by_unit:
            return True
        dep = deployed_by_unit[unit]
        # Check version code monotonicity if available
        if sync_mod:
            eff = sync_mod.effective_version(None, unit)
            if eff is not None:
                local_eff_code = eff[2]
                deployed_code = dep.get("code", 0)
                if local_eff_code > deployed_code:
                    return True
        # Check APK artifact existence:
        apk_name = dep.get("apk")
        if not apk_name:
            return True
        if local_apk_dir is not None:
            if not local_apk_dir.is_dir():
                return True
            apk_file = local_apk_dir / apk_name
            if not apk_file.is_file() or apk_file.stat().st_size == 0:
                return True
        elif remote_apks is not None:
            if isinstance(remote_apks, tuple):
                valid_apks, empty_apks = remote_apks
                if apk_name in empty_apks:
                    return True
            else:
                valid_apks = remote_apks
            if apk_name not in valid_apks:
                # Concurrent probe result for APKs missing from Git tree (Codex Finding P1)
                apk_status = remote_probed_map.get(apk_name, "ERROR")
                if apk_status == "MISSING":
                    return True
                elif apk_status == "ERROR":
                    has_inconclusive_artifacts = True
                    return False
        elif local_apk_dir is None:
            # Custom URL target without Git Tree API (Codex Findings P1 & P2)
            apk_status = remote_probed_map.get(apk_name, "ERROR")
            if apk_status == "MISSING":
                return True
            elif apk_status == "ERROR":
                has_inconclusive_artifacts = True
                return False
        return False

    for u in all_audited_units:
        if u in audit_exceptions:
            parity_by_status["EXCEPTION"].append(u)
        elif u in renamed_units:
            parity_by_status["RENAMED"].append(u)
        elif u.startswith("src/pt/") or (sync_mod and sync_mod.is_ptbr_unit(u)):
            if u in local_modules and is_publish_pending(u):
                parity_by_status["PUBLISH_PENDING"].append(u)
            else:
                parity_by_status["PROTECTED_PTBR"].append(u)
        elif u in upstream_sources and u not in local_modules:
            parity_by_status["MISSING"].append(u)
        elif u in local_modules and u not in upstream_sources:
            if sync_mod and sync_mod.is_nox_exclusive(u, upstream_ref, base=base):
                if u in local_modules and is_publish_pending(u):
                    parity_by_status["PUBLISH_PENDING"].append(u)
                else:
                    parity_by_status["PROTECTED_PTBR"].append(u)
            else:
                parity_by_status["STALE"].append(u)
        elif u in unmerged_units:
            parity_by_status["OUTDATED"].append(u)
        elif u in local_modules and is_publish_pending(u):
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
        f"| **Catalog Verification Source** | {catalog_source} | {catalog_status} |",
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
    if parity_by_status["PUBLISH_PENDING"]:
        sample = parity_by_status["PUBLISH_PENDING"][:20]
        extra = f" and {len(parity_by_status['PUBLISH_PENDING']) - 20} more..." if len(parity_by_status["PUBLISH_PENDING"]) > 20 else ""
        lines.append(f"**Pending Publication Units ({len(parity_by_status['PUBLISH_PENDING'])}):** `{', '.join(sample)}`{extra}\n")

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
        # Upstream divergence or pending source changes independently prove sync is needed
        if (
            pending_units
            or (upstream_head != sync_head and upstream_head != "UNKNOWN")
            or parity_by_status["MISSING"]
            or parity_by_status["STALE"]
            or parity_by_status["OUTDATED"]
            or parity_by_status["RENAMED"]
        ):
            return 2

        # If catalog cannot be verified across all prioritized tiers, fail controlled (exit 1)
        # to prevent false confirmation of synchronization from absence of data (Section 5)
        if not catalog_verified:
            print("::error::Catalog integrity unverified. No reliable catalog source available.", file=sys.stderr)
            return 1

        # Pending publication of local source modules triggers exit code 2
        if parity_by_status["PUBLISH_PENDING"]:
            return 2

        # If catalog was fetched but distribution artifacts could not be confirmed,
        # fail controlled (exit 1) to prevent false confirmation of sync (Section 5)
        if catalog_status == "UNVERIFIED_ARTIFACTS":
            print("::error::Catalog verified but distribution artifacts could not be independently confirmed.", file=sys.stderr)
            return 1

        # If any artifact check was inconclusive due to network errors, fail controlled (exit 1)
        # to prevent false confirmation of sync (Section 5)
        if has_inconclusive_artifacts:
            print("::error::One or more artifact verification checks were inconclusive due to network errors.", file=sys.stderr)
            return 1

        return 0

    return 0



def main() -> None:
    parser = argparse.ArgumentParser(description="Check Project Nox consistency")
    parser.add_argument("--check-pending", action="store_true", help="Exit with code 2 if sync is needed")
    parser.add_argument("--catalog-file", type=str, default=None, help="Path to local catalog index.min.json")
    parser.add_argument("--distribution-dir", type=str, default=None, help="Path to local distribution repo directory")
    args = parser.parse_args()
    sys.exit(check_consistency(
        check_pending=args.check_pending,
        catalog_target=args.catalog_file,
        distribution_dir=args.distribution_dir,
    ))


if __name__ == "__main__":
    main()
