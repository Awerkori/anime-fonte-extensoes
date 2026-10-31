import html
import sys
import json
from pathlib import Path
import shutil

sys.dont_write_bytecode = True


REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

to_delete: list[str] = json.loads(sys.argv[1])


def pkg_to_module(pkg: str) -> str:
    return pkg.replace("eu.kanade.tachiyomi.animeextension.", "")


def get_existing_modules() -> set[str] | None:
    modules = set()
    # sys.argv[2] contains the ref_name and 'repo', e.g., 'main/repo'
    branch_dir_name = sys.argv[2].split('/')[0]
    src_dir = REMOTE_REPO.parent.joinpath(branch_dir_name, "src")

    if not src_dir.is_dir():
        print(f"WARNING: Source directory {src_dir} not found; skipping stale module pruning.")
        return None

    for lang_dir in src_dir.iterdir():
        if not lang_dir.is_dir():
            continue
        for ext_dir in lang_dir.iterdir():
            if ext_dir.is_dir():
                modules.add(f"{lang_dir.name}.{ext_dir.name}")

    return modules


existing_modules = get_existing_modules()

# Load indexes
with REMOTE_REPO.joinpath("index.json").open() as remote_index_file:
    remote_index = json.load(remote_index_file)

with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
    local_index = json.load(local_index_file)

remote_modules = {pkg_to_module(item["pkg"]) for item in remote_index}
remote_by_pkg = {item["pkg"]: item for item in remote_index}

skipped_downgrades = set()
filtered_local_index = []

for item in local_index:
    remote_item = remote_by_pkg.get(item["pkg"])
    if remote_item is not None and item["code"] < remote_item["code"]:
        module = pkg_to_module(item["pkg"])
        skipped_downgrades.add(module)
        print(
            "Skipping downgrade for "
            f"{module}: local code {item['code']} < remote code {remote_item['code']}"
        )
        continue

    filtered_local_index.append(item)

local_index = filtered_local_index

# Extensions that still exist in repo branch but no longer exist in source tree
stale_modules = set()
if existing_modules is not None and len(existing_modules) > 0:
    stale_modules = remote_modules - existing_modules
    if stale_modules:
        print(f"Removing stale modules not found in source tree: {sorted(stale_modules)}")

to_delete = list((set(to_delete) | stale_modules) - skipped_downgrades)
# Absolute protection: PT-BR modules are NEVER pruned or deleted from catalog
to_delete = [m for m in to_delete if not m.startswith("pt.")]


# Delete old files
for module in to_delete:
    apk_name = f"aniyomi-{module}-v*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(f"Removing old APK: {file.name}")
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(f"Removing old Icon: {file.name}")
        file.unlink(missing_ok=True)

# Copy APKs
remote_apk_dir = REMOTE_REPO.joinpath("apk")
remote_apk_dir.mkdir(exist_ok=True)
local_apks = {item["apk"] for item in local_index}
for file in LOCAL_REPO.joinpath("apk").glob("*"):
    if file.name not in local_apks:
        continue
    shutil.copy2(file, remote_apk_dir / file.name)

# Copy Icons
remote_icon_dir = REMOTE_REPO.joinpath("icon")
remote_icon_dir.mkdir(exist_ok=True)
local_icons = {f"{item['pkg']}.png" for item in local_index}
for file in LOCAL_REPO.joinpath("icon").glob("*"):
    if file.name not in local_icons:
        continue
    shutil.copy2(file, remote_icon_dir / file.name)


# Build final index
# 1. Keep remote items that are NOT in to_delete
index = [
    item for item in remote_index
    if not any([item["pkg"].endswith(f".{module}") for module in to_delete])
]

# 2. Add local items
index.extend(local_index)
index.sort(key=lambda x: x["pkg"])

# Garbage Collection: Remove any APK or Icon that is not in the final index
valid_apks = {item["apk"] for item in index}
valid_icons = {f"{item['pkg']}.png" for item in index}

for file in remote_apk_dir.glob("*"):
    if file.name not in valid_apks:
        print(f"Garbage collection: Removing unindexed APK {file.name}")
        file.unlink(missing_ok=True)

for file in remote_icon_dir.glob("*"):
    if file.name not in valid_icons:
        print(f"Garbage collection: Removing unindexed Icon {file.name}")
        file.unlink(missing_ok=True)

# Save files
with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')
