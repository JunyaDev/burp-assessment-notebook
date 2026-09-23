#!/usr/bin/env bash
# Publish a GitHub release for this repo and upload the built extension jar.
#
# Needs a GitHub token with "repo" scope (or fine-grained Contents: read/write):
#     export GH_TOKEN=ghp_xxx        # or GITHUB_TOKEN
#     scripts/publish-release.sh [tag]
#
# Default tag is v<version> from build.gradle (v1.0.0). Builds the jar first if
# it is missing. Uses only curl (no gh CLI, no jq).
set -euo pipefail
cd "$(dirname "$0")/.."

OWNER_REPO="JunyaDev/burp-assessment-notebook"
VERSION="$(sed -n "s/^version = '\(.*\)'/\1/p" build.gradle | head -1)"
TAG="${1:-v${VERSION:-1.0.0}}"
JAR="build/libs/assessment-notebook.jar"
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

[ -n "$TOKEN" ] || { echo "Set GH_TOKEN (or GITHUB_TOKEN) first." >&2; exit 1; }
[ -f "$JAR" ] || { echo "Building jar..."; scripts/build-jar.sh; }

API="https://api.github.com/repos/$OWNER_REPO"
UP="https://uploads.github.com/repos/$OWNER_REPO"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

echo "==> creating release $TAG"
body=$(cat RELEASE_NOTES.md 2>/dev/null || echo "Assessment Notebook $TAG")
# JSON-encode the body safely.
payload=$(TAG="$TAG" BODY="$body" python3 -c '
import json,os
print(json.dumps({"tag_name":os.environ["TAG"],"name":os.environ["TAG"],
  "body":os.environ["BODY"],"draft":False,"prerelease":False}))')

resp=$(curl -sS "${AUTH[@]}" -X POST "$API/releases" -d "$payload")
rel_id=$(printf '%s' "$resp" | grep -m1 '"id":' | grep -o '[0-9]\+' | head -1)
if [ -z "$rel_id" ]; then
  echo "Failed to create release:" >&2
  printf '%s\n' "$resp" >&2
  exit 1
fi
echo "    release id $rel_id"

echo "==> uploading $JAR"
curl -sS "${AUTH[@]}" -H "Content-Type: application/java-archive" \
  --data-binary @"$JAR" \
  "$UP/releases/$rel_id/assets?name=assessment-notebook-$TAG.jar" \
  -o /dev/null -w "    asset upload HTTP %{http_code}\n"

echo "Done: https://github.com/$OWNER_REPO/releases/tag/$TAG"
