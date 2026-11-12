import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("release-xcframework.sh")


class ChecksumStampTest(unittest.TestCase):
    def test_replaces_checksum_from_previous_release(self):
        source = SCRIPT.read_text(encoding="utf-8")
        stamp = re.search(r"python3 - .*?<<'PY'\n(.*?)\nPY", source, re.S)
        self.assertIsNotNone(stamp)
        old = "a" * 64
        new = "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            manifest = pathlib.Path(directory) / "Package.swift"
            manifest.write_text(
                '.binaryTarget(name: "Core", url: "https://example.test/download/v0.2.7/Core.zip", '
                f'checksum: "{old}")\n',
                encoding="utf-8",
            )
            subprocess.run(
                [sys.executable, "-c", stamp.group(1), str(manifest), new, "v0.2.8"],
                check=True,
                capture_output=True,
                text=True,
            )
            stamped = manifest.read_text(encoding="utf-8")
            self.assertIn(f'checksum: "{new}"', stamped)
            self.assertIn("/download/v0.2.8/", stamped)


def git(cwd, *args):
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


class StampPushTest(unittest.TestCase):
    """The stamp lands on master even when master moved during the build."""

    def test_push_survives_a_commit_that_landed_during_the_build(self):
        source = SCRIPT.read_text(encoding="utf-8")
        block = re.search(r"\nif ! git diff --quiet -- .*?\nfi\n", source, re.S)
        self.assertIsNotNone(block)
        bash = shutil.which("bash")
        self.assertIsNotNone(bash)
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            origin, runner, other = root / "origin.git", root / "runner", root / "other"
            git(root, "init", "-q", "--bare", "-b", "master", str(origin))
            git(root, "clone", "-q", str(origin), str(other))
            for name in ("Package.swift", "notes.txt"):
                (other / name).write_text("checksum: \"old\"\n", encoding="utf-8")
            git(other, "add", ".")
            git(other, "-c", "user.name=t", "-c", "user.email=t@example.test", "commit", "-qm", "tag")
            git(other, "push", "-q", "origin", "HEAD:master")
            git(root, "clone", "-q", str(origin), str(runner))
            git(runner, "checkout", "-q", "--detach")
            # Another fix lands on master while the framework builds.
            (other / "notes.txt").write_text("a later fix\n", encoding="utf-8")
            git(other, "-c", "user.name=t", "-c", "user.email=t@example.test", "commit", "-qam", "later fix")
            git(other, "push", "-q", "origin", "HEAD:master")
            (runner / "Package.swift").write_text("checksum: \"new\"\n", encoding="utf-8")
            result = subprocess.run(
                [bash, "-c", "set -euo pipefail\n" + block.group(0)],
                cwd=runner,
                env={**os.environ, "manifest": "Package.swift", "tag": "v9.9.9", "GITHUB_DEFAULT_BRANCH": "master"},
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            log = git(origin, "log", "--format=%s", "master")
            self.assertIn("later fix", log)
            self.assertIn("stamp the v9.9.9 XCFramework checksum", log)


if __name__ == "__main__":
    unittest.main()
