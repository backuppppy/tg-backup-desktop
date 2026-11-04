#!/usr/bin/env bash
# Compares the engine copy against the Android app it was taken from.
# Usage: tools/engine-sync-check.sh <android-repo> <commit>
# Prints SAME / EDITED / NEW / MISSING per file and exits 1 if an EDITED file is not listed
# in docs/PORTING.md (by its path under org/tgbackup/app/, e.g. `data/Store.kt`).
set -euo pipefail

repo=${1:?usage: $0 <android-repo> <commit>}
commit=${2:?usage: $0 <android-repo> <commit>}
root=$(cd "$(dirname "$0")/.." && pwd)
engine="$root/engine/src/main/kotlin/org/tgbackup/app"
android="app/src/main/java/org/tgbackup/app"
porting="$root/docs/PORTING.md"
status=0

# Files in the copied packages at the commit.
mapfile -t upstream < <(git -C "$repo" ls-tree -r --name-only "$commit" -- \
    "$android/data" "$android/engine" "$android/tg" "$android/export" | sed "s#^$android/##" | grep '\.kt$')

declare -A seen
while IFS= read -r rel; do
    seen[$rel]=1
    if git -C "$repo" cat-file -e "$commit:$android/$rel" 2>/dev/null; then
        if git -C "$repo" show "$commit:$android/$rel" | cmp -s - "$engine/$rel"; then
            echo "SAME    $rel"
        else
            if grep -qF "\`$rel\`" "$porting"; then
                echo "EDITED  $rel"
            else
                echo "EDITED  $rel  (not listed in docs/PORTING.md)"
                status=1
            fi
        fi
    else
        echo "NEW     $rel"
    fi
done < <(cd "$engine" && find . -name '*.kt' | sed 's#^\./##' | sort)

for rel in "${upstream[@]}"; do
    [[ -n ${seen[$rel]:-} ]] || echo "MISSING $rel"
done

exit $status
