#!/usr/bin/env python3
"""
fetch-inspector.py — Project Nox Anime

Robust fetcher and validator for Inspector.jar:
- Prevents 92-byte corrupted / HTTP error payload downloads.
- Validates HTTP response status, redirects, and content length.
- Queries GitHub API with authentication (if GITHUB_TOKEN/GH_TOKEN set) and proper headers.
- Cascades to pinned known-good release URL on API failure or rate limit.
- Enforces minimum byte size (>= 10MB) and ZIP/JAR archive integrity (testzip).
- Executes controlled retries with exponential backoff on transient errors.
- Performs atomic write via temporary file to prevent corrupted jar artifacts.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True

API_LATEST_RELEASE_URL = "https://api.github.com/repos/komikku-app/aniyomi-extensions-inspector/releases/latest"
PINNED_INSPECTOR_URL = (
    "https://github.com/komikku-app/aniyomi-extensions-inspector/releases/download/"
    "v1.5.0.2/Aniyomi.Extensions.Inspector-v1.5.0.2-r799.jar"
)
MIN_JAR_SIZE_BYTES = 10 * 1024 * 1024  # 10 MB (official jar is ~31.9 MB)
DEFAULT_MAX_RETRIES = 3
DEFAULT_TIMEOUT_SECONDS = 30


def validate_jar(jar_path: Path, min_size: int = MIN_JAR_SIZE_BYTES) -> tuple[bool, str]:
    """
    Validate that jar_path exists, exceeds min_size, and is a structurally valid ZIP archive.
    Returns (is_valid, reason).
    """
    if not jar_path.is_file():
        return False, f"File does not exist: {jar_path}"

    size = jar_path.stat().st_size
    if size < min_size:
        return False, f"File size too small: {size} bytes (minimum required: {min_size} bytes)"

    if not zipfile.is_zipfile(jar_path):
        return False, f"File is not a valid zip/jar archive (size: {size} bytes)"

    try:
        with zipfile.ZipFile(jar_path, "r") as zf:
            corrupt_member = zf.testzip()
            if corrupt_member is not None:
                return False, f"Archive corrupt at member: {corrupt_member}"
    except Exception as exc:
        return False, f"Archive validation exception: {exc}"

    return True, f"Valid JAR archive ({size} bytes)"


def resolve_inspector_url(token: str | None = None, timeout: int = 15) -> str:
    """
    Query GitHub API for latest release asset URL. Falls back to PINNED_INSPECTOR_URL on failure.
    """
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "Project-Nox-CI",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(API_LATEST_RELEASE_URL, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                print(
                    f"Warning: GitHub API returned status {resp.status}. Using pinned fallback URL.",
                    file=sys.stderr,
                )
                return PINNED_INSPECTOR_URL

            payload = json.loads(resp.read().decode("utf-8"))
            assets = payload.get("assets", [])
            for asset in assets:
                name = asset.get("name", "")
                download_url = asset.get("browser_download_url", "")
                if name.endswith(".jar") and download_url.startswith("https://"):
                    print(f"Resolved latest Inspector asset via API: {name}")
                    return download_url

            print("Warning: No valid .jar asset found in latest release. Using pinned fallback.", file=sys.stderr)
            return PINNED_INSPECTOR_URL
    except Exception as exc:
        print(f"Warning: GitHub API resolution failed ({exc}). Using pinned fallback URL.", file=sys.stderr)
        return PINNED_INSPECTOR_URL


def download_file(
    url: str,
    dest_path: Path,
    min_size: int = MIN_JAR_SIZE_BYTES,
    max_retries: int = DEFAULT_MAX_RETRIES,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
) -> bool:
    """
    Download URL to dest_path atomically with HTTP status validation, retries, and integrity checks.
    """
    temp_path = dest_path.with_name(f"{dest_path.name}.tmp")
    headers = {
        "User-Agent": "Project-Nox-CI",
        "Accept": "*/*",
    }

    for attempt in range(1, max_retries + 1):
        try:
            print(f"Downloading Inspector from: {url} (attempt {attempt}/{max_retries})...")
            if temp_path.exists():
                temp_path.unlink()

            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = getattr(resp, "status", 200)
                if status != 200:
                    raise urllib.error.HTTPError(
                        url, status, f"Unexpected HTTP status {status}", resp.headers, None
                    )

                content_length = resp.headers.get("Content-Length")
                if content_length is not None:
                    cl_int = int(content_length)
                    if cl_int < min_size:
                        raise ValueError(
                            f"Server Content-Length ({cl_int} bytes) is below minimum required ({min_size} bytes)"
                        )

                with open(temp_path, "wb") as f_out:
                    downloaded = 0
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        f_out.write(chunk)
                        downloaded += len(chunk)

            # Validate downloaded temporary file
            is_valid, reason = validate_jar(temp_path, min_size=min_size)
            if not is_valid:
                raise ValueError(f"Downloaded file failed integrity validation: {reason}")

            # Atomic rename into destination
            temp_path.replace(dest_path)
            print(f"Successfully downloaded and verified: {dest_path} ({dest_path.stat().st_size} bytes)")
            return True

        except Exception as exc:
            print(f"Download attempt {attempt} failed: {exc}", file=sys.stderr)
            if temp_path.exists():
                try:
                    temp_path.unlink()
                except OSError:
                    pass

            if attempt < max_retries:
                backoff = attempt * 3
                print(f"Retrying in {backoff}s...", file=sys.stderr)
                time.sleep(backoff)

    return False


def fetch_and_verify_inspector(
    output_path: Path,
    min_size: int = MIN_JAR_SIZE_BYTES,
    pinned_url: str = PINNED_INSPECTOR_URL,
    max_retries: int = DEFAULT_MAX_RETRIES,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
    force: bool = False,
    token: str | None = None,
) -> Path:
    """
    Ensure output_path exists and contains a valid Inspector.jar.
    Downloads from API-resolved URL or pinned fallback URL if missing or invalid.
    """
    output_path = output_path.resolve()

    if not force and output_path.is_file():
        valid, reason = validate_jar(output_path, min_size=min_size)
        if valid:
            print(f"Existing Inspector JAR is valid: {output_path} ({reason})")
            return output_path
        else:
            print(f"Existing Inspector JAR invalid ({reason}). Re-downloading...", file=sys.stderr)
            try:
                output_path.unlink()
            except OSError:
                pass

    # Step 1: Attempt download using dynamically resolved API URL
    resolved_url = resolve_inspector_url(token=token, timeout=15)
    success = download_file(
        resolved_url,
        output_path,
        min_size=min_size,
        max_retries=max_retries,
        timeout=timeout,
    )

    # Step 2: If dynamic download failed and was different from pinned URL, try pinned URL
    if not success and resolved_url != pinned_url:
        print(f"Dynamic URL download failed. Trying pinned fallback URL: {pinned_url}", file=sys.stderr)
        success = download_file(
            pinned_url,
            output_path,
            min_size=min_size,
            max_retries=max_retries,
            timeout=timeout,
        )

    if not success:
        raise RuntimeError(
            f"Fatal: Failed to download a valid Inspector.jar after all retries and fallback URLs."
        )

    return output_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch and validate Inspector.jar for Project Nox Anime")
    parser.add_argument("-o", "--output", default="Inspector.jar", help="Target output file path (default: Inspector.jar)")
    parser.add_argument("--pinned-url", default=PINNED_INSPECTOR_URL, help="Pinned fallback URL")
    parser.add_argument("--min-size", type=int, default=MIN_JAR_SIZE_BYTES, help="Minimum file size in bytes")
    parser.add_argument("--retries", type=int, default=DEFAULT_MAX_RETRIES, help="Maximum download retries")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS, help="Timeout per request in seconds")
    parser.add_argument("--force", action="store_true", help="Force re-download even if existing jar is valid")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    output_path = Path(args.output)

    try:
        fetch_and_verify_inspector(
            output_path=output_path,
            min_size=args.min_size,
            pinned_url=args.pinned_url,
            max_retries=args.retries,
            timeout=args.timeout,
            force=args.force,
            token=token,
        )
        return 0
    except Exception as exc:
        print(f"Error fetching Inspector: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
