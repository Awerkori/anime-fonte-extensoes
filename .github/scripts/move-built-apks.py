import json
import sys
sys.dont_write_bytecode = True
from pathlib import Path
import shutil

REPO_APK_DIR = Path("repo/apk")
SOURCE_INFO_DIR = Path("source-info")

shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
shutil.rmtree(SOURCE_INFO_DIR, ignore_errors=True)
SOURCE_INFO_DIR.mkdir(parents=True, exist_ok=True)

artifacts_dir = Path.home().joinpath("apk-artifacts")
for apk in artifacts_dir.glob("**/*.apk"):
    apk_name = apk.name.replace("-release.apk", ".apk")

    shutil.move(apk, REPO_APK_DIR.joinpath(apk_name))

for info_file in artifacts_dir.glob("**/keiyoushi-source-info.json"):
    try:
        info = json.loads(info_file.read_text(encoding="utf-8"))
        pkg = info.get("packageName")
        if pkg:
            shutil.copy2(info_file, SOURCE_INFO_DIR.joinpath(f"{pkg}.json"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Warning: skipping invalid source info {info_file}: {exc}", file=sys.stderr)
