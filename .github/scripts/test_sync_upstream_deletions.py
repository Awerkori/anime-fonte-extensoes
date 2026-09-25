#!/usr/bin/env python3
"""Regression tests for Yuzono source edits, removals, and repo artifact cleanup."""

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "sync_upstream", ROOT / ".github/scripts/sync-upstream.py",
)
sync = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sync
SPEC.loader.exec_module(sync)


def run(*args: str, cwd: Path, check: bool = True) -> str:
    result = subprocess.run(args, cwd=cwd, text=True, capture_output=True)
    if check and result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


class SyncDeletionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="nox-sync-delete-")
        self.repo = Path(self.temp.name) / "repo"
        run("git", "init", "-b", "main", str(self.repo), cwd=Path(self.temp.name))
        run("git", "config", "user.name", "Regression Test", cwd=self.repo)
        run("git", "config", "user.email", "sync-test@example.invalid", cwd=self.repo)
        self.old_cwd = Path.cwd()
        os.chdir(self.repo)
        self.add_source("src/tr/turkanime", 1)
        self.add_source("src/pt/conflict", 1)
        self.add_source("src/pt/mass_bump", 1)
        self.add_source("src/pt/custom_edit", 1)
        run("git", "add", ".", cwd=self.repo)
        run("git", "commit", "-m", "merge base", cwd=self.repo)
        self.base = run("git", "rev-parse", "HEAD", cwd=self.repo)
        run("git", "switch", "-c", "upstream", cwd=self.repo)

    def tearDown(self) -> None:
        os.chdir(self.old_cwd)
        self.temp.cleanup()

    def add_source(self, unit: str, code: int) -> None:
        path = self.repo / unit
        path.mkdir(parents=True, exist_ok=True)
        (path / "build.gradle").write_text(
            f"ext {{\n    extVersionCode = {code}\n}}\n",
            encoding="utf-8",
        )

    def commit_upstream(self, operation) -> str:
        operation()
        run("git", "add", "-A", cwd=self.repo)
        run("git", "commit", "-m", "upstream update", cwd=self.repo)
        run("git", "update-ref", "refs/remotes/upstream/master", "HEAD", cwd=self.repo)
        return "upstream/master"

    def create_divergence(self, upstream_change, nox_change=None):
        upstream_ref = self.commit_upstream(upstream_change)
        run("git", "switch", "main", cwd=self.repo)
        if nox_change:
            nox_change()
            run("git", "add", "-A", cwd=self.repo)
            run("git", "commit", "-m", "Nox local changes", cwd=self.repo)
        upstream_units = sync.collect_units(sync.changed_entries(self.base, upstream_ref))[0]
        main_units = sync.collect_units(sync.changed_entries(self.base, "HEAD"))[0]
        return upstream_ref, upstream_units, main_units

    def classify(self, upstream_ref, upstream_units, main_units, explicit=()):
        return sync.classify_sync_units(
            self.base, upstream_ref, upstream_units, main_units, list(explicit),
        )

    def test_upstream_and_nox_edits_remain_nox_wins(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: (self.repo / "src/pt/conflict/build.gradle").write_text("upstream fix\n"),
            lambda: (self.repo / "src/pt/conflict/build.gradle").write_text("Nox fix\n"),
        )
        upstream_only, conflicts, _, deleted, _ = self.classify(upstream_ref, up, local)
        self.assertIn("src/pt/conflict", conflicts)
        self.assertNotIn("src/pt/conflict", deleted)

    def test_upstream_delete_without_nox_edit_is_removed(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: shutil_rmtree(self.repo / "src/tr/turkanime"),
        )
        *_, deleted, kept = self.classify(upstream_ref, up, local)
        self.assertEqual(deleted, ["src/tr/turkanime"])
        self.assertFalse(kept)

    def test_upstream_delete_after_mass_bump_is_removed(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: shutil_rmtree(self.repo / "src/pt/mass_bump"),
            lambda: (self.repo / "src/pt/mass_bump/build.gradle").write_text("extVersionCode = 56\n"),
        )
        self.assertIn("src/pt/mass_bump", self.classify(upstream_ref, up, local)[3])

    def test_upstream_delete_after_custom_edit_is_removed_by_default(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: shutil_rmtree(self.repo / "src/pt/custom_edit"),
            lambda: (self.repo / "src/pt/custom_edit/build.gradle").write_text("Nox custom parser\n"),
        )
        self.assertIn("src/pt/custom_edit", self.classify(upstream_ref, up, local)[3])

    def test_explicit_delete_protection_keeps_source(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: shutil_rmtree(self.repo / "src/tr/turkanime"),
        )
        result = self.classify(upstream_ref, up, local, ["src/tr/turkanime"])
        self.assertIn("src/tr/turkanime", result[4])
        self.assertNotIn("src/tr/turkanime", result[3])

    def test_nox_exclusive_source_is_not_classified_as_deleted(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: self.add_source("src/fr/upstream_marker", 1),
            lambda: self.add_source("src/pt/nox_exclusive", 1),
        )
        _, _, nox_only, deleted, _ = self.classify(upstream_ref, up, local)
        self.assertIn("src/pt/nox_exclusive", nox_only)
        self.assertNotIn("src/pt/nox_exclusive", deleted)

    def test_new_upstream_source_is_imported_not_removed(self):
        def add():
            self.add_source("src/fr/new_upstream", 1)
        upstream_ref, up, local = self.create_divergence(add)
        upstream_only = self.classify(upstream_ref, up, local)[0]
        self.assertIn("src/fr/new_upstream", upstream_only)

    def test_upstream_rename_removes_old_unit_and_imports_new_unit(self):
        def rename():
            run("git", "mv", "src/tr/turkanime", "src/tr/turkanime-tv", cwd=self.repo)
        upstream_ref, up, local = self.create_divergence(rename)
        upstream_only, _, _, deleted, _ = self.classify(upstream_ref, up, local)
        self.assertIn("src/tr/turkanime", deleted)
        self.assertIn("src/tr/turkanime-tv", upstream_only)
        sync.apply_units(
            upstream_ref, up, set(), [], deleted, [],
        )
        self.assertFalse((self.repo / "src/tr/turkanime").exists())
        self.assertTrue((self.repo / "src/tr/turkanime-tv/build.gradle").exists())

    def test_build_matrix_and_repo_merge_delete_turkanime_artifacts(self):
        # The matrix sees a source-tree deletion as a DELETE module.
        base = self.base
        shutil_rmtree(self.repo / "src/tr/turkanime")
        run("git", "add", "-A", cwd=self.repo)
        run("git", "commit", "-m", "remove turkanime", cwd=self.repo)
        sys.path.insert(0, str(ROOT / ".github/scripts"))
        try:
            import importlib.util
            spec = importlib.util.spec_from_file_location(
                "matrix", ROOT / ".github/scripts/generate-build-matrices.py",
            )
            matrix = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(matrix)
            _, deleted = matrix.get_module_list(base)
        finally:
            sys.path.pop(0)
        self.assertIn("tr.turkanime", deleted)

        # Merge-repo uses that DELETE plus stale source/index detection to GC APK/icon/index.
        sandbox = Path(self.temp.name) / "publish"
        remote = sandbox / "repo"
        source = sandbox / "main"
        (remote / "apk").mkdir(parents=True)
        (remote / "icon").mkdir()
        (source / "src").mkdir(parents=True)
        (source / "repo").mkdir()
        item = {
            "pkg": "eu.kanade.tachiyomi.animeextension.tr.turkanime",
            "apk": "aniyomi-tr.turkanime-v1.apk",
            "code": 56,
            "sources": [],
        }
        (remote / "index.json").write_text(json.dumps([item]))
        (source / "repo/index.min.json").write_text("[]")
        (remote / "apk" / item["apk"]).write_bytes(b"apk")
        (remote / "icon" / f"{item['pkg']}.png").write_bytes(b"icon")
        script = ROOT / ".github/scripts/merge-repo.py"
        run(sys.executable, str(script), '["tr.turkanime"]', "main/repo", cwd=remote)
        self.assertEqual(json.loads((remote / "index.json").read_text()), [])
        self.assertEqual(json.loads((remote / "index.min.json").read_text()), [])
        self.assertFalse((remote / "apk" / item["apk"]).exists())
        self.assertFalse((remote / "icon" / f"{item['pkg']}.png").exists())

    def test_delete_only_ci_can_generate_empty_local_index(self):
        checkout = Path(self.temp.name) / "checkout"
        (checkout / "repo/apk").mkdir(parents=True)
        (checkout / "repo/icon").mkdir()
        (checkout / "output.json").write_text("{}\n", encoding="utf-8")
        sdk = Path(self.temp.name) / "sdk/build-tools/36.0.0"
        sdk.mkdir(parents=True)
        env = os.environ | {"ANDROID_HOME": str(sdk.parents[1])}
        result = subprocess.run(
            [sys.executable, str(ROOT / ".github/scripts/create-repo.py")],
            cwd=checkout, env=env, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((checkout / "repo/index.min.json").read_text()), [])

        workflow = (ROOT / ".github/workflows/build_push.yml").read_text("utf-8")
        self.assertIn("needs.build.result == 'skipped'", workflow)
        self.assertIn("echo '{}' > output.json", workflow)
        sync_script = (ROOT / ".github/scripts/sync-upstream.py").read_text("utf-8")
        self.assertNotRegex(sync_script, r'git\("push"[^\n]*"--force"')

    def test_dry_run_labels_turkanime_as_remove_not_conflict(self):
        upstream_ref, up, local = self.create_divergence(
            lambda: shutil_rmtree(self.repo / "src/tr/turkanime"),
            lambda: (self.repo / "src/tr/turkanime/build.gradle").write_text("extVersionCode = 56\n"),
        )
        upstream_only, conflicts, nox_only, deleted, protected = self.classify(
            upstream_ref, up, local,
        )
        import io
        from contextlib import redirect_stdout
        output = io.StringIO()
        with redirect_stdout(output):
            sync.print_plan(
                self.base, upstream_ref, upstream_only, conflicts, nox_only,
                [], set(), [], deleted, protected,
            )
        self.assertIn("[deleted upstream] src/tr/turkanime → REMOVE", output.getvalue())
        self.assertNotIn("[conflict→Nox] src/tr/turkanime", output.getvalue())

    def test_prior_ours_merge_does_not_hide_deletion_from_later_sync(self):
        shutil_rmtree(self.repo / "src/tr/turkanime")
        run("git", "add", "-A", cwd=self.repo)
        run("git", "commit", "-m", "Yuzono removes Türk Anime TV", cwd=self.repo)
        run("git", "update-ref", "refs/remotes/upstream/master", "HEAD", cwd=self.repo)
        upstream_ref = "upstream/master"

        # Reproduce the previous Nox-wins -s ours merge: upstream's deletion is
        # in the merge-base, but the local tree still carries the source.
        run("git", "switch", "main", cwd=self.repo)
        run("git", "merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref, cwd=self.repo)
        run("git", "commit", "-m", "old sync kept Nox tree", cwd=self.repo)
        current_base = run("git", "merge-base", "HEAD", upstream_ref, cwd=self.repo)
        self.assertEqual(current_base, run("git", "rev-parse", upstream_ref, cwd=self.repo))

        upstream_units = sync.collect_units(sync.changed_entries(current_base, upstream_ref))[0]
        main_units = sync.collect_units(sync.changed_entries(current_base, "HEAD"))[0]
        self.assertNotIn("src/tr/turkanime", upstream_units)
        _, conflicts, _, deleted, protected = sync.classify_sync_units(
            current_base, upstream_ref, upstream_units, main_units, [],
        )
        self.assertIn("src/tr/turkanime", deleted)
        self.assertNotIn("src/tr/turkanime", conflicts)

        sync.apply_units(
            upstream_ref,
            sorted(set(upstream_units) | set(deleted) | set(protected)),
            set(conflicts), [], deleted, protected,
        )
        self.assertFalse((self.repo / "src/tr/turkanime").exists())


def shutil_rmtree(path: Path) -> None:
    import shutil
    shutil.rmtree(path)


if __name__ == "__main__":
    unittest.main()
