#!/usr/bin/env bash
# Downloads one exact Paper build from PaperMC's download service (Fill v3) and checks its SHA-256.
#
#   ci/download-paper.sh <minecraft-version> <build> <sha256> <out.jar>
#
# The pins live in .github/workflows/ci.yml (PAPER_VERSION, PAPER_BUILD, PAPER_SHA256). Needs curl, jq, sha256sum.
set -euo pipefail

version=${1:?usage: download-paper.sh <version> <build> <sha256> <out.jar>}
build=${2:?}
sha=${3:?}
out=${4:?}
# PaperMC asks every client to identify itself with a contact URL.
ua="logic-blocks-ci/1.0 (https://github.com/tedtalksbits/logic-blocks)"

if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --status 2>/dev/null; then
    echo "download-paper: $out already matches the pinned build"
    exit 0
fi

builds_json=$(curl -fsSL --retry 3 -H "User-Agent: $ua" \
    "https://fill.papermc.io/v3/projects/paper/versions/$version/builds")
url=$(jq -r --argjson id "$build" \
    'first(.[] | select(.id == $id) | .downloads."server:default".url) // empty' <<< "$builds_json")
[ -n "$url" ] || { echo "download-paper: Paper $version build $build not found" >&2; exit 1; }

mkdir -p "$(dirname "$out")"
curl -fsSL --retry 3 -H "User-Agent: $ua" -o "$out.part" "$url"
if ! echo "$sha  $out.part" | sha256sum -c --status 2>/dev/null; then
    echo "download-paper: checksum mismatch for $url (expected $sha, got $(sha256sum "$out.part" | cut -d' ' -f1))" >&2
    rm -f "$out.part"
    exit 1
fi
mv "$out.part" "$out"
echo "download-paper: Paper $version-$build -> $out"
