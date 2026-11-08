#!/usr/bin/env python3
"""
test_fetch_inspector.py — Unit test suite for fetch-inspector.py
"""

import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True

import fetch_inspector


class TestFetchInspector(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.test_dir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _create_valid_zip(self, path: Path, size_pad: int = 0) -> Path:
        """Create a structurally valid zip file, stored uncompressed to ensure size exceeds min_size."""
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            if size_pad > 0:
                zf.writestr("dummy.bin", b"X" * size_pad)
        return path

    def test_validate_jar_non_existent(self):
        non_existent = self.test_dir / "does_not_exist.jar"
        valid, reason = fetch_inspector.validate_jar(non_existent, min_size=100)
        self.assertFalse(valid)
        self.assertIn("does not exist", reason)

    def test_validate_jar_92_bytes_corrupted(self):
        """Simulate the exact 92-byte corrupted payload from run 42466754903."""
        corrupt_file = self.test_dir / "Inspector.jar"
        corrupt_file.write_bytes(b"A" * 92)
        self.assertEqual(corrupt_file.stat().st_size, 92)

        valid, reason = fetch_inspector.validate_jar(corrupt_file, min_size=1000)
        self.assertFalse(valid)
        self.assertIn("too small", reason)

    def test_validate_jar_large_but_not_zip(self):
        garbage_file = self.test_dir / "garbage.jar"
        garbage_file.write_bytes(b"A" * 2000)
        valid, reason = fetch_inspector.validate_jar(garbage_file, min_size=1000)
        self.assertFalse(valid)
        self.assertIn("not a valid zip/jar", reason)

    def test_validate_jar_valid(self):
        jar_path = self.test_dir / "valid.jar"
        self._create_valid_zip(jar_path, size_pad=2000)
        valid, reason = fetch_inspector.validate_jar(jar_path, min_size=1000)
        self.assertTrue(valid)
        self.assertIn("Valid JAR archive", reason)

    def test_resolve_inspector_url_success(self):
        mock_payload = {
            "assets": [
                {
                    "name": "Aniyomi.Extensions.Inspector-v1.5.0.2-r799.jar",
                    "browser_download_url": "https://github.com/komikku-app/aniyomi-extensions-inspector/releases/download/v1.5.0.2/Aniyomi.Extensions.Inspector-v1.5.0.2-r799.jar",
                }
            ]
        }
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.read.return_value = json.dumps(mock_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with patch("urllib.request.urlopen", return_value=mock_resp):
            url = fetch_inspector.resolve_inspector_url(token="dummy_token")
            self.assertEqual(url, "https://github.com/komikku-app/aniyomi-extensions-inspector/releases/download/v1.5.0.2/Aniyomi.Extensions.Inspector-v1.5.0.2-r799.jar")

    def test_resolve_inspector_url_fallback_on_api_error(self):
        with patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError("url", 403, "Rate Limited", {}, None)):
            url = fetch_inspector.resolve_inspector_url()
            self.assertEqual(url, fetch_inspector.PINNED_INSPECTOR_URL)

    def test_resolve_inspector_url_fallback_on_missing_asset(self):
        mock_payload = {"assets": [{"name": "source.tar.gz", "browser_download_url": "https://example.com/src.tar.gz"}]}
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.read.return_value = json.dumps(mock_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with patch("urllib.request.urlopen", return_value=mock_resp):
            url = fetch_inspector.resolve_inspector_url()
            self.assertEqual(url, fetch_inspector.PINNED_INSPECTOR_URL)

    def test_download_file_rejects_sub_minimum_content_length(self):
        """Verify that server sending Content-Length: 92 is rejected without saving to dest_path."""
        dest_path = self.test_dir / "Inspector.jar"
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.headers = {"Content-Length": "92"}
        mock_resp.read.return_value = b"x" * 92
        mock_resp.__enter__.return_value = mock_resp

        with patch("urllib.request.urlopen", return_value=mock_resp), patch("time.sleep"):
            success = fetch_inspector.download_file("https://example.com/jar", dest_path, min_size=1000, max_retries=2)
            self.assertFalse(success)
            self.assertFalse(dest_path.exists())

    def test_download_file_retry_and_success(self):
        dest_path = self.test_dir / "Inspector.jar"

        valid_buf = io.BytesIO()
        with zipfile.ZipFile(valid_buf, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("test.txt", "hello world")
            zf.writestr("pad.bin", b"0" * 2000)
        valid_bytes = valid_buf.getvalue()

        # Attempt 1 fails with 504 Gateway Timeout, Attempt 2 succeeds
        mock_fail = urllib.error.HTTPError("url", 504, "Gateway Timeout", {}, None)

        mock_ok = MagicMock()
        mock_ok.status = 200
        mock_ok.headers = {"Content-Length": str(len(valid_bytes))}
        mock_ok.read.side_effect = [valid_bytes, b""]
        mock_ok.__enter__.return_value = mock_ok

        with patch("urllib.request.urlopen", side_effect=[mock_fail, mock_ok]), patch("time.sleep"):
            success = fetch_inspector.download_file("https://example.com/jar", dest_path, min_size=1000, max_retries=2)
            self.assertTrue(success)
            self.assertTrue(dest_path.is_file())
            self.assertEqual(dest_path.stat().st_size, len(valid_bytes))

    def test_fetch_and_verify_inspector_reuses_existing_valid(self):
        dest_path = self.test_dir / "Inspector.jar"
        self._create_valid_zip(dest_path, size_pad=2000)

        with patch("fetch_inspector.resolve_inspector_url") as mock_resolve:
            res = fetch_inspector.fetch_and_verify_inspector(dest_path, min_size=1000)
            self.assertEqual(res, dest_path)
            mock_resolve.assert_not_called()

    def test_fetch_and_verify_inspector_replaces_invalid_existing(self):
        dest_path = self.test_dir / "Inspector.jar"
        dest_path.write_bytes(b"A" * 92)  # 92 bytes

        valid_buf = io.BytesIO()
        with zipfile.ZipFile(valid_buf, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("valid.txt", "content")
            zf.writestr("pad", b"A" * 3000)
        valid_bytes = valid_buf.getvalue()

        mock_ok = MagicMock()
        mock_ok.status = 200
        mock_ok.headers = {"Content-Length": str(len(valid_bytes))}
        mock_ok.read.side_effect = [valid_bytes, b""]
        mock_ok.__enter__.return_value = mock_ok

        with patch("fetch_inspector.resolve_inspector_url", return_value="https://example.com/latest.jar"), \
             patch("urllib.request.urlopen", return_value=mock_ok), \
             patch("time.sleep"):
            res = fetch_inspector.fetch_and_verify_inspector(dest_path, min_size=1000)
            self.assertEqual(res, dest_path)
            self.assertEqual(dest_path.stat().st_size, len(valid_bytes))


if __name__ == "__main__":
    unittest.main()
