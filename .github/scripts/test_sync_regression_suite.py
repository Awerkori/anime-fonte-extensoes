#!/usr/bin/env python3
"""
Project Nox Anime — Comprehensive Regression Suite (Scenarios 1-15 + 2026-11-18 Incident Reproduction).
"""

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / ".github/scripts"


def _load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


sync = _load_module("sync_upstream_reg_mod", "sync-upstream.py")
checker = _load_module("check_consistency_reg_mod", "check-consistency.py")
pulse_guard = _load_module("pulse_guard_reg_mod", "pulse_guard.py")
gbm = _load_module("gbm_reg_mod", "generate-build-matrices.py")


def _git(cwd: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=check)


class SyncRegressionSuiteTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="nox_anime_reg_"))
        self.orig_cwd = Path.cwd()

    def tearDown(self):
        os.chdir(self.orig_cwd)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _init_bare_and_pair(self, name: str) -> tuple[Path, Path, Path]:
        upstream = self.temp_dir / f"{name}_upstream"
        origin_bare = self.temp_dir / f"{name}_origin.git"
        local = self.temp_dir / f"{name}_local"

        upstream.mkdir(parents=True)
        _git(upstream, "init", "-b", "master")
        _git(upstream, "config", "user.name", "Upstream")
        _git(upstream, "config", "user.email", "up@example.invalid")

        # Minimal valid Gradle-like workspace
        (upstream / "settings.gradle.kts").write_text(
            'rootProject.name = "Yuzono-Anime"\ninclude(":core")\ninclude(":compiler")\n',
            encoding="utf-8",
        )
        (upstream / "gradle").mkdir()
        (upstream / "gradle/libs.versions.toml").write_text(
            "[versions]\ncoroutines = \"1.10.2\"\n\n[libraries]\ncoroutines-core = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-core\", version.ref = \"coroutines\" }\n\n[bundles]\ncommon = [\"coroutines-core\"]\n",
            encoding="utf-8",
        )
        (upstream / "gradle/kei.versions.toml").write_text(
            "[plugins]\nextension = { id = \"kei.plugins.extension\" }\n",
            encoding="utf-8",
        )
        bl_dir = upstream / "gradle/build-logic/src/main/kotlin"
        bl_dir.mkdir(parents=True)
        (bl_dir / "ExtensionPlugin.kt").write_text(
            'dependencies {\n    implementation(project(":core"))\n    compileOnly(libs.bundles.common)\n    ksp(project(":compiler"))\n}\n',
            encoding="utf-8",
        )
        (upstream / "core").mkdir()
        (upstream / "core/build.gradle.kts").write_text(
            "dependencies {\n    compileOnly(libs.bundles.common)\n}\n",
            encoding="utf-8",
        )
        (upstream / "compiler").mkdir()
        (upstream / "compiler/build.gradle.kts").write_text("dependencies {}\n", encoding="utf-8")
        (upstream / "src/en/alpha").mkdir(parents=True)
        (upstream / "src/en/alpha/build.gradle.kts").write_text(
            'yuzono {\n    libVersion = "16"\n    versionCode = 1\n}\n',
            encoding="utf-8",
        )
        (upstream / "src/en/alpha/Alpha.kt").write_text("class Alpha\n", encoding="utf-8")
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Upstream base")

        _git(self.temp_dir, "init", "--bare", str(origin_bare))
        _git(self.temp_dir, "clone", str(upstream), str(local))
        _git(local, "config", "user.name", "Nox Bot")
        _git(local, "config", "user.email", "nox@example.invalid")
        _git(local, "branch", "-M", "main")
        _git(local, "remote", "rename", "origin", "upstream")
        _git(local, "remote", "add", "origin", str(origin_bare))

        # Nox customizations
        (local / "settings.gradle.kts").write_text(
            'rootProject.name = "Nox-Anime"\ninclude(":core")\ninclude(":compiler")\n',
            encoding="utf-8",
        )
        (local / "gradle/kei.versions.toml").write_text(
            "[plugins]\nextension = { id = \"kei.plugins.extension\" }\nlegacy-extension = { id = \"kei.plugins.extension.legacy\" }\n",
            encoding="utf-8",
        )
        (bl_dir_loc := local / "gradle/build-logic/src/main/kotlin").mkdir(parents=True, exist_ok=True)
        (bl_dir_loc / "PluginExtensionLegacy.kt").write_text(
            'dependencies {\n    implementation(project(":core"))\n    compileOnly(libs.bundles.common)\n}\n',
            encoding="utf-8",
        )
        (local / ".github/scripts").mkdir(parents=True, exist_ok=True)
        (local / ".github/nox-protected.txt").write_text("src/pt/prot\n", encoding="utf-8")
        (local / "src/pt/prot").mkdir(parents=True)
        (local / "src/pt/prot/build.gradle").write_text(
            "ext {\n    extName = 'Prot'\n    extVersionCode = 5\n}\n",
            encoding="utf-8",
        )
        (local / "src/pt/prot/Prot.kt").write_text("class Prot // Nox PT-BR\n", encoding="utf-8")
        (local / "src/pt/noxonly").mkdir(parents=True)
        (local / "src/pt/noxonly/build.gradle").write_text(
            "ext {\n    extName = 'NoxOnly'\n    extVersionCode = 2\n}\n",
            encoding="utf-8",
        )
        (local / "src/pt/noxonly/NoxOnly.kt").write_text("class NoxOnly\n", encoding="utf-8")
        up_head = _git(upstream, "rev-parse", "HEAD").stdout.strip()
        (local / ".github/sync-state.json").write_text(
            json.dumps({"upstream_sha": up_head, "last_validated_upstream_sha": up_head, "upstream_branch": "master"}, indent=2) + "\n",
            encoding="utf-8",
        )
        _git(local, "add", "-A")
        _git(local, "commit", "-m", "Nox setup")
        _git(local, "push", "-u", "origin", "main")
        _git(local, "push", "origin", "upstream/master:refs/heads/sync")
        return upstream, origin_bare, local

    # 1. Sincronização normal de extensões
    def test_01_normal_extension_sync(self):
        upstream, _, local = self._init_bare_and_pair("t01")
        (upstream / "src/en/alpha/build.gradle.kts").write_text(
            'yuzono {\n    libVersion = "16"\n    versionCode = 2\n}\n',
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Update alpha v2")
        os.chdir(local)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={"src/en/alpha": 1}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertIn("versionCode = 2", (local / "src/en/alpha/build.gradle.kts").read_text())
        state = json.loads((local / ".github/sync-state.json").read_text())
        self.assertEqual(state["sync_status"], "VALIDATED")
        self.assertEqual(state["upstream_sha"], _git(upstream, "rev-parse", "HEAD").stdout.strip())

    # 2. Sincronização com nova dependência Gradle
    def test_02_sync_with_new_gradle_dependency(self):
        upstream, _, local = self._init_bare_and_pair("t02")
        (upstream / "gradle/libs.versions.toml").write_text(
            "[versions]\ncoroutines = \"1.10.2\"\n\n[libraries]\n"
            "coroutines-core = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-core\", version.ref = \"coroutines\" }\n"
            "nanohttpd-fork = { module = \"com.github.komikku-app.nanohttpd:nanohttpd\", version = \"4ccb73a\" }\n\n"
            "[bundles]\ncommon = [\"coroutines-core\"]\n",
            encoding="utf-8",
        )
        (upstream / "core/build.gradle.kts").write_text(
            "dependencies {\n    compileOnly(libs.bundles.common)\n    implementation(libs.nanohttpd.fork)\n}\n",
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Add nanohttpd-fork")
        os.chdir(local)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertIn("nanohttpd-fork", (local / "gradle/libs.versions.toml").read_text())
        self.assertEqual(sync.run_structural_validations(), [])

    # 3. Sincronização com novo módulo compartilhado
    def test_03_sync_with_new_shared_module(self):
        upstream, _, local = self._init_bare_and_pair("t03")
        (upstream / "extensions-lib").mkdir()
        (upstream / "extensions-lib/build.gradle.kts").write_text("dependencies {}\n", encoding="utf-8")
        (upstream / "settings.gradle.kts").write_text(
            'rootProject.name = "Yuzono-Anime"\ninclude(":core")\ninclude(":extensions-lib")\ninclude(":compiler")\n',
            encoding="utf-8",
        )
        (upstream / "gradle/build-logic/src/main/kotlin/ExtensionPlugin.kt").write_text(
            'dependencies {\n    implementation(project(":core"))\n    compileOnly(libs.bundles.common)\n    compileOnly(project(":extensions-lib"))\n    ksp(project(":compiler"))\n}\n',
            encoding="utf-8",
        )
        (upstream / "core/build.gradle.kts").write_text(
            'dependencies {\n    compileOnly(libs.bundles.common)\n    compileOnly(project(":extensions-lib"))\n}\n',
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Vendor extensions-lib")
        os.chdir(local)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertTrue((local / "extensions-lib/build.gradle.kts").is_file())
        self.assertIn('include(":extensions-lib")', (local / "settings.gradle.kts").read_text())
        self.assertIn('rootProject.name = "Nox-Anime"', (local / "settings.gradle.kts").read_text())
        self.assertIn('compileOnly(project(":extensions-lib"))', (local / "gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt").read_text())
        self.assertEqual(sync.run_structural_validations(), [])

    # 4. Alteração incompatível bloqueada antes do push + reprodução do incidente 18/11/2026
    def test_04_reproduce_2026_11_18_incident_blocked_before_push(self):
        upstream, origin_bare, local = self._init_bare_and_pair("t04_incident")
        pre_origin_main = _git(origin_bare, "rev-parse", "refs/heads/main").stdout.strip()
        pre_good_upstream = _git(upstream, "rev-parse", "HEAD").stdout.strip()

        # Simulate upstream commit introducing libs.nanohttpd.fork and :extensions-lib in core,
        # but simulate failure to provide the catalog alias (e.g. broken upstream or incomplete import)
        (upstream / "core/build.gradle.kts").write_text(
            'dependencies {\n    compileOnly(libs.bundles.common)\n    compileOnly(project(":extensions-lib"))\n    implementation(libs.nanohttpd.fork)\n}\n',
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Core uses nanohttpd.fork and :extensions-lib without defining them")
        bad_upstream = _git(upstream, "rev-parse", "HEAD").stdout.strip()

        os.chdir(local)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]), \
             self.assertRaises(SystemExit) as cm:
            sync.main()
        self.assertNotEqual(cm.exception.code, 0)

        # origin/main MUST NOT contain core/build.gradle.kts from the incompatible commit
        origin_core = _git(origin_bare, "show", "refs/heads/main:core/build.gradle.kts").stdout
        self.assertNotIn("nanohttpd.fork", origin_core, "Incompatible core change must NEVER reach origin/main")
        self.assertNotEqual(
            _git(origin_bare, "rev-parse", "refs/heads/sync", check=False).stdout.strip(),
            bad_upstream,
            "sync ref must NOT advance to broken upstream commit",
        )
        # sync-state.json MUST NOT mark broken commit as validated
        state = json.loads((local / ".github/sync-state.json").read_text())
        self.assertEqual(state["upstream_sha"], pre_good_upstream)
        self.assertEqual(state["sync_status"], "BLOCKED_DETERMINISTIC")
        self.assertEqual(state["blocked_candidate"]["upstream_sha"], bad_upstream)
        self.assertEqual(_git(origin_bare, "log", "-1", "--format=%s", "refs/heads/main").stdout.strip().startswith("[skip ci]"), True)
        self.assertEqual(sync.get_code_sha("HEAD"), pre_origin_main)

    # 5. Preservação integral das extensões PT-BR
    def test_05_ptbr_byte_for_byte_preserved(self):
        upstream, _, local = self._init_bare_and_pair("t05")
        (upstream / "src/pt/prot").mkdir(parents=True)
        (upstream / "src/pt/prot/Prot.kt").write_text("class Prot // Upstream overwrite\n", encoding="utf-8")
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Upstream touches PT-BR")
        os.chdir(local)
        pre_snap = sync.get_ptbr_snapshot(None)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertEqual(pre_snap, sync.get_ptbr_snapshot(None))

    # 6. Preservação das extensões exclusivas e protegidas
    def test_06_nox_exclusive_and_protected_preserved(self):
        upstream, _, local = self._init_bare_and_pair("t06")
        (upstream / "src/en/uponly").mkdir(parents=True)
        (upstream / "src/en/uponly/build.gradle.kts").write_text('yuzono {\n    libVersion = "16"\n    versionCode = 1\n}\n')
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Add uponly")
        _git(local, "fetch", "upstream", "master")
        os.chdir(local)
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertTrue((local / "src/pt/noxonly/NoxOnly.kt").is_file())
        self.assertTrue((local / "src/pt/prot/Prot.kt").is_file())
        self.assertTrue((local / "src/en/uponly/build.gradle.kts").is_file())

    # 7. Recuperação de falha transitória
    def test_07_transient_failure_recovery(self):
        upstream, _, local = self._init_bare_and_pair("t07")
        (upstream / "src/en/alpha/build.gradle.kts").write_text('yuzono {\n    libVersion = "16"\n    versionCode = 2\n}\n')
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Alpha v2")
        os.chdir(local)
        # Transient Gradle error -> exits with EXIT_TRANSIENT_NETWORK (76) and does NOT permanently block candidate
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sync, "validate_candidate_with_gradle", side_effect=sync.TransientNetworkError("HTTP 503 Service Unavailable")), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]), \
             self.assertRaises(SystemExit) as cm:
            sync.main()
        self.assertEqual(cm.exception.code, sync.EXIT_TRANSIENT_NETWORK)
        state = json.loads((local / ".github/sync-state.json").read_text())
        self.assertIsNone(state.get("blocked_candidate"))

        # Subsequent retry when network recovers succeeds cleanly
        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sync, "validate_candidate_with_gradle", return_value=(True, [], "")), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        self.assertIn("versionCode = 2", (local / "src/en/alpha/build.gradle.kts").read_text())

    # 8. Bloqueio de tentativas repetitivas em falhas determinísticas (sync & publication)
    def test_08_deterministic_loop_prevention(self):
        upstream, _, local = self._init_bare_and_pair("t08")
        (upstream / "core/build.gradle.kts").write_text(
            "dependencies {\n    implementation(libs.missing.alias)\n}\n",
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Broken core")
        os.chdir(local)

        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]), \
             self.assertRaises(SystemExit):
            sync.main()

        # Next run with identical (code_sha, upstream_sha) MUST deduplicate and NOT re-run Gradle or push
        with patch.object(sync, "apply_units") as mock_apply, \
             patch.object(sync, "validate_candidate_with_gradle") as mock_gradle, \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
            mock_apply.assert_not_called()
            mock_gradle.assert_not_called()

        # Publication recovery loop prevention when build_push.yml already failed deterministically on HEAD
        code_sha = sync.get_code_sha("HEAD")
        head_sha = _git(local, "rev-parse", "HEAD").stdout.strip()
        runs_payload = {
            "workflow_runs": [
                {"id": 555, "head_branch": "main", "status": "completed", "conclusion": "failure", "head_sha": head_sha}
            ]
        }
        jobs_payload = {
            "jobs": [{"id": 777, "name": "Build extensions (1)", "conclusion": "failure", "steps": []}]
        }
        r1 = MagicMock(); r1.read.return_value = json.dumps(runs_payload).encode(); r1.__enter__.return_value = r1
        r2 = MagicMock(); r2.read.return_value = json.dumps(jobs_payload).encode(); r2.__enter__.return_value = r2
        r3 = MagicMock(); r3.read.return_value = b"Script compilation error: Unresolved reference 'nanohttpd'"; r3.__enter__.return_value = r3
        mock_opener = MagicMock()
        mock_opener.open.return_value = r3
        with patch.dict(os.environ, {"GH_TOKEN": "tok", "GITHUB_REPOSITORY": "ProjectNox-org/anime-fonte-extensoes"}), \
             patch("urllib.request.urlopen", side_effect=[r1, r2]), \
             patch("urllib.request.build_opener", return_value=mock_opener):
            status = sync.recover_pending_publications(
                ["src/en/alpha"],
                catalog_target="https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.min.json",
                target_kind="url",
            )
        self.assertEqual(status, "BLOCKED_DETERMINISTIC")
        state = json.loads((local / ".github/sync-state.json").read_text())
        self.assertEqual(state["publication_block"]["code_sha"], code_sha)
        self.assertEqual(state["publication_block"]["failure_type"], "DETERMINISTIC_BUILD_FAILURE")

        # Transient 429 at tail of long redirected log -> TRANSIENT_BUILD_FAILURE + cross-host auth stripped
        long_prefix = b"x" * 250_000
        r_t1 = MagicMock(); r_t1.read.return_value = json.dumps(runs_payload).encode(); r_t1.__enter__.return_value = r_t1
        r_t2 = MagicMock(); r_t2.read.return_value = json.dumps(jobs_payload).encode(); r_t2.__enter__.return_value = r_t2
        r_t3 = MagicMock()
        r_t3.read.return_value = long_prefix + b"\nReceived status code 429 from server: Too Many Requests\n"
        r_t3.__enter__.return_value = r_t3
        r_disp = MagicMock(); r_disp.status = 204; r_disp.__enter__.return_value = r_disp
        transient_opener = MagicMock()

        def _check_opener_open(req, timeout=20):
            # Capture the redirect handler created by sync.recover_pending_publications
            return r_t3

        transient_opener.open.side_effect = _check_opener_open
        # Reset block for fresh classification check
        state["publication_block"] = None
        (local / ".github/sync-state.json").write_text(json.dumps(state), encoding="utf-8")
        captured_handlers = []
        orig_build_opener = urllib.request.build_opener

        def _spy_build_opener(*handlers):
            captured_handlers.extend(handlers)
            return transient_opener

        with patch.dict(os.environ, {"GH_TOKEN": "tok", "GITHUB_REPOSITORY": "ProjectNox-org/anime-fonte-extensoes"}), \
             patch("urllib.request.urlopen", side_effect=[r_t1, r_t2, r_disp]), \
             patch("urllib.request.build_opener", side_effect=_spy_build_opener):
            status_t = sync.recover_pending_publications(
                ["src/en/alpha"],
                catalog_target="https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/index.min.json",
                target_kind="url",
            )
        self.assertEqual(status_t, "DISPATCHED")
        state_t = json.loads((local / ".github/sync-state.json").read_text())
        self.assertEqual(state_t["publication_block"]["failure_type"], "TRANSIENT_BUILD_FAILURE")
        self.assertTrue(captured_handlers)
        redir_req = captured_handlers[0].redirect_request(
            urllib.request.Request("https://api.github.com/logs", headers={"Authorization": "token secret"}),
            None,
            302,
            "Found",
            {},
            "https://pipelines.actions.githubusercontent.com/sas?sig=abc",
        )
        self.assertIsNotNone(redir_req)
        self.assertNotIn("Authorization", redir_req.headers)
        self.assertNotIn("Authorization", redir_req.unredirected_hdrs)

    # 9. Recuperação após correção de um commit anteriormente bloqueado
    def test_09_recovery_after_fix_of_blocked_commit(self):
        upstream, _, local = self._init_bare_and_pair("t09")
        (upstream / "core/build.gradle.kts").write_text("dependencies {\n    implementation(libs.missing.alias)\n}\n")
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Missing alias")
        os.chdir(local)

        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]), \
             self.assertRaises(SystemExit):
            sync.main()

        # Upstream fixes the catalog by adding missing-alias
        (upstream / "gradle/libs.versions.toml").write_text(
            "[versions]\ncoroutines = \"1.10.2\"\n\n[libraries]\n"
            "coroutines-core = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-core\", version.ref = \"coroutines\" }\n"
            "missing-alias = { module = \"org.example:fixed\", version = \"1.0\" }\n\n"
            "[bundles]\ncommon = [\"coroutines-core\"]\n",
            encoding="utf-8",
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Fix missing-alias in catalog")

        with patch.object(sync, "fetch_deployed_version_codes", return_value={}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        state = json.loads((local / ".github/sync-state.json").read_text())
        self.assertEqual(state["sync_status"], "VALIDATED")
        self.assertIsNone(state.get("blocked_candidate"))

    # 10. Proteção contra concorrência entre workflows
    def test_10_concurrency_protection(self):
        upstream, origin_bare, local = self._init_bare_and_pair("t10")
        start_main = _git(local, "rev-parse", "origin/main").stdout.strip()
        # Simulate another workflow pushing to origin/main concurrently
        other = self.temp_dir / "other_clone"
        _git(self.temp_dir, "clone", "-b", "main", str(origin_bare), str(other))
        _git(other, "config", "user.name", "Other")
        _git(other, "config", "user.email", "other@example.invalid")
        (other / "README.md").write_text("concurrent push\n")
        _git(other, "add", "-A")
        _git(other, "commit", "-m", "Concurrent main advance")
        _git(other, "push", "origin", "HEAD:main")

        os.chdir(local)
        with self.assertRaises(sync.ConcurrencyRaceError):
            sync.ensure_main_unchanged(start_main)

        # Pulse guard also blocks split-brain
        res = pulse_guard.ActiveRunsResult(status="OK", runs=[12345], attempts=1)
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "schedule"}), \
             patch.object(pulse_guard, "get_active_runs", return_value=res), \
             patch("sys.stdout", new_callable=io.StringIO):
            args = MagicMock(event_name=None, trigger_source=None, current_run_id="999", repo=None, workflow="sync_upstream.yml", no_backoff=True)
            rc = pulse_guard.cmd_watchdog_check(args)
        self.assertEqual(rc, 0)

    # 11. Sincronização sem alterações, sem commits ou builds desnecessários
    def test_11_no_changes_idempotent_no_commits(self):
        _, origin_bare, local = self._init_bare_and_pair("t11")
        before_head = _git(origin_bare, "rev-parse", "refs/heads/main").stdout.strip()
        os.chdir(local)
        out_file = self.temp_dir / "gh_out"
        with patch.dict(os.environ, {"GITHUB_OUTPUT": str(out_file)}), \
             patch.object(sync, "get_publish_pending_units", return_value=([], None, False, "file", None)), \
             patch.object(sys, "argv", ["sync-upstream.py", "--push"]):
            sync.main()
        after_head = _git(origin_bare, "rev-parse", "refs/heads/main").stdout.strip()
        self.assertEqual(before_head, after_head)
        self.assertIn("should_pulse=false", out_file.read_text())

    # 12. Compilação / detecção das extensões impactadas por mudanças compartilhadas
    def test_12_shared_module_indirect_impacts(self):
        self.assertTrue(gbm.CORE_FILES_REGEX.match("extensions-lib/build.gradle.kts"))
        self.assertTrue(gbm.CORE_FILES_REGEX.match("core/build.gradle.kts"))
        upstream, _, local = self._init_bare_and_pair("t12")
        (upstream / "lib/myhelper").mkdir(parents=True)
        (upstream / "lib/myhelper/build.gradle.kts").write_text("dependencies {}\n")
        (upstream / "src/en/useshelper").mkdir(parents=True)
        (upstream / "src/en/useshelper/build.gradle.kts").write_text(
            'dependencies { implementation(project(":lib:myhelper")) }\nyuzono { versionCode = 1 }\n'
        )
        _git(upstream, "add", "-A")
        _git(upstream, "commit", "-m", "Add helper and dependent")
        _git(local, "fetch", "upstream", "master")
        os.chdir(local)
        impacts = sync.detect_indirect_impacts(["lib/myhelper"])
        # Locally after sync it's in src/
        (local / "lib/myhelper").mkdir(parents=True)
        (local / "lib/myhelper/build.gradle.kts").write_text("dependencies {}\n")
        (local / "src/en/useshelper").mkdir(parents=True)
        (local / "src/en/useshelper/build.gradle.kts").write_text(
            'dependencies { implementation(project(":lib:myhelper")) }\nyuzono { versionCode = 1 }\n'
        )
        self.assertIn("src/en/useshelper", sync.detect_indirect_impacts(["lib/myhelper"]))

    # 13, 14, 15. Publicação, assinaturas, versionCodes, compatibilidade torrent/manifest/catalogs
    def test_13_14_15_publication_signatures_versioncodes_and_catalog_compat(self):
        # Verify real repo structural validation and key Nox invariants
        os.chdir(REPO_ROOT)
        self.assertEqual(sync.run_structural_validations(), [])
        gen_manifest = (REPO_ROOT / "gradle/build-logic/src/main/kotlin/io/github/keiyoushi/gradle/tasks/GenerateManifestTask.kt").read_text()
        self.assertIn('aniyomix.name', gen_manifest)
        self.assertIn('aniyomix.torrent', gen_manifest)
        self.assertIn('tachiyomi.animeextension.torrent', gen_manifest)
        ext_plugin = (REPO_ROOT / "gradle/build-logic/src/main/kotlin/ExtensionPlugin.kt").read_text()
        leg_plugin = (REPO_ROOT / "gradle/build-logic/src/main/kotlin/PluginExtensionLegacy.kt").read_text()
        self.assertIn('META-INF/nanohttpd/default-mimetypes.properties', ext_plugin)
        self.assertIn('META-INF/nanohttpd/default-mimetypes.properties', leg_plugin)
        self.assertIn('compileOnly(project(":extensions-lib"))', ext_plugin)
        self.assertIn('compileOnly(project(":extensions-lib"))', leg_plugin)

        # Verify publish_protobuf & merge-repo torrent=1 + downgrade protection
        res_torrent = subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "test_merge_repo_torrent.py")],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res_torrent.returncode, 0, res_torrent.stderr)

        pb_python = os.environ.get("NOX_PUBLISH_PYTHON", "").strip()
        if not pb_python:
            local_venv_py = REPO_ROOT.parent / ".venv-publish/bin/python"
            probe = subprocess.run(
                [sys.executable, "-c", "import google.protobuf"],
                capture_output=True,
            )
            if probe.returncode == 0:
                pb_python = sys.executable
            elif local_venv_py.exists():
                pb_python = str(local_venv_py)
            else:
                pb_python = sys.executable

        res_pb = subprocess.run(
            [pb_python, str(SCRIPTS_DIR / "test_publish_protobuf.py")],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res_pb.returncode, 0, res_pb.stderr)

    def test_16_hybrid_source_info_skips_inspector_for_modern_apks(self):
        create_repo_mod = _load_module("create_repo_reg_mod", "create-repo.py")
        work = self.temp_dir / "cr"
        apk_dir = work / "repo/apk"
        info_dir = work / "source-info"
        apk_dir.mkdir(parents=True)
        info_dir.mkdir(parents=True)
        (apk_dir / "aniyomi-all.modern-v16.1.apk").write_bytes(b"modern")
        (apk_dir / "aniyomi-pt.legacy-v16.2.apk").write_bytes(b"legacy")
        (info_dir / "eu.kanade.tachiyomi.animeextension.all.modern.json").write_text(
            json.dumps({
                "packageName": "eu.kanade.tachiyomi.animeextension.all.modern",
                "sources": [{"name": "Modern", "lang": "all", "id": 123456, "baseUrl": "https://modern.test"}],
            }),
            encoding="utf-8",
        )
        inspected_pkgs = []

        def fake_badging(apk: Path) -> str:
            if "modern" in apk.name:
                return "package: name='eu.kanade.tachiyomi.animeextension.all.modern' versionCode='1' versionName='16.1'\n"
            return "package: name='eu.kanade.tachiyomi.animeextension.pt.legacy' versionCode='2' versionName='16.2'\n"

        def fake_check_call(cmd, *args, **kwargs):
            # Only legacy APK must be copied for Inspector.jar
            if "-jar" in cmd:
                leg_dir = Path(cmd[3])
                inspected_pkgs.extend(p.name for p in leg_dir.iterdir())
                Path(cmd[4]).write_text(
                    json.dumps({
                        "eu.kanade.tachiyomi.animeextension.pt.legacy": [
                            {"name": "Legacy", "lang": "pt-BR", "id": "999", "baseUrl": "https://legacy.test", "versionId": 1}
                        ]
                    }),
                    encoding="utf-8",
                )

        inspector_dummy = work / "Inspector.jar"
        inspector_dummy.write_bytes(b"jar")
        out_json = work / "output.json"
        orig = Path.cwd()
        os.chdir(work)
        try:
            with patch.object(create_repo_mod, "_aapt_badging", side_effect=fake_badging), \
                 patch.object(create_repo_mod.subprocess, "check_call", side_effect=fake_check_call):
                data = create_repo_mod.resolve_inspector_and_source_info(
                    apk_dir=apk_dir, source_info_dir=info_dir, output_path=out_json
                )
        finally:
            os.chdir(orig)
        self.assertEqual(inspected_pkgs, ["aniyomi-pt.legacy-v16.2.apk"])
        self.assertIn("eu.kanade.tachiyomi.animeextension.all.modern", data)
        self.assertIn("eu.kanade.tachiyomi.animeextension.pt.legacy", data)
        self.assertEqual(data["eu.kanade.tachiyomi.animeextension.all.modern"][0]["id"], "123456")


if __name__ == "__main__":
    unittest.main()
