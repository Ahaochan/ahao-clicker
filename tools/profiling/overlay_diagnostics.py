"""Bounded, opt-in overlay-event capture. Uses Python's standard library only."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import uuid

TAG = "AhaoOverlayDiag"
ROOT = Path(__file__).resolve().parents[2]


class BoundedLog:
    def __init__(self, directory, max_bytes=1024 * 1024, backups=2):
        self.path = Path(directory) / "events.log"
        self.max_bytes = max_bytes
        self.backups = backups

    def append(self, line):
        data = (line.rstrip("\r\n") + "\n").encode("utf-8", errors="replace")
        if len(data) > self.max_bytes:
            data = data[:self.max_bytes - 1].decode("utf-8", errors="ignore").encode("utf-8") + b"\n"
        if self.path.exists() and self.path.stat().st_size + len(data) > self.max_bytes:
            for index in range(self.backups, 0, -1):
                destination = self.path.with_name(self.path.name + f".{index}")
                source = self.path if index == 1 else self.path.with_name(self.path.name + f".{index - 1}")
                if source.exists():
                    source.replace(destination)
        with self.path.open("ab") as output:
            output.write(data)


def adb_path(explicit=None):
    if explicit:
        return str(Path(explicit).resolve()) if Path(explicit).is_file() else (shutil.which(explicit) or explicit)
    if found := shutil.which("adb"):
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = ROOT / "local.properties"
    if not sdk and local.exists():
        match = re.search(r"^sdk\.dir=(.+)$", local.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            sdk = match[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if sdk:
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            return str(candidate)
    raise ValueError("ADB not found: set ANDROID_HOME or pass --adb /path/to/adb")


class Adb:
    def __init__(self, executable, serial):
        self.command = [executable, "-s", serial]

    def call(self, *args):
        return subprocess.run(self.command + list(args), check=True, capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=15).stdout.strip()

    def stream(self):
        return subprocess.Popen(self.command + ["logcat", "-v", "threadtime", "-T", "1", "-s", TAG + ":D", "*:S"],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def save_summary(directory, summary):
    temporary = Path(directory) / "summary.tmp"
    temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(Path(directory) / "summary.json")


def capture(adb, directory, seconds=1200, max_lines=4096):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    session = uuid.uuid4().hex
    summary = {"session": session, "startedUtc": datetime.now(timezone.utc).isoformat(),
               "maximumSeconds": seconds, "maximumLines": max_lines,
               "maximumFileBytes": 1024 * 1024, "maximumFiles": 3,
               "probeSeen": False, "lines": 0, "reason": "setup_error", "logLevelRestored": False}
    writer = BoundedLog(directory)
    previous = adb.call("shell", "getprop", "log.tag." + TAG)
    if previous and not re.fullmatch(r"[A-Za-z]+", previous):
        raise ValueError("Unexpected original log level; nothing changed")
    summary["previousLogLevel"] = previous
    summary["reason"] = "starting"
    save_summary(directory, summary)
    process = None
    try:
        adb.call("shell", "setprop", "log.tag." + TAG, "DEBUG")
        process = adb.stream()
        messages = queue.Queue(maxsize=128)

        def read():
            try:
                while line := process.stdout.readline(16 * 1024):
                    messages.put(line.decode("utf-8", errors="replace"))
            finally:
                messages.put(None)

        threading.Thread(target=read, daemon=True).start()
        started = time.monotonic()
        deadline = started + seconds
        next_probe = started
        probe = "event=capture_probe session=" + session
        while True:
            now = time.monotonic()
            if now >= deadline:
                summary["reason"] = "duration_limit"
                break
            if not summary["probeSeen"]:
                if now - started >= 5:
                    summary["reason"] = "probe_missing"
                    break
                if now >= next_probe:
                    adb.call("shell", "log", "-p", "d", "-t", TAG, probe)
                    next_probe = now + .5
            try:
                line = messages.get(timeout=min(.25, max(.001, deadline - now)))
            except queue.Empty:
                continue
            if line is None:
                summary["reason"] = "transport_closed"
                break
            writer.append(line)
            summary["lines"] += 1
            if not summary["probeSeen"] and probe in line:
                summary["probeSeen"] = True
                summary["reason"] = "capturing"
                save_summary(directory, summary)
                print(f"READY: {directory}\nProbe saved. Reproduce once; press Ctrl+C after it happens.", flush=True)
            if summary["lines"] >= max_lines:
                summary["reason"] = "line_limit"
                break
    except KeyboardInterrupt:
        summary["reason"] = "user_stopped"
    except Exception as error:
        summary["reason"] = "capture_error"
        summary["error"] = type(error).__name__ + ": " + str(error)
    finally:
        if process is not None:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        try:
            adb.call("shell", "setprop", "log.tag." + TAG, previous if previous else '""')
            summary["logLevelRestored"] = True
        except Exception as error:
            summary["restoreError"] = str(error)
        summary["stoppedUtc"] = datetime.now(timezone.utc).isoformat()
        save_summary(directory, summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--adb", help="ADB executable; otherwise use PATH, SDK environment or local.properties")
    parser.add_argument("--seconds", type=int, default=1200, help="Capture duration, 1..1200 seconds")
    parser.add_argument("--max-lines", type=int, default=4096, help="Maximum output lines, 1..4096")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial) or not 1 <= args.seconds <= 1200 or not 1 <= args.max_lines <= 4096:
        parser.error("Invalid serial or capture limits")
    adb = Adb(adb_path(args.adb), args.serial)
    if adb.call("get-state") != "device":
        parser.error("Device must be connected and authorized")
    folder = ROOT / ".tools" / "overlay-diagnostics" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    result = capture(adb, folder, args.seconds, args.max_lines)
    print(json.dumps({"directory": str(folder), **result}, ensure_ascii=False))
    return 0 if result["probeSeen"] and result["logLevelRestored"] and result["reason"] in {
        "user_stopped", "duration_limit", "line_limit"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
