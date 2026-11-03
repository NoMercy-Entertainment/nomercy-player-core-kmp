import pathlib
import re
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


if __name__ == "__main__":
    unittest.main()
