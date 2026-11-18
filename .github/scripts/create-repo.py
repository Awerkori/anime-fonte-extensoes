import argparse
import sys
sys.dont_write_bytecode = True
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"
SOURCE_INFO_DIR = Path("source-info")


def _get_aapt() -> Path:
    build_tools = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())
    if not build_tools:
        raise RuntimeError("No Android build-tools found under ANDROID_HOME")
    return build_tools[-1] / "aapt"


def _aapt_badging(apk: Path) -> str:
    return subprocess.check_output(
        [str(_get_aapt()), "dump", "--include-meta-data", "badging", str(apk)]
    ).decode()


def resolve_inspector_and_source_info(
    apk_dir: Path = REPO_APK_DIR,
    source_info_dir: Path = SOURCE_INFO_DIR,
    output_path: Path = Path("output.json"),
) -> dict:
    """Build output.json using compile-time keiyoushi-source-info.json first and Inspector.jar only for legacy APKs."""
    source_info_by_pkg: dict[str, list[dict]] = {}
    if source_info_dir.is_dir():
        for info_file in sorted(source_info_dir.glob("*.json")):
            info = json.loads(info_file.read_text(encoding="utf-8"))
            pkg = info.get("packageName")
            if pkg:
                source_info_by_pkg[pkg] = [
                    {
                        "name": src["name"],
                        "lang": src["lang"],
                        "id": str(src["id"]),
                        "baseUrl": src["baseUrl"],
                        "versionId": int(src.get("versionId", 1)),
                    }
                    for src in info.get("sources", [])
                ]

    output_data: dict[str, list[dict]] = {}
    legacy_apks: list[Path] = []
    if apk_dir.is_dir():
        for apk in sorted(apk_dir.iterdir()):
            if not apk.is_file() or apk.suffix != ".apk":
                continue
            badging = _aapt_badging(apk)
            package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
            pkg = PACKAGE_NAME_REGEX.search(package_info)[1]
            if pkg in source_info_by_pkg:
                output_data[pkg] = source_info_by_pkg[pkg]
            else:
                legacy_apks.append(apk)

    if legacy_apks:
        inspector_jar = Path("Inspector.jar")
        if not inspector_jar.is_file():
            subprocess.check_call(
                [sys.executable, str(Path(__file__).with_name("fetch_inspector.py")), "-o", str(inspector_jar)]
            )
        with tempfile.TemporaryDirectory(prefix="nox-legacy-inspector-") as tmpdir:
            tmp_root = Path(tmpdir)
            leg_apk_dir = tmp_root / "apk"
            leg_work_dir = tmp_root / "work"
            leg_out_file = tmp_root / "legacy-output.json"
            leg_apk_dir.mkdir(parents=True)
            for apk in legacy_apks:
                shutil.copy2(apk, leg_apk_dir / apk.name)
            subprocess.check_call(
                ["java", "-jar", str(inspector_jar), str(leg_apk_dir), str(leg_out_file), str(leg_work_dir)]
            )
            legacy_data = json.loads(leg_out_file.read_text(encoding="utf-8"))
            output_data.update(legacy_data)

    output_path.write_text(json.dumps(output_data, ensure_ascii=False), encoding="utf-8")
    return output_data


def create_repo(resolve_sources: bool = False) -> None:
    REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)
    output_path = Path("output.json")
    if resolve_sources or not output_path.exists():
        inspector_data = resolve_inspector_and_source_info(output_path=output_path)
    else:
        with output_path.open(encoding="utf-8") as f:
            inspector_data = json.load(f)

    index_min_data = []

    for apk in REPO_APK_DIR.iterdir():
        badging = _aapt_badging(apk)

        package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
        package_name = PACKAGE_NAME_REGEX.search(package_info)[1]
        application_icon = APPLICATION_ICON_320_REGEX.search(badging)[1]

        with ZipFile(apk) as z, z.open(application_icon) as i, (
            REPO_ICON_DIR / f"{package_name}.png"
        ).open("wb") as f:
            f.write(i.read())

        language = LANGUAGE_REGEX.search(apk.name)[1]
        sources = inspector_data[package_name]

        if len(sources) == 1:
            source_language = sources[0]["lang"]

            if (
                source_language != language
                and source_language not in {"all", "other"}
                and language not in {"all", "other"}
            ):
                language = source_language

        common_data = {
            "name": APPLICATION_LABEL_REGEX.search(badging)[1],
            "pkg": package_name,
            "apk": apk.name,
            "lang": language,
            "code": int(VERSION_CODE_REGEX.search(package_info)[1]),
            "version": VERSION_NAME_REGEX.search(package_info)[1],
            "nsfw": int(IS_NSFW_REGEX.search(badging)[1]),
        }
        min_data = {
            **common_data,
            "sources": [],
        }

        for source in sources:
            min_data["sources"].append(
                {
                    "name": source["name"],
                    "lang": source["lang"],
                    "id": source["id"],
                    "baseUrl": source["baseUrl"],
                    "versionId": source["versionId"],
                }
            )

        index_min_data.append(min_data)

    with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
        json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resolve-sources", action="store_true")
    args = parser.parse_args()
    create_repo(resolve_sources=args.resolve_sources)
