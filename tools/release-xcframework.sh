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
# The release to attach to. GITHUB_REF_NAME is the tag on a tag push, but on a
# workflow_dispatch it is the BRANCH the workflow ran from -- so a re-attach
# would have uploaded to a release named "master". RELEASE_TAG says it outright.
tag="${RELEASE_TAG:-${GITHUB_REF_NAME:?this runs on a tag}}"

# gh, by absolute path when PATH does not have it.
#
# The fifth tool in the situation build.gradle.kts documents for bash, gh, gtar
# and docker: a launchd-started runner inherits no login shell, so Homebrew's
# directories are not on its PATH. The framework built fine and then this script
# died on "gh: command not found" with the zip already on disk.
gh_bin() {
  local candidate
  for candidate in \
    "${NOMERCY_GH:-}" \
    /opt/homebrew/bin/gh \
    /usr/local/bin/gh \
    /usr/bin/gh; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  printf 'gh'
}
# Through bash: this script is committed executable, and a tag cut before that
# was true is still a tag somebody may need to re-attach a framework to.
output="$(bash "$here/tools/package-xcframework.sh" "$name")"
echo "$output"

zip_path="$(echo "$output" | sed -n 's/^XCFRAMEWORK_ZIP=//p')"
checksum="$(echo "$output" | sed -n 's/^XCFRAMEWORK_CHECKSUM=//p')"

if [ -z "$zip_path" ] || [ -z "$checksum" ]; then
  echo "packaging produced no zip or no checksum — nothing to release" >&2
  exit 1
fi

# The release object, when nothing has made one yet.
#
# `gh release upload` needs a RELEASE, and a tag is not one. Nothing in the
# pipeline created it, so v0.1.0 failed here with a bare "release not found"
# after the framework had already been built twice. Creating it here keeps the
# asset and the release that carries it in one place, which is the same reason
# the checksum is stamped in this script rather than a step away from it.
if ! "$(gh_bin)" release view "$tag" >/dev/null 2>&1; then
  "$(gh_bin)" release create "$tag" --title "$tag" --generate-notes
fi

"$(gh_bin)" release upload "$tag" "$zip_path" --clobber

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
