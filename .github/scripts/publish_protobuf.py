import gzip
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

import index_pb2

APK_NAME = re.compile(r"^aniyomi-[A-Za-z0-9_.-]+\.apk$")
PACKAGE_NAME = re.compile(
    r"^eu\.kanade\.tachiyomi\.animeextension(?:\.[A-Za-z0-9_]+)+$"
)
VERSION_NAME = re.compile(r"^([0-9]+)\.([0-9]+)$")
APK_BASE_URL = "https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/apk/"
ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/ProjectNox-org/anime-extensoes@repo/icon/"
SIGNING_FINGERPRINT = (
    "144f44e865cbfae6bde110489ef7ec142f0b1681f627a093a218650e21db5dad"
)


def _required_text(value, field):
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} must be a non-empty string")
    return value


def _source_url(value):
    if not isinstance(value, str):
        raise ValueError("source baseUrl must be a string")
    if value:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError(f"source baseUrl must be an HTTP or HTTPS URL: {value!r}")
    return value


def build_catalog(entries, metadata):
    if not isinstance(entries, list) or not entries:
        raise ValueError("catalog must be a non-empty list")
    if not isinstance(metadata, dict):
        raise ValueError("repository metadata must be an object")

    name = _required_text(metadata.get("name"), "meta.name")
    badge_label = _required_text(metadata.get("shortName"), "meta.shortName")
    website = _required_text(metadata.get("website"), "meta.website")
    signing_key = _required_text(
        metadata.get("signingKeyFingerprint"), "meta.signingKeyFingerprint"
    )
    if signing_key != SIGNING_FINGERPRINT:
        raise ValueError("repository signing-key fingerprint does not match Nox")
    parsed_website = urlsplit(website)
    if parsed_website.scheme not in {"http", "https"} or not parsed_website.netloc:
        raise ValueError("meta.website must be an HTTP or HTTPS URL")

    index = index_pb2.Index(
        name=name,
        badgeLabel=badge_label,
        signingKey=signing_key,
        contact=index_pb2.Contact(
            website=website,
            discord="https://discord.com/invite/qNAMYUEmGj",
        ),
    )
    seen_packages = set()
    for item in entries:
        if not isinstance(item, dict):
            raise ValueError("catalog entries must be objects")
        package_name = _required_text(item.get("pkg"), "pkg")
        apk_name = _required_text(item.get("apk"), "apk")
        if not PACKAGE_NAME.fullmatch(package_name):
            raise ValueError(f"invalid anime extension package name: {package_name!r}")
        if not APK_NAME.fullmatch(apk_name):
            raise ValueError(f"invalid APK filename: {apk_name!r}")
        if package_name in seen_packages:
            raise ValueError(f"duplicate package name: {package_name}")
        seen_packages.add(package_name)

        version_name = _required_text(item.get("version"), "version")
        version_match = VERSION_NAME.fullmatch(version_name)
        code = item.get("code")
        if (
            version_match is None
            or not isinstance(code, int)
            or isinstance(code, bool)
            or code <= 0
            or int(version_match.group(2)) != code
        ):
            raise ValueError(f"inconsistent version/code for {package_name}")
        nsfw = item.get("nsfw")
        if not isinstance(nsfw, int) or isinstance(nsfw, bool) or nsfw not in (0, 1):
            raise ValueError(f"nsfw must be 0 or 1 for {package_name}")
        torrent = item.get("torrent", False)
        if not isinstance(torrent, bool):
            raise ValueError(f"torrent must be a boolean for {package_name}")
        sources = item.get("sources")
        if not isinstance(sources, list):
            raise ValueError(f"sources must be a list for {package_name}")

        extension = index.extensionList.extensions.add(
            name=_required_text(item.get("name"), "name"),
            packageName=package_name,
            extensionLib=version_match.group(1),
            versionCode=int(version_match.group(1)) * 100_000 + code,
            versionName=version_name,
            contentWarning=(
                index_pb2.CONTENT_WARNING_NSFW
                if nsfw == 1
                else index_pb2.CONTENT_WARNING_SAFE
            ),
            isTorrent=torrent,
        )
        extension.resources.apkUrl = APK_BASE_URL + apk_name
        extension.resources.iconUrl = ICON_BASE_URL + package_name + ".png"

        for source in sources:
            if not isinstance(source, dict):
                raise ValueError(f"sources must contain objects for {package_name}")
            raw_source_id = source.get("id")
            if (
                isinstance(raw_source_id, bool)
                or not isinstance(raw_source_id, (int, str))
                or isinstance(raw_source_id, str) and not raw_source_id.isdecimal()
            ):
                raise ValueError(f"invalid source id for {package_name}")
            try:
                source_id = int(raw_source_id)
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"invalid source id for {package_name}") from error
            if not 0 <= source_id <= 2**63 - 1:
                raise ValueError(f"source id out of int64 range for {package_name}")
            extension.sources.add(
                id=source_id,
                name=_required_text(source.get("name"), "source.name"),
                language=_required_text(source.get("lang"), "source.lang"),
                homeUrl=_source_url(source.get("baseUrl", "")),
            )

    payload = index.SerializeToString(deterministic=True)
    compressed = bytearray(gzip.compress(payload, mtime=0))
    compressed[9] = 3
    return bytes(compressed)


def main():
    repo_dir = Path.cwd()
    entries = json.loads((repo_dir / "index.json").read_text(encoding="utf-8"))
    config = json.loads((repo_dir / "repo.json").read_text(encoding="utf-8"))
    compressed = build_catalog(entries, config.get("meta"))
    (repo_dir / "index.pb").write_bytes(compressed)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"Protobuf catalog publication failed: {error}", file=sys.stderr)
        sys.exit(1)
