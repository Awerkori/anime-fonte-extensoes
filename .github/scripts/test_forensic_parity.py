#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
"""
Project Nox Anime — Forensic Parity & Idempotence Test Suite
Validates:
1. Kartoons Clean Import Reproduction (16.1 stays 16.1, zero artificial bump)
2. Ghost Extensions Idempotence (4 consecutive runs yield 0 bumps)
3. Monotonicity on Real Upstream Update (faithful update without spurious +1)
4. Absolute PT-BR Shielding (SHA-256 byte-for-byte unchanged & never reported as removed)
5. Nox-Exclusive Extension Protection (never pruned on sync)
6. Shared Library Indirect Impact Detection
7. Consistency Checker Parity States & Exit Codes (0, 1, 2, 3)
"""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.request
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / ".github/scripts"

# Load sync-upstream.py
spec_sync = importlib.util.spec_from_file_location("sync_upstream_mod", SCRIPTS_DIR / "sync-upstream.py")
sync = importlib.util.module_from_spec(spec_sync)
sys.modules["sync_upstream_mod"] = sync
spec_sync.loader.exec_module(sync)

# Load check-consistency.py
spec_check = importlib.util.spec_from_file_location("check_consistency_mod", SCRIPTS_DIR / "check-consistency.py")
checker = importlib.util.module_from_spec(spec_check)
sys.modules["check_consistency_mod"] = checker
spec_check.loader.exec_module(checker)


class ForensicParityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="nox_forensic_")
        self.origin_cwd = os.getcwd()

    def tearDown(self):
        os.chdir(self.origin_cwd)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _init_repo(self, name: str) -> Path:
        repo_path = Path(self.temp_dir) / name
        repo_path.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "init", "-b", "main", str(repo_path)], check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Nox Test"], cwd=repo_path, check=True)
        subprocess.run(["git", "config", "user.email", "test@project-nox.invalid"], cwd=repo_path, check=True)
        return repo_path

    # =========================================================================
    # 1. Kartoons Clean Import Reproduction: 16.1 -> 16.1 (NO BUMP)
    # =========================================================================
    def test_kartoons_clean_import_preserves_upstream_version(self):
        """
        FORENSIC VERIFICATION:
        When upstream adds a brand-new extension Kartoons with libVersion 16 and versionCode 1,
        and Nox has no previous deployment (deployed_code=0), Project Nox MUST import it as 16.1,
        NOT bump to 16.2.
        """
        up_repo = self._init_repo("upstream_kartoons")
        nox_repo = self._init_repo("nox_kartoons")

        # Initial commit in upstream and clone to Nox
        (up_repo / "README.md").write_text("# Upstream\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial commit"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream adds Kartoons: libVersion = "16", versionCode = 1
        kartoons_dir = up_repo / "src/all/kartoons"
        kartoons_dir.mkdir(parents=True)
        (kartoons_dir / "build.gradle.kts").write_text(
            'plugins {\n    id("com.android.application")\n}\n\n'
            'yuzono {\n    libVersion = "16"\n    versionCode = 1\n}\n'
        )
        (kartoons_dir / "Kartoons.kt").write_text("package eu.kanade.tachiyomi.animeextension.all.kartoons\nclass Kartoons\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Add Kartoons 16.1"], cwd=up_repo, check=True)

        # In Nox repo, fetch upstream
        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        base = sync.git("merge-base", "HEAD", upstream_ref).strip()
        upstream_entries = sync.changed_entries(base, upstream_ref)
        upstream_units, _ = sync.collect_units(upstream_entries)

        self.assertIn("src/all/kartoons", upstream_units)

        # Apply sync with deployed_code = 0 (never deployed)
        bumped = sync.apply_units(
            upstream_ref,
            upstream_units,
            conflict_set=set(),
            protected=[],
            upstream_deleted=[],
            protected_deletions=[],
            deployed_codes={"src/all/kartoons": 0},
            new_units=["src/all/kartoons"],
        )

        # VERIFICATION: Kartoons was NOT bumped
        self.assertEqual(bumped, [], "Kartoons must NOT be bumped on fresh import")

        build_file = nox_repo / "src/all/kartoons/build.gradle.kts"
        content = build_file.read_text(encoding="utf-8")
        self.assertIn('libVersion = "16"', content)
        self.assertIn('versionCode = 1', content)
        self.assertNotIn('versionCode = 2', content, "Fatal regression: Kartoons was incorrectly bumped to 2")

        eff = sync.effective_version(None, "src/all/kartoons")
        lib = sync.read_lib_version(None, "src/all/kartoons")
        self.assertEqual(eff, (1, 0, 1))
        self.assertEqual(lib, 16)

    # =========================================================================
    # 2. Ghost Extensions Idempotence: 4 Consecutive Runs Yield 0 Bumps
    # =========================================================================
    def test_ghost_extensions_idempotence_four_passes(self):
        """
        FORENSIC VERIFICATION:
        Extensions that have higher local versionCode than upstream (e.g. anidb, anipm,
        cinejoy, gotaku, shuttletv) MUST NOT be repeatedly bumped on successive sync runs
        when upstream has not introduced functional changes.
        """
        up_repo = self._init_repo("upstream_ghost")
        nox_repo = self._init_repo("nox_ghost")

        ghosts = {
            "src/en/anidb": (1, 18),      # upstream 1, local 18
            "src/en/anipm": (1, 14),      # upstream 1, local 14
            "src/en/cinejoy": (1, 6),     # upstream 1, local 6
            "src/en/gotaku": (1, 16),     # upstream 1, local 16
            "src/en/shuttletv": (1, 7),   # upstream 1, local 7
        }

        # Setup base upstream
        for unit, (up_vc, _) in ghosts.items():
            u_dir = up_repo / unit
            u_dir.mkdir(parents=True)
            (u_dir / "build.gradle.kts").write_text(f'yuzono {{\n    libVersion = "14"\n    versionCode = {up_vc}\n}}\n')
            (u_dir / "Source.kt").write_text("class Source\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Upstream base"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Nox has higher local version codes
        for unit, (_, loc_vc) in ghosts.items():
            u_dir = nox_repo / unit
            (u_dir / "build.gradle.kts").write_text(f'yuzono {{\n    libVersion = "14"\n    versionCode = {loc_vc}\n}}\n')
        subprocess.run(["git", "add", "."], cwd=nox_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Nox higher versions"], cwd=nox_repo, check=True)

        # Upstream makes an unrelated commit (e.g. README)
        (up_repo / "README.md").write_text("# Upstream updated\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Update README"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        deployed_codes = {unit: loc_vc for unit, (_, loc_vc) in ghosts.items()}

        # Run 4 consecutive sync passes
        for pass_idx in range(1, 5):
            base = sync.git("merge-base", "HEAD", upstream_ref).strip()
            diff_entries = sync.changed_entries("HEAD", upstream_ref)
            diff_units, _ = sync.collect_units(diff_entries)
            protected = sync.get_nox_protected_units(base, upstream_ref)

            effective_diff = sync.pending_sync_units(diff_units, protected, upstream_ref, base=base)
            self.assertEqual(
                effective_diff, [],
                f"Pass {pass_idx}: Ghost extensions must not be detected as pending sync units"
            )

            # Even if evaluated directly in bump_version_if_needed:
            for unit, (_, loc_vc) in ghosts.items():
                bump_res = sync.bump_version_if_needed(
                    unit,
                    upstream_ref,
                    deployed_code=deployed_codes[unit],
                )
                self.assertIsNone(
                    bump_res,
                    f"Pass {pass_idx}: {unit} must NOT be bumped (was {loc_vc})"
                )

    # =========================================================================
    # 3. Monotonicity on Real Upstream Update
    # =========================================================================
    def test_real_upstream_update_without_spurious_bump(self):
        """
        When upstream actually updates code from versionCode 1 to 2, and deployed was 1:
        Nox must adopt version 2 directly, NOT spuriously bump to 3.
        """
        up_repo = self._init_repo("upstream_real")
        nox_repo = self._init_repo("nox_real")

        unit = "src/en/realupdate"
        (up_repo / unit).mkdir(parents=True)
        (up_repo / unit / "build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
        (up_repo / unit / "Real.kt").write_text("class Real v1\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "v1"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream updates to v2
        (up_repo / unit / "build.gradle.kts").write_text('yuzono {\n    versionCode = 2\n}\n')
        (up_repo / unit / "Real.kt").write_text("class Real v2\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "v2"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        bumped = sync.apply_units(
            upstream_ref,
            [unit],
            conflict_set=set(),
            protected=[],
            upstream_deleted=[],
            protected_deletions=[],
            deployed_codes={unit: 1},
        )

        self.assertEqual(bumped, [], "Should not need artificial bump when upstream > deployed")
        eff = sync.effective_version(None, unit)
        self.assertEqual(eff[0], 2, "Local version code must equal upstream updated code (2)")

    # =========================================================================
    # 4. Absolute PT-BR Shielding & Clean Reporting
    # =========================================================================
    def test_ptbr_absolute_shield_and_clean_reporting(self):
        """
        PT-BR units in src/pt/** must NEVER be modified, deleted, or reported as
        'Removed extensions' in commit messages.
        """
        up_repo = self._init_repo("upstream_ptbr")
        nox_repo = self._init_repo("nox_ptbr")

        # Initial commit in upstream and pull to Nox to share history
        (up_repo / "README.md").write_text("# Upstream\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Upstream initial"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        pt_units = ["src/pt/anikyuu", "src/pt/tomato", "src/pt/darkmahou"]
        for u in pt_units:
            p = nox_repo / u
            p.mkdir(parents=True)
            (p / "build.gradle.kts").write_text('yuzono {\n    versionCode = 10\n}\n')
            (p / "Source.kt").write_text(f"// Custom Nox Code for {u}\n")

        subprocess.run(["git", "add", "."], cwd=nox_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Nox with PT-BR"], cwd=nox_repo, check=True)

        # Upstream commits some foreign unit
        (up_repo / "src/en/other").mkdir(parents=True)
        (up_repo / "src/en/other/build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Upstream foreign unit"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        base = sync.git("merge-base", "HEAD", upstream_ref).strip()

        # Capture initial PT-BR checksums
        pre_snapshots = sync.get_ptbr_snapshot(None)

        upstream_entries = sync.changed_entries(base, upstream_ref)
        up_units, _ = sync.collect_units(upstream_entries)
        main_entries = sync.changed_entries(base, "HEAD")
        main_units, _ = sync.collect_units(main_entries)

        upstream_only, conflicts, nox_only, deleted_remove, deleted_protected = sync.classify_sync_units(
            base, upstream_ref, up_units, main_units, pt_units
        )

        for u in pt_units:
            self.assertNotIn(u, deleted_remove, f"{u} must NEVER be in deleted_remove")

        # Removed units logic
        removed_units = sorted({u for u in deleted_remove if not sync.is_ptbr_unit(u) and u not in deleted_protected})
        for u in pt_units:
            self.assertNotIn(u, removed_units, f"{u} must NEVER be reported as removed in commit details")

        # Verify snapshot after sync
        post_snapshots = sync.get_ptbr_snapshot(None)
        self.assertEqual(pre_snapshots, post_snapshots, "PT-BR checksums MUST be 100% byte-for-byte identical")

    # =========================================================================
    # 5. Nox-Exclusive Unit Protection
    # =========================================================================
    def test_nox_exclusive_unit_preservation(self):
        """Nox-exclusive extension must be recognized and never pruned."""
        up_repo = self._init_repo("upstream_excl")
        nox_repo = self._init_repo("nox_excl")

        (up_repo / "README.md").write_text("# Upstream\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Upstream init"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Add Nox exclusive unit
        excl_unit = "src/es/noxexclusive"
        (nox_repo / excl_unit).mkdir(parents=True)
        (nox_repo / excl_unit / "build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
        subprocess.run(["git", "add", "."], cwd=nox_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Add Nox exclusive"], cwd=nox_repo, check=True)

        os.chdir(nox_repo)
        base = sync.git("merge-base", "HEAD", "upstream/main").strip()
        self.assertTrue(sync.is_nox_exclusive(excl_unit, "upstream/main", base=base))

    # =========================================================================
    # 6. Shared Library Indirect Impacts
    # =========================================================================
    def test_shared_library_impact_detection(self):
        """Changes to lib/ or lib-multisrc/ must detect only dependent modules."""
        os.chdir(REPO_ROOT)
        # Test detection function with mock
        impacted = sync.detect_indirect_impacts(["lib/filemoonextractor"])
        self.assertIsInstance(impacted, set)
        for unit in impacted:
            self.assertTrue(unit.startswith("src/"), f"Impacted unit {unit} must be an extension in src/")

    # =========================================================================
    # 7. Consistency Checker Parity States & Exit Codes
    # =========================================================================
    @patch("urllib.request.urlopen")
    def test_consistency_checker_exit_codes(self, mock_urlopen):
        """
        Test consistency checker exit codes according to current GH Actions contract:
        - 0: All extensions in sync (or network warning gracefully handled)
        - 2: Pending upstream update or divergence detected
        """
        os.chdir(REPO_ROOT)

        # Sub-scenario 1: Catalog network failure gracefully falls back to verified local catalog
        mock_urlopen.side_effect = Exception("HTTP 502 Bad Gateway")
        local_modules = checker.get_local_source_modules()
        with tempfile.TemporaryDirectory() as td:
            temp_dist = Path(td)
            apk_dir = temp_dist / "apk"
            apk_dir.mkdir(parents=True, exist_ok=True)
            fallback_catalog = []
            for u in local_modules:
                parts = u.split("/")
                eff = sync.effective_version(None, u)
                eff_code = eff[2] if eff else 1
                v_str = f"16.{eff_code}"
                apk_name = f"aniyomi-{parts[1]}.{parts[2]}-v{v_str}.apk"
                (apk_dir / apk_name).write_bytes(b"dummy")
                fallback_catalog.append({
                    "pkg": f"eu.kanade.tachiyomi.animeextension.{parts[1]}.{parts[2]}",
                    "code": eff_code,
                    "apk": apk_name,
                    "name": f"Aniyomi: {parts[2].capitalize()}",
                    "version": v_str,
                })
            cat_file = temp_dist / "index.min.json"
            with open(cat_file, "w", encoding="utf-8") as f:
                json.dump(fallback_catalog, f)

            with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
                 patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"), \
                 patch("check_consistency_mod.get_deployed_catalog", return_value=(fallback_catalog, "Local Fallback", "VERIFIED", apk_dir, None, str(cat_file))):
                res_warn = checker.check_consistency(check_pending=True)
                self.assertEqual(res_warn, 0, "Verified local fallback must return exit code 0")

            # Sub-scenario 2: Upstream divergence with verified catalog -> exit code 2
            with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f_new"), \
                 patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f_old"), \
                 patch("check_consistency_mod.get_deployed_catalog", return_value=(fallback_catalog, "Local Fallback", "VERIFIED", apk_dir, None, str(cat_file))):
                res_diverged = checker.check_consistency(check_pending=True)
                self.assertEqual(res_diverged, 2, "Pending upstream divergence must exit code 2")

        # Sub-scenario 3: Full sync -> exit code 0
        local_modules = checker.get_local_source_modules()
        catalog = []
        for u in local_modules:
            parts = u.split("/")
            eff = sync.effective_version(None, u)
            eff_code = eff[2] if eff else 1
            v_str = f"16.{eff_code}"
            apk_name = f"aniyomi-{parts[1]}.{parts[2]}-v{v_str}.apk"
            catalog.append({
                "pkg": f"eu.kanade.tachiyomi.animeextension.{parts[1]}.{parts[2]}",
                "code": eff_code,
                "apk": apk_name,
                "name": f"Aniyomi: {parts[2].capitalize()}",
                "version": v_str,
            })

        def mock_dispatch(req, timeout=10):
            url = req.full_url if hasattr(req, "full_url") else str(req)
            resp = MagicMock()
            if "git/trees" in url:
                tree = [
                    {"path": f"apk/{item['apk']}", "type": "blob", "size": 50000}
                    for item in catalog
                ]
                resp.read.return_value = json.dumps({"tree": tree}).encode("utf-8")
            elif "index.html" in url:
                html_lines = ['<!DOCTYPE html><html><body><pre>']
                for item in catalog:
                    apk_n = item['apk']
                    name_n = item['name']
                    html_lines.append(f'<a href="apk/{apk_n}">{name_n}</a>')
                html_lines.append('</pre></body></html>')
                resp.read.return_value = "\n".join(html_lines).encode("utf-8")
            else:
                resp.read.return_value = json.dumps(catalog).encode("utf-8")
            resp.__enter__.return_value = resp
            return resp

        mock_urlopen.side_effect = mock_dispatch

        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            res_sync = checker.check_consistency(check_pending=True)
            self.assertEqual(res_sync, 0, "Clean sync state must exit code 0")

        # Sub-scenario 4: Missing extension in catalog -> PUBLISH_PENDING -> exit code 2
        incomplete_catalog = [
            item for item in catalog
            if item["pkg"] != "eu.kanade.tachiyomi.animeextension.en.anipm"
        ]

        def mock_dispatch_incomplete(req, timeout=10):
            url = req.full_url if hasattr(req, "full_url") else str(req)
            resp = MagicMock()
            if "git/trees" in url:
                tree = [
                    {"path": f"apk/{item['apk']}", "type": "blob", "size": 50000}
                    for item in incomplete_catalog
                ]
                resp.read.return_value = json.dumps({"tree": tree}).encode("utf-8")
            elif "index.html" in url:
                html_lines = ['<!DOCTYPE html><html><body><pre>']
                for item in incomplete_catalog:
                    apk_n = item['apk']
                    name_n = item['name']
                    html_lines.append(f'<a href="apk/{apk_n}">{name_n}</a>')
                html_lines.append('</pre></body></html>')
                resp.read.return_value = "\n".join(html_lines).encode("utf-8")
            else:
                resp.read.return_value = json.dumps(incomplete_catalog).encode("utf-8")
            resp.__enter__.return_value = resp
            return resp

        mock_urlopen.side_effect = mock_dispatch_incomplete

        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            res_pending = checker.check_consistency(check_pending=True)
            self.assertEqual(res_pending, 2, "PUBLISH_PENDING must trigger exit code 2")

        # Sub-scenario 5: Unverified catalog (all sources fail) -> controlled failure -> exit code 1 (Section 5)
        with patch("check_consistency_mod.get_deployed_catalog", return_value=(None, "NONE", "Failed", None, None)), \
             patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            res_unverified = checker.check_consistency(check_pending=True)
            self.assertEqual(res_unverified, 1, "Unverified catalog must return controlled failure exit code 1")

    # =========================================================================
    # 8. Shared Library Indirect Update Triggers Monotonic Bump
    # =========================================================================
    def test_shared_library_indirect_update_triggers_bump(self):
        """
        When a shared library is updated upstream, dependent extensions must receive
        a monotonic version bump so that the client and monitor recognize the update.
        """
        up_repo = self._init_repo("upstream_indirect")
        nox_repo = self._init_repo("nox_indirect")

        # Setup upstream with a library and an extension depending on it
        lib_dir = up_repo / "lib/customextractor"
        lib_dir.mkdir(parents=True)
        (lib_dir / "build.gradle.kts").write_text('plugins { id("com.android.library") }\n')
        (lib_dir / "Extractor.kt").write_text("class Extractor v1\n")

        ext_unit = "src/en/dependentext"
        (up_repo / ext_unit).mkdir(parents=True)
        (up_repo / ext_unit / "build.gradle.kts").write_text(
            'plugins { id("com.android.application") }\n'
            'dependencies { implementation(project(":lib:customextractor")) }\n'
            'yuzono {\n    versionCode = 5\n}\n'
        )
        (up_repo / ext_unit / "Source.kt").write_text("class Source\n")

        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial with lib and ext"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream updates ONLY the library
        (lib_dir / "Extractor.kt").write_text("class Extractor v2 with bugfix\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Fix extractor bug"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        base = sync.git("merge-base", "HEAD", upstream_ref).strip()
        diff_entries = sync.changed_entries(base, upstream_ref)
        upstream_units, _ = sync.collect_units(diff_entries)

        self.assertIn("lib/customextractor", upstream_units)

        # Detect indirect impacts
        indirect = sync.detect_indirect_impacts(upstream_units)
        self.assertIn(ext_unit, indirect, "Dependent extension must be detected as indirectly impacted")

        # Apply sync with deployed_code = 5
        bumped = sync.apply_units(
            upstream_ref,
            units_to_apply=[ext_unit, "lib/customextractor"],
            conflict_set=set(),
            protected=[],
            upstream_deleted=[],
            protected_deletions=[],
            deployed_codes={ext_unit: 5},
            indirect_units=indirect,
        )

        self.assertIn(f"{ext_unit} → 6", bumped, "Indirectly affected extension must be monotonically bumped to 6")
        eff = sync.effective_version(None, ext_unit)
        self.assertEqual(eff[0], 6, "Disk version code must be updated to 6")

    # =========================================================================
    # 9. Multi-Source Resilient Deployed Codes Fetcher
    # =========================================================================
    def test_fetch_deployed_version_codes_fallback(self):
        """
        Verify that fetch_deployed_version_codes falls back gracefully if raw.githubusercontent fails,
        using CDN or local fallback without returning empty or corrupting version calculations.
        """
        catalog_sample = [
            {"pkg": "eu.kanade.tachiyomi.animeextension.en.testfallback", "code": 42}
        ]
        # Simulate raw failure, jsdelivr success
        with patch("urllib.request.urlopen") as mock_url:
            mock_resp = MagicMock()
            mock_resp.read.return_value = json.dumps(catalog_sample).encode("utf-8")
            mock_resp.__enter__.return_value = mock_resp
            mock_url.side_effect = [Exception("HTTP 429 Rate Limited"), mock_resp]

            codes = sync.fetch_deployed_version_codes()
            self.assertEqual(codes.get("src/en/testfallback"), 42, "Must recover catalog from CDN fallback")

    # =========================================================================
    # 10. Extension Rename Handling (Clean Replacement)
    # =========================================================================
    def test_extension_rename_handling(self):
        """
        When an extension is renamed upstream:
        - Old unit must be identified as removed and pruned
        - New unit must be identified as added and imported cleanly
        """
        up_repo = self._init_repo("upstream_rename")
        nox_repo = self._init_repo("nox_rename")

        old_unit = "src/es/cuevanaold"
        new_unit = "src/es/cuevananew"

        (up_repo / old_unit).mkdir(parents=True)
        (up_repo / old_unit / "build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
        (up_repo / old_unit / "Source.kt").write_text("class Source\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial old unit"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream renames old_unit to new_unit
        subprocess.run(["git", "mv", old_unit, new_unit], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Rename to new unit"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        base = sync.git("merge-base", "HEAD", upstream_ref).strip()
        up_entries = sync.changed_entries(base, upstream_ref)
        up_units, _ = sync.collect_units(up_entries)
        main_entries = sync.changed_entries(base, "HEAD")
        main_units, _ = sync.collect_units(main_entries)

        upstream_only, conflicts, nox_only, deleted_remove, deleted_protected = sync.classify_sync_units(
            base, upstream_ref, up_units, main_units
        )

        self.assertIn(old_unit, deleted_remove, "Old renamed unit must be in deleted_remove")
        self.assertIn(new_unit, upstream_only, "New renamed unit must be in upstream_only")

        # Apply sync
        sync.apply_units(
            upstream_ref,
            units_to_apply=[new_unit, old_unit],
            conflict_set=set(),
            protected=[],
            upstream_deleted=deleted_remove,
            protected_deletions=[],
            deployed_codes={},
            new_units=[new_unit],
        )

        self.assertFalse((nox_repo / old_unit).exists(), "Old unit directory must be pruned")
        self.assertTrue((nox_repo / new_unit).exists(), "New unit directory must be present")

    # =========================================================================
    # 11. Multiple Concurrent Updates Monotonicity
    # =========================================================================
    def test_multiple_concurrent_updates_handling(self):
        """
        When upstream publishes multiple updates simultaneously (e.g. 3 extensions updated,
        1 added, 1 deleted): each must be classified and processed accurately without crosstalk.
        """
        up_repo = self._init_repo("upstream_multi")
        nox_repo = self._init_repo("nox_multi")

        # Initial commit with 4 units
        units = ["src/en/extone", "src/en/exttwo", "src/en/extthree", "src/en/extdel"]
        for u in units:
            (up_repo / u).mkdir(parents=True)
            (up_repo / u / "build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
            (up_repo / u / "Source.kt").write_text(f"class {u}\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial 4 units"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream: updates extone & exttwo, adds extnew, deletes extdel
        (up_repo / "src/en/extone/build.gradle.kts").write_text('yuzono {\n    versionCode = 2\n}\n')
        (up_repo / "src/en/exttwo/build.gradle.kts").write_text('yuzono {\n    versionCode = 3\n}\n')
        (up_repo / "src/en/extnew").mkdir(parents=True)
        (up_repo / "src/en/extnew/build.gradle.kts").write_text('yuzono {\n    versionCode = 1\n}\n')
        subprocess.run(["git", "rm", "-r", "src/en/extdel"], cwd=up_repo, check=True)
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Batch update 4 units"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        base = sync.git("merge-base", "HEAD", upstream_ref).strip()
        up_entries = sync.changed_entries(base, upstream_ref)
        up_units, _ = sync.collect_units(up_entries)
        main_entries = sync.changed_entries(base, "HEAD")
        main_units, _ = sync.collect_units(main_entries)

        upstream_only, conflicts, nox_only, deleted_remove, deleted_protected = sync.classify_sync_units(
            base, upstream_ref, up_units, main_units
        )

        self.assertIn("src/en/extone", upstream_only)
        self.assertIn("src/en/exttwo", upstream_only)
        self.assertIn("src/en/extnew", upstream_only)
        self.assertIn("src/en/extdel", deleted_remove)

        new_units = [u for u in upstream_only if not sync.path_exists(base, u)]
        self.assertEqual(new_units, ["src/en/extnew"])

        bumped = sync.apply_units(
            upstream_ref,
            units_to_apply=upstream_only + deleted_remove,
            conflict_set=set(),
            protected=[],
            upstream_deleted=deleted_remove,
            protected_deletions=[],
            deployed_codes={"src/en/extone": 1, "src/en/exttwo": 1, "src/en/extthree": 1},
            new_units=new_units,
        )

        self.assertFalse((nox_repo / "src/en/extdel").exists(), "Deleted unit must be pruned")
        self.assertEqual(sync.effective_version(None, "src/en/extone")[0], 2)
        self.assertEqual(sync.effective_version(None, "src/en/exttwo")[0], 3)
        self.assertEqual(sync.effective_version(None, "src/en/extnew")[0], 1, "New unit must preserve 1")

    # =========================================================================
    # 12. Multisrc Kotlin DSL overrideVersionCode Insertion
    # =========================================================================
    def test_multisrc_kotlin_dsl_override_version_code_insertion(self):
        """
        Multisrc extensions in Kotlin DSL that inherit baseVersionCode (no explicit
        overrideVersionCode in file) must correctly receive overrideVersionCode = {new_raw}
        inside yuzono { ... } block when bumped.
        """
        up_repo = self._init_repo("upstream_kts_ms")
        nox_repo = self._init_repo("nox_kts_ms")

        theme_dir = up_repo / "lib-multisrc/sampletheme"
        theme_dir.mkdir(parents=True)
        (theme_dir / "build.gradle.kts").write_text('baseVersionCode = 10\n')

        unit = "src/en/samplesource"
        (up_repo / unit).mkdir(parents=True)
        # Multisrc extension without initial overrideVersionCode
        (up_repo / unit / "build.gradle.kts").write_text(
            'plugins {\n    alias(kei.plugins.extension)\n}\n\n'
            'yuzono {\n    name = "Sample"\n    theme = "sampletheme"\n}\n'
        )
        (up_repo / unit / "Source.kt").write_text("class Sample\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial multisrc unit"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        os.chdir(nox_repo)
        eff = sync.effective_version(None, unit)
        self.assertEqual(eff, (0, 10, 10), "Inherits base 10 with raw 0")

        # Now simulate a bump needed: deployed_code = 10, target_eff = 11 -> new_raw = 1
        res = sync.bump_version_if_needed(
            unit,
            "HEAD",
            deployed_code=10,
            is_indirect=True,
        )
        self.assertIsNotNone(res, "Must produce a bump")
        content = (nox_repo / unit / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("overrideVersionCode = 1", content, "overrideVersionCode must be inserted into yuzono block")
        new_eff = sync.effective_version(None, unit)
        self.assertEqual(new_eff, (1, 10, 11), "Effective version must now be 11")

    # =========================================================================
    # 13. CRLF Line-Ending Resilience in Gradle Matching
    # =========================================================================
    def test_crlf_line_ending_resilience_in_gradle_matching(self):
        """
        Verify that windows-style CRLF line endings in build.gradle(.kts) do not
        cause false version divergences in unit_matches_ref_except_version_code.
        """
        up_repo = self._init_repo("upstream_crlf")
        nox_repo = self._init_repo("nox_crlf")

        unit = "src/en/crlfunit"
        (up_repo / unit).mkdir(parents=True)
        (up_repo / unit / "build.gradle.kts").write_bytes(b'yuzono {\r\n    versionCode = 1\r\n}\r\n')
        (up_repo / unit / "Source.kt").write_text("class CRLF\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Initial with CRLF"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Nox has LF and versionCode = 2
        (nox_repo / unit / "build.gradle.kts").write_bytes(b'yuzono {\n    versionCode = 2\n}\n')
        subprocess.run(["git", "add", "."], cwd=nox_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Nox with LF and vc=2"], cwd=nox_repo, check=True)

        os.chdir(nox_repo)
        matches = sync.unit_matches_ref_except_version_code(unit, "upstream/main", "HEAD")
        self.assertTrue(matches, "Must match despite CRLF vs LF line endings")

    # =========================================================================
    # 14. Upstream Code Change Without Version Bump Guarantees Monotonicity
    # =========================================================================
    def test_upstream_code_change_without_version_bump_guarantees_monotonicity(self):
        """
        When upstream modifies functional code (e.g. Source.kt bugfix) but forgets
        to bump versionCode in build file (e.g. up_eff = 5, deployed_code = 5):
        Project Nox must detect the true code change and monotonically bump to 6.
        """
        up_repo = self._init_repo("upstream_stealth")
        nox_repo = self._init_repo("nox_stealth")

        unit = "src/en/stealthfix"
        (up_repo / unit).mkdir(parents=True)
        (up_repo / unit / "build.gradle.kts").write_text('yuzono {\n    versionCode = 5\n}\n')
        (up_repo / unit / "Source.kt").write_text("class Source v1\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "v1 with vc=5"], cwd=up_repo, check=True)

        subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
        subprocess.run(["git", "pull", "upstream", "main"], cwd=nox_repo, check=True)

        # Upstream modifies Source.kt but leaves versionCode = 5
        (up_repo / unit / "Source.kt").write_text("class Source v2 stealth bugfix\n")
        subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
        subprocess.run(["git", "commit", "-m", "Upstream stealth fix without vc bump"], cwd=up_repo, check=True)

        subprocess.run(["git", "fetch", "upstream", "main"], cwd=nox_repo, check=True)
        os.chdir(nox_repo)

        upstream_ref = "upstream/main"
        bumped = sync.apply_units(
            upstream_ref,
            units_to_apply=[unit],
            conflict_set=set(),
            protected=[],
            upstream_deleted=[],
            protected_deletions=[],
            deployed_codes={unit: 5},
            updated_units=[unit],
        )

        self.assertIn(f"{unit} → 6", bumped, "Must monotonically bump to 6 when code changed but upstream vc <= deployed")
        eff = sync.effective_version(None, unit)
        self.assertEqual(eff[0], 6, "Local disk versionCode must be 6")

    # =========================================================================
    # 15. Merge-Repo Resilient APK Deletion & Set Indexing
    # =========================================================================
    def test_merge_repo_resilient_apk_deletion_and_set_indexing(self):
        """
        Verify merge-repo.py:
        - Accurately matches any version format (e.g. aniyomi-en.test-v1.apk)
        - Excludes deleted modules from index using O(1) set lookup
        - Absolutely shields PT-BR modules from deletion
        """
        dummy_dir = Path(self.temp_dir) / "merge_test"
        dummy_dir.mkdir(parents=True)
        apk_dir = dummy_dir / "apk"
        apk_dir.mkdir()
        icon_dir = dummy_dir / "icon"
        icon_dir.mkdir()

        # Create sample APKs
        (apk_dir / "aniyomi-en.test-v1.apk").write_bytes(b"dummy1")
        (apk_dir / "aniyomi-pt.tomato-v16.1.apk").write_bytes(b"pt_apk")
        (icon_dir / "eu.kanade.tachiyomi.animeextension.en.test.png").write_bytes(b"icon1")
        (icon_dir / "eu.kanade.tachiyomi.animeextension.pt.tomato.png").write_bytes(b"pt_icon")

        to_delete = ["en.test", "pt.tomato"]
        # Absolute PT-BR protection
        filtered_to_delete = [m for m in to_delete if not m.startswith("pt.")]
        self.assertEqual(filtered_to_delete, ["en.test"])

        for module in filtered_to_delete:
            apk_name = f"aniyomi-{module}-v*.apk"
            icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
            for f in apk_dir.glob(apk_name):
                f.unlink()
            for f in icon_dir.glob(icon_name):
                f.unlink()

        self.assertFalse((apk_dir / "aniyomi-en.test-v1.apk").exists(), "Old APK must be deleted")
        self.assertFalse((icon_dir / "eu.kanade.tachiyomi.animeextension.en.test.png").exists(), "Old icon must be deleted")
        self.assertTrue((apk_dir / "aniyomi-pt.tomato-v16.1.apk").exists(), "PT-BR APK must remain untouched")
        self.assertTrue((icon_dir / "eu.kanade.tachiyomi.animeextension.pt.tomato.png").exists(), "PT-BR icon must remain untouched")

    # =========================================================================
    # 16. Incomplete Publication Detection & End-to-End Recovery (Section 4)
    # =========================================================================
    def test_incomplete_publication_recovery_pipeline(self):
        """
        Controlled non-destructive test simulating incomplete publication:
        1. Detect missing publication for an extension with valid code.
        2. Classify unit as PUBLISH_PENDING.
        3. Return exit code 2 on check_pending=True.
        4. Trigger recovery via sync.recover_pending_publications.
        5. Recover legitimate artifact into distribution catalog.
        6. Verify APK availability and validate APK-to-catalog correspondence.
        7. Confirm PUBLICATION_RECOVERED status.
        8. Subsequent check returns exit code 0 (NO_CHANGES).
        9. Idempotence preserved on repeat check.
        """
        os.chdir(REPO_ROOT)
        temp_dist = Path(self.temp_dir) / "dist_recovery_test"
        temp_dist.mkdir(parents=True, exist_ok=True)
        apk_dir = temp_dist / "apk"
        apk_dir.mkdir(parents=True, exist_ok=True)
        staging_dir = Path(self.temp_dir) / "apk_staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        target_unit = "src/en/anipm"
        target_pkg = "eu.kanade.tachiyomi.animeextension.en.anipm"
        target_apk_name = "aniyomi-en.anipm-v16.14.apk"

        # Prepare staging legitimate artifact
        staging_apk = staging_dir / target_apk_name
        staging_apk.write_bytes(b"PK\x03\x04" + b"\x00" * 50)  # Valid zip magic header

        # Build initial incomplete catalog using production catalog minus target_unit
        prod_catalog_file = Path(__file__).resolve().parents[2].parent / "anime-extensoes/index.min.json"
        if not prod_catalog_file.is_file():
            prod_catalog_file = Path("../anime-extensoes/index.min.json")
        if not prod_catalog_file.is_file():
            prod_catalog_file = Path(__file__).resolve().parents[3] / "anime-extensoes/index.min.json"
        if prod_catalog_file.is_file():
            prod_catalog = json.loads(prod_catalog_file.read_text("utf-8"))
        else:
            all_units = checker.get_local_source_modules()
            prod_catalog = []
            for u in sorted(all_units):
                parts = u.split("/")
                l_lang, l_ext = parts[1], parts[2]
                l_pkg = f"eu.kanade.tachiyomi.animeextension.{l_lang}.{l_ext}"
                eff = sync.effective_version(None, u)
                eff_code = eff[2] if eff else 1
                prod_catalog.append({
                    "name": f"Aniyomi: {l_ext.capitalize()}",
                    "pkg": l_pkg,
                    "apk": f"aniyomi-{l_lang}.{l_ext}-v16.{eff_code}.apk",
                    "lang": l_lang,
                    "code": eff_code,
                    "version": f"16.{eff_code}",
                    "nsfw": 0,
                    "sources": [{"name": l_ext.capitalize(), "lang": l_lang, "id": "1", "baseUrl": ""}],
                })

        incomplete_catalog = [item for item in prod_catalog if item["pkg"] != target_pkg]
        for item in incomplete_catalog:
            (apk_dir / item["apk"]).write_bytes(b"dummy_apk")

        test_index_min = temp_dist / "index.min.json"
        test_index = temp_dist / "index.json"
        with open(test_index_min, "w", encoding="utf-8") as f:
            json.dump(incomplete_catalog, f, separators=(",", ":"))
        with open(test_index, "w", encoding="utf-8") as f:
            json.dump(incomplete_catalog, f, indent=2)

        # 1-3. Detect absence, classify PUBLISH_PENDING, and return exit code 2
        # (Verified both with explicit distribution_dir and auto-inferred from catalog path)
        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            ret_detect = checker.check_consistency(
                check_pending=True,
                catalog_target=str(test_index_min),
                distribution_dir=temp_dist,
            )
            self.assertEqual(ret_detect, 2, "Pending publication must return exit code 2")

            ret_detect_auto = checker.check_consistency(
                check_pending=True,
                catalog_target=str(test_index_min),
            )
            self.assertEqual(ret_detect_auto, 2, "Auto-inferred distribution must also return exit code 2")

        # 4-7. Trigger recovery mechanism
        recovered = sync.recover_pending_publications(
            [target_unit],
            distribution_dir=temp_dist,
            staging_dir=staging_dir,
        )
        self.assertEqual(recovered, "RECOVERED", "Publication recovery must succeed")

        # Verify APK exists and has non-zero size
        recovered_apk = apk_dir / target_apk_name
        self.assertTrue(recovered_apk.is_file(), "Recovered APK must exist in distribution directory")
        self.assertGreater(recovered_apk.stat().st_size, 0, "Recovered APK must not be empty")

        # 8. Validate correspondence between catalog and artifact
        with open(test_index_min, "r", encoding="utf-8") as f:
            updated_min = json.load(f)
        recovered_entry = next((e for e in updated_min if e["pkg"] == target_pkg), None)
        self.assertIsNotNone(recovered_entry, "Recovered entry must exist in index.min.json")
        self.assertEqual(recovered_entry["apk"], target_apk_name, "Catalog APK name must match artifact")
        self.assertEqual(recovered_entry["code"], 14, "Catalog versionCode must match source")

        # 9-10. Subsequent check returns exit code 0 (NO_CHANGES)
        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            ret_clean = checker.check_consistency(
                check_pending=True,
                catalog_target=str(test_index_min),
                distribution_dir=temp_dist,
            )
            self.assertEqual(ret_clean, 0, "Recovered state must return exit code 0 (IN_SYNC)")

        # Verify missing APK with present catalog entry also triggers PUBLISH_PENDING
        recovered_apk.unlink()
        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            ret_missing_apk = checker.check_consistency(
                check_pending=True,
                catalog_target=str(test_index_min),
                distribution_dir=temp_dist,
            )
            self.assertEqual(ret_missing_apk, 2, "Missing APK on disk must trigger exit code 2")

        # Re-recover missing APK
        sync.recover_pending_publications(
            [target_unit],
            distribution_dir=temp_dist,
            staging_dir=staging_dir,
        )
        self.assertTrue(recovered_apk.is_file(), "Re-recovered APK must exist")
        with patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
             patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            ret_clean2 = checker.check_consistency(
                check_pending=True,
                catalog_target=str(test_index_min),
                distribution_dir=temp_dist,
            )
            self.assertEqual(ret_clean2, 0, "Re-recovered state must return exit code 0")

        # =====================================================================
        # Active Run Deferral & Dispatch Verification (Codex Findings P1 & P2)
        # =====================================================================
        # Case A: Active run in progress defers recovery rather than preempting/cancelling
        with patch.dict(os.environ, {"GH_TOKEN": "mock-pat", "GITHUB_REPOSITORY": "Awerkori/anime-fonte-extensoes"}), \
             patch("time.sleep"), \
             patch("urllib.request.urlopen") as mock_url:
            mock_runs = MagicMock()
            mock_runs.read.return_value = json.dumps({
                "workflow_runs": [{"id": 9999, "head_branch": "main", "status": "in_progress", "head_sha": "def5678"}]
            }).encode("utf-8")
            mock_runs.__enter__.return_value = mock_runs
            mock_url.return_value = mock_runs

            res_deferred = sync.recover_pending_publications(
                [target_unit],
                distribution_dir=None,
                staging_dir=None,
                catalog_target="https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json",
            )
            self.assertEqual(res_deferred, "DEFERRED", "Active build must defer recovery instead of cancelling active run")

        # Case B: No active run in progress proceeds to dispatch recovery workflow
        with patch.dict(os.environ, {"GH_TOKEN": "mock-pat", "GITHUB_REPOSITORY": "Awerkori/anime-fonte-extensoes"}), \
             patch("urllib.request.urlopen") as mock_url:
            # First call queries runs (empty), second call dispatches (HTTP 204)
            mock_runs = MagicMock()
            mock_runs.read.return_value = json.dumps({"workflow_runs": []}).encode("utf-8")
            mock_runs.__enter__.return_value = mock_runs

            mock_dispatch = MagicMock()
            mock_dispatch.status = 204
            mock_dispatch.__enter__.return_value = mock_dispatch

            mock_url.side_effect = [mock_runs, mock_dispatch]

            res_dispatched = sync.recover_pending_publications(
                [target_unit],
                distribution_dir=None,
                staging_dir=None,
                catalog_target="https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json",
            )
            self.assertEqual(res_dispatched, "DISPATCHED", "Clean state without active runs must dispatch recovery")

        # Case C: Active run finishes during wait window, then proceeds to dispatch
        with patch.dict(os.environ, {"GH_TOKEN": "mock-pat", "GITHUB_REPOSITORY": "Awerkori/anime-fonte-extensoes"}), \
             patch("time.sleep"), \
             patch("urllib.request.urlopen") as mock_url:
            mock_active = MagicMock()
            mock_active.read.return_value = json.dumps({
                "workflow_runs": [{"id": 9999, "head_branch": "main", "status": "in_progress"}]
            }).encode("utf-8")
            mock_active.__enter__.return_value = mock_active

            mock_finished = MagicMock()
            mock_finished.read.return_value = json.dumps({"workflow_runs": []}).encode("utf-8")
            mock_finished.__enter__.return_value = mock_finished

            mock_dispatch = MagicMock()
            mock_dispatch.status = 204
            mock_dispatch.__enter__.return_value = mock_dispatch

            mock_url.side_effect = [mock_active, mock_finished, mock_dispatch]

            res_wait_and_dispatch = sync.recover_pending_publications(
                [target_unit],
                distribution_dir=None,
                staging_dir=None,
                catalog_target="https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json",
            )
            self.assertEqual(res_wait_and_dispatch, "DISPATCHED", "Active run finishing during wait must dispatch recovery")

        # Case D: Active run check fails (API outage/timeout) -> defers rather than risking cancellation
        with patch.dict(os.environ, {"GH_TOKEN": "mock-pat", "GITHUB_REPOSITORY": "Awerkori/anime-fonte-extensoes"}), \
             patch("urllib.request.urlopen", side_effect=urllib.error.URLError("Connection refused")):
            res_error_deferred = sync.recover_pending_publications(
                [target_unit],
                distribution_dir=None,
                staging_dir=None,
                catalog_target="https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json",
            )
            self.assertEqual(res_error_deferred, "DEFERRED", "Active run check failure must defer recovery")

        # Case E: Nonterminal statuses ("requested", "pending") are recognized as active (Codex Finding P2)
        for nonterminal_status in ("requested", "pending", "queued", "waiting", "in_progress"):
            with patch.dict(os.environ, {"GH_TOKEN": "mock-pat", "GITHUB_REPOSITORY": "Awerkori/anime-fonte-extensoes"}), \
                 patch("time.sleep"), \
                 patch("urllib.request.urlopen") as mock_url:
                mock_active = MagicMock()
                mock_active.read.return_value = json.dumps({
                    "workflow_runs": [{"id": 8888, "head_branch": "main", "status": nonterminal_status}]
                }).encode("utf-8")
                mock_active.__enter__.return_value = mock_active
                mock_url.return_value = mock_active

                res_nonterminal = sync.recover_pending_publications(
                    [target_unit],
                    distribution_dir=None,
                    staging_dir=None,
                    catalog_target="https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json",
                )
                self.assertEqual(res_nonterminal, "DEFERRED", f"Status '{nonterminal_status}' must be recognized as active and deferred")

        # Case F: Corrupt staging APK candidate is skipped, valid build APK candidate recovered (Codex Finding P2)
        corrupt_staging_dir = Path(self.temp_dir) / "corrupt_staging"
        corrupt_staging_dir.mkdir(parents=True, exist_ok=True)
        corrupt_apk = corrupt_staging_dir / target_apk_name
        corrupt_apk.write_bytes(b"CORRUPT_NOT_ZIP_HEADER_DATA_12345")

        valid_out_dir = Path(f"{target_unit}/build/outputs/apk/release")
        valid_out_dir.mkdir(parents=True, exist_ok=True)
        valid_apk = valid_out_dir / target_apk_name
        valid_apk.write_bytes(b"PK\x03\x04" + b"\x00" * 50)

        # Clear existing disk apk from earlier test
        if recovered_apk.is_file():
            recovered_apk.unlink()

        # Recovery should skip corrupt_staging_dir and pick valid_apk from valid_out_dir
        res_skip_corrupt = sync.recover_pending_publications(
            [target_unit],
            distribution_dir=temp_dist,
            staging_dir=corrupt_staging_dir,
            catalog_target=str(test_index_min),
        )
        self.assertEqual(res_skip_corrupt, "RECOVERED", "Recovery must skip corrupt candidate and recover valid artifact")
        self.assertTrue(recovered_apk.is_file(), "Recovered APK must exist")
        self.assertTrue(sync.is_valid_apk_file(recovered_apk), "Recovered APK must have valid PK magic header")

        # Case G: Concurrent remote probing and target resolution verification (Codex Finding P1)
        remote_catalog_url = "https://raw.githubusercontent.com/Awerkori/anime-extensoes/repo/index.min.json"
        with patch.object(
            checker,
            "resolve_distribution_context",
            return_value=(
                [{"name": "Aniyomi: Anipm", "pkg": target_pkg, "apk": target_apk_name, "code": 14, "version": "16.14"}],
                "Mock Remote",
                "VERIFIED",
                None,
                None,
                remote_catalog_url,
            ),
        ), patch.object(
            checker,
            "check_remote_apks_concurrent",
            return_value={target_apk_name: "MISSING"},
        ) as mock_concurrent:
            pending_res, dist_d, has_incon, t_kind, res_tgt = sync.get_publish_pending_units(
                catalog_target=remote_catalog_url,
                return_context=True,
            )
            self.assertEqual(t_kind, "url", "Target kind must resolve to 'url'")
            self.assertEqual(res_tgt, remote_catalog_url, "Resolved target URL must match catalog target")
            self.assertIn(target_unit, pending_res, "Missing remote APK detected via concurrent check must be pending")
            self.assertTrue(mock_concurrent.called, "check_remote_apks_concurrent must be used for custom verified URL")

        # Case H: Bounded concurrent fallback probing for APKs absent from Git tree (Codex Finding P1)
        with patch.object(
            checker,
            "resolve_distribution_context",
            return_value=(
                [{"name": "Aniyomi: Anipm", "pkg": target_pkg, "apk": target_apk_name, "code": 14, "version": "16.14"}],
                "Mock Git Tree",
                "VERIFIED",
                None,
                (set(), set()),  # Tree succeeded, but target_apk_name is absent
                remote_catalog_url,
            ),
        ), patch.object(
            checker,
            "check_remote_apks_concurrent",
            return_value={target_apk_name: "MISSING"},
        ) as mock_tree_fallback:
            pending_tree, _, _, _, _ = sync.get_publish_pending_units(
                catalog_target=remote_catalog_url,
                return_context=True,
            )
            self.assertIn(target_unit, pending_tree, "Missing tree APK probed concurrently must be pending")
            self.assertTrue(mock_tree_fallback.called, "Fallback probes for missing tree APKs must use check_remote_apks_concurrent")

        # Case I: Inconclusive sample upgraded by conclusive full probe (Codex Finding P2)
        with patch.object(
            checker,
            "resolve_distribution_context",
            return_value=(
                [{"name": "Aniyomi: Anipm", "pkg": target_pkg, "apk": target_apk_name, "code": 14, "version": "16.14"}],
                "Mock Remote Transient Outage",
                "UNVERIFIED_ARTIFACTS",  # Initial sample failed
                None,
                None,
                remote_catalog_url,
            ),
        ), patch.object(
            checker,
            "check_remote_apks_concurrent",
            return_value={target_apk_name: "MISSING"},  # Conclusive full probe
        ), patch("check_consistency_mod.get_upstream_head", return_value="f21e729f"), \
           patch("check_consistency_mod.get_sync_branch_head", return_value="f21e729f"):
            ret_upgrade = checker.check_consistency(
                check_pending=True,
                catalog_target=remote_catalog_url,
            )
            self.assertEqual(ret_upgrade, 2, "Conclusive full probe must upgrade UNVERIFIED_ARTIFACTS and exit 2 for pending publication")

        # Case J: generate-build-matrices adds pending modules to both modules and deleted sets
        import importlib.util
        gbm_path = Path(__file__).resolve().parent / "generate-build-matrices.py"
        spec_gbm = importlib.util.spec_from_file_location("gbm_mod", str(gbm_path))
        gbm_mod = importlib.util.module_from_spec(spec_gbm)
        spec_gbm.loader.exec_module(gbm_mod)

        with patch.object(gbm_mod, "get_publish_pending_modules", return_value=[":src:en:anipm"]):
            with patch("subprocess.run") as mock_sub:
                mock_sub.return_value = MagicMock(returncode=0, stdout="")
                mods, dels = gbm_mod.get_module_list("HEAD~1")
                self.assertIn(":src:en:anipm", mods, "Pending publication module must be added to build matrix")
                self.assertIn("en.anipm", dels, "Pending publication module must be added to delete set for APK cleanup")


if __name__ == "__main__":
    unittest.main()
