import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("merge-repo.py")
PREFIX = "eu.kanade.tachiyomi.animeextension."


class MergeRepoTorrentTest(unittest.TestCase):
    def test_preserves_torrent_capability_in_existing_catalog_without_builds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "main" / "src" / "all"
            source.joinpath("torrent").mkdir(parents=True)
            (source / "torrent" / "build.gradle.kts").write_text(
                "yuzuno {\n    torrent = true\n}\n", encoding="utf-8"
            )
            source.joinpath("regular").mkdir()
            (source / "regular" / "build.gradle.kts").write_text(
                "yuzuno {\n}\n", encoding="utf-8"
            )

            remote = root / "repo"
            (remote / "apk").mkdir(parents=True)
            (remote / "icon").mkdir()
            local = root / "main" / "repo"
            (local / "apk").mkdir(parents=True)
            index = []
            for module in ("all.torrent", "all.regular"):
                package = PREFIX + module
                apk = f"aniyomi-{module}-v14.1.apk"
                index.append(
                    {
                        "name": module,
                        "pkg": package,
                        "apk": apk,
                        "lang": "all",
                        "code": 1,
                        "version": "14.1",
                        "nsfw": 0,
                        "sources": [],
                    }
                )
                (remote / "apk" / apk).write_bytes(b"existing apk")
                (remote / "icon" / f"{package}.png").write_bytes(b"existing icon")

            (remote / "index.json").write_text(json.dumps(index), encoding="utf-8")
            (local / "index.min.json").write_text("[]", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "[]", "main/repo"],
                cwd=remote,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            for catalog_name in ("index.json", "index.min.json"):
                published = json.loads((remote / catalog_name).read_text(encoding="utf-8"))
                by_package = {item["pkg"]: item for item in published}
                torrent_val = by_package[PREFIX + "all.torrent"]["torrent"]
                self.assertEqual(torrent_val, 1)
                self.assertIs(type(torrent_val), int)
                self.assertNotIn("torrent", by_package[PREFIX + "all.regular"])


if __name__ == "__main__":
    unittest.main()
