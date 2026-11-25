#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -gt 1 ]; then
    echo 'Usage: bash scripts/fetch-upstream.sh [repository-relative-upstream-lock.json]' >&2
    exit 2
fi
lock_values=$(python3 - "${1:-upstream.lock.json}" <<'PY'
import json
import pathlib
import sys
root = pathlib.Path.cwd().resolve()
def safe_path(value):
    path = pathlib.PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or not path.parts or not (root/path).resolve().is_relative_to(root):
        raise SystemExit('Upstream lock and source paths must be safe repository-relative paths')
    return path
lock = json.loads((root/safe_path(sys.argv[1])).read_text())
for key in ('commit', 'tree'):
    if len(lock[key]) != 40 or any(c not in '0123456789abcdef' for c in lock[key]):
        raise SystemExit('Upstream commit and tree must be complete lowercase Git hashes')
target = safe_path(lock['path'])
if target.parts[0] != 'third_party' or len(target.parts) < 2:
    raise SystemExit('Pristine upstream must live inside third_party/')
print(lock['commit'], lock['tree'], target, sep='\t')
PY
)
IFS=$'\t' read -r revision tree target <<< "$lock_values"
repo=https://github.com/ggml-org/llama.cpp.git
if [ -e "$target" ] && [ ! -d "$target/.git" ]; then
    echo 'Upstream destination already exists without its own Git checkout; preserving it.' >&2
    exit 1
fi
if [ ! -d "$target/.git" ]; then
    git clone --depth 1 "$repo" "$target"
fi
if [ -n "$(git -C "$target" status --porcelain)" ]; then
    echo 'Upstream checkout contains changes; refusing to overwrite them.' >&2
    exit 1
fi
if ! git -C "$target" cat-file -e "$revision^{commit}" 2>/dev/null; then
    git -C "$target" fetch --depth 1 origin "$revision"
fi
if [ "$(git -C "$target" rev-parse "$revision^{tree}")" != "$tree" ]; then
    echo 'Upstream commit tree differs from selected lock; refusing to check it out.' >&2
    exit 1
fi
git -C "$target" checkout --detach "$revision"
test "$(git -C "$target" rev-parse HEAD)" = "$revision"
