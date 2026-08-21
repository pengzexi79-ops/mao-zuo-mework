import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("release_notes.py")
SPEC = importlib.util.spec_from_file_location("release_notes", SCRIPT)
release_notes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_notes)


class ReleaseNotesToolTest(unittest.TestCase):
    def test_validate_pending_generates_next_patch(self):
        pending = {
            "title": "测试发布",
            "summary": "验证自动发布记录。",
            "changes": ["更新功能"],
            "fixes": ["修复问题"],
            "verification": ["测试通过"],
            "compatibility": "无影响。",
            "evidence": ["test_release_notes.py"],
        }
        checked = release_notes.validate_pending(pending, "2.2.5")
        self.assertEqual("2.2.6", checked["version"])

    def test_validate_pending_rejects_non_incremental_version(self):
        pending = {
            "version": "2.2.7",
            "title": "测试发布",
            "summary": "验证自动发布记录。",
            "changes": ["更新功能"],
            "fixes": ["修复问题"],
            "verification": ["测试通过"],
            "compatibility": "无影响。",
            "evidence": ["test_release_notes.py"],
        }
        with self.assertRaises(ValueError):
            release_notes.validate_pending(pending, "2.2.5")

    def test_write_json_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.json"
            release_notes.write_json(path, {"version": "2.2.6"})
            self.assertEqual({"version": "2.2.6"}, json.loads(path.read_text(encoding="utf-8")))

    def test_installer_version_stays_in_sync(self):
        app_props = release_notes.APP_PROPS_PATH.read_text(encoding="utf-8")
        installer = release_notes.INSTALLER_PATH.read_text(encoding="utf-8")
        props_version = __import__("re").search(r'RELEASE_VERSION = "([^"]+)";', app_props).group(1)
        installer_version = __import__("re").search(r'^#define AppVersion "([^"]+)"$', installer, __import__("re").MULTILINE).group(1)
        self.assertEqual(props_version, installer_version)


if __name__ == "__main__":
    unittest.main()
