import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import overlay_diagnostics as capture


class FakeProcess:
    def __init__(self, lines):
        self.stdout = io.BytesIO(lines)
        self.terminated = False

    def poll(self):
        return 0 if self.terminated else None

    def terminate(self):
        self.terminated = True

    def wait(self, timeout):
        return 0


class FakeAdb:
    def __init__(self, lines, original="INFO"):
        self.process = FakeProcess(lines)
        self.original = original
        self.calls = []

    def call(self, *args):
        self.calls.append(args)
        return self.original if args[1] == "getprop" else ""

    def stream(self):
        return self.process


class OverlayCaptureTest(unittest.TestCase):
    def test_rotation_keeps_only_three_bounded_files(self):
        with TemporaryDirectory() as tmp:
            writer = capture.BoundedLog(tmp, max_bytes=12)
            for index in range(8):
                writer.append(str(index) * 10)
            files = sorted(Path(tmp).glob("events.log*"))
            self.assertEqual(len(files), 3)
            self.assertTrue(all(path.stat().st_size <= 12 for path in files))
            self.assertEqual((Path(tmp) / "events.log.2").read_text(), "5" * 10 + "\n")

    def test_large_unicode_line_is_bounded_and_stays_valid_utf8(self):
        with TemporaryDirectory() as tmp:
            capture.BoundedLog(tmp, max_bytes=12).append("记录" * 20)
            data = (Path(tmp) / "events.log").read_bytes()
            self.assertLessEqual(len(data), 12)
            self.assertTrue(data.decode("utf-8").endswith("\n"))

    def run_capture(self, adb, **options):
        with TemporaryDirectory() as tmp, patch.object(capture.uuid, "uuid4") as unique:
            unique.return_value.hex = "current-session"
            result = capture.capture(adb, Path(tmp) / "capture", **options)
            saved = json.loads((Path(tmp) / "capture/summary.json").read_text(encoding="utf-8"))
            self.assertEqual(saved, result)
            return result

    def test_line_limit_stops_own_process_and_restores_original_level(self):
        adb = FakeAdb(b"event=capture_probe session=current-session\nevent=feedback_interrupt\n")
        result = self.run_capture(adb, max_lines=2)
        self.assertEqual(result["reason"], "line_limit")
        self.assertTrue(result["probeSeen"] and result["logLevelRestored"] and adb.process.terminated)
        self.assertEqual(adb.calls[-1], ("shell", "setprop", "log.tag.AhaoOverlayDiag", "INFO"))

    def test_old_probe_does_not_acknowledge_new_session(self):
        result = self.run_capture(FakeAdb(b"event=capture_probe session=old-session\n"))
        self.assertFalse(result["probeSeen"])
        self.assertEqual(result["reason"], "transport_closed")
        self.assertTrue(result["logLevelRestored"])

    def test_empty_original_property_is_restored_as_empty(self):
        adb = FakeAdb(b"", original="")
        self.run_capture(adb)
        self.assertEqual(adb.calls[-1][-1], '""')

    def test_expired_deadline_stops_capture_without_waiting_for_events(self):
        with patch.object(capture.time, "monotonic", side_effect=[10.0, 11.1]):
            result = self.run_capture(FakeAdb(b""), seconds=1)
        self.assertEqual(result["reason"], "duration_limit")
        self.assertTrue(result["logLevelRestored"])

    def test_original_level_is_saved_before_diagnostics_are_enabled(self):
        adb = FakeAdb(b"")
        original_call = adb.call
        with TemporaryDirectory() as tmp:
            directory = Path(tmp) / "capture"

            def inspect(*args):
                if args == ("shell", "setprop", "log.tag.AhaoOverlayDiag", "DEBUG"):
                    saved = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
                    self.assertEqual(saved["previousLogLevel"], "INFO")
                    self.assertEqual(saved["reason"], "starting")
                return original_call(*args)

            with patch.object(adb, "call", side_effect=inspect):
                capture.capture(adb, directory)

    def test_launch_error_still_restores_property_and_writes_summary(self):
        adb = FakeAdb(b"")
        with patch.object(adb, "stream", side_effect=RuntimeError("launch failed")):
            result = self.run_capture(adb)
        self.assertEqual(result["reason"], "capture_error")
        self.assertTrue(result["logLevelRestored"])

    def test_keyboard_interrupt_cleans_up_after_probe_was_saved(self):
        adb = FakeAdb(b"event=capture_probe session=current-session\n")
        with patch("builtins.print", side_effect=KeyboardInterrupt):
            result = self.run_capture(adb)
        self.assertEqual(result["reason"], "user_stopped")
        self.assertTrue(result["probeSeen"] and result["logLevelRestored"] and adb.process.terminated)

    def test_restore_error_remains_explicit_in_summary(self):
        adb = FakeAdb(b"event=capture_probe session=current-session\n")
        original_call = adb.call

        def disconnect(*args):
            if args == ("shell", "setprop", "log.tag.AhaoOverlayDiag", "INFO"):
                raise RuntimeError("device disconnected")
            return original_call(*args)

        with patch.object(adb, "call", side_effect=disconnect):
            result = self.run_capture(adb, max_lines=1)
        self.assertFalse(result["logLevelRestored"])
        self.assertIn("device disconnected", result["restoreError"])


if __name__ == "__main__":
    unittest.main()
