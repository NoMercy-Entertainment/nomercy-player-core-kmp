#!/usr/bin/env bash
# Attach the XCFramework to the tag's release and stamp its checksum into the
# manifest.
#
# The two have to happen together. A release asset without the matching checksum
# in Package.swift is a package nobody can resolve, and a checksum committed
# without the asset is a package that resolves to a 404 — both look like a broken
# release to a consumer and neither is visible to whoever cut it.
#
#   tools/release-xcframework.sh NoMercyPlayerCore Package.swift
set -euo pipefail

name="${1:?usage: release-xcframework.sh <FrameworkName> <path/to/Package.swift>}"
manifest="${2:?manifest path required}"
here="$(cd "$(dirname "$0")/.." && pwd)"
tag="${GITHUB_REF_NAME:?this runs on a tag}"

output="$("$here/tools/package-xcframework.sh" "$name")"
echo "$output"

zip_path="$(echo "$output" | sed -n 's/^XCFRAMEWORK_ZIP=//p')"
checksum="$(echo "$output" | sed -n 's/^XCFRAMEWORK_CHECKSUM=//p')"

if [ -z "$zip_path" ] || [ -z "$checksum" ]; then
  echo "packaging produced no zip or no checksum — nothing to release" >&2
  exit 1
fi

gh release upload "$tag" "$zip_path" --clobber

# The URL carries the tag too, so a manifest cut for one release cannot quietly
# serve another one's asset.
python3 - "$here/$manifest" "$checksum" "$tag" <<'PY'
import io
import re
import sys

path, checksum, tag = sys.argv[1], sys.argv[2], sys.argv[3]
text = io.open(path, encoding="utf-8", newline="").read()

before = text
text = text.replace('checksum: "REPLACED_BY_RELEASE_JOB"', 'checksum: "%s"' % checksum)
text = re.sub(r"/download/v[^/]+/", "/download/%s/" % tag, text)

if text == before:
    print("manifest already carried this checksum and tag — nothing to stamp")
else:
    io.open(path, "w", encoding="utf-8", newline="").write(text)
    print("stamped %s into %s" % (checksum, path))
PY

if ! git diff --quiet -- "$manifest"; then
  git config user.name "Stoney_Eagle"
  git config user.email "45034970+StoneyEagle@users.noreply.github.com"
  git add "$manifest"
  git commit -m "build(dist): stamp the $tag XCFramework checksum into $manifest"
  # Onto the default branch rather than the detached tag checkout, or the commit
  # exists only in the runner and the next consumer resolves the placeholder.
  git push origin "HEAD:${GITHUB_DEFAULT_BRANCH:-master}"
fi
