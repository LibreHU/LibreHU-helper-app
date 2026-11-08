#!/usr/bin/env python3
"""Runs LibreHU Helper's report on an emulator (CI: .github/workflows/report-test.yml) and keeps what explains a
failure without the head unit: logcat (stack traces of a crash), screenshots and UI trees, the report's file list and
its manifest. Two runs: « Export to the app's folder », then « Choose the folder and export » (DocumentsUI).

    python3 tools/report_test.py helper.apk out
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

PKG = "org.librehu.helper"
APK, OUT = sys.argv[1], sys.argv[2]
DIAG = f"/sdcard/Android/data/{PKG}/files/diag"


def adb(*args, timeout=180):
    try:
        r = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        print("timeout:", args, flush=True)
        return ""
    return r.stdout + r.stderr


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def nodes(xml):
    try:
        root = ET.fromstring(xml[xml.find("<?xml"):] if "<?xml" in xml else xml)
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def find(xml, pattern):
    rx = re.compile(pattern, re.I)
    for n in nodes(xml):
        if rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")):
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                return (x1 + x2) // 2, (y1 + y2) // 2, n.get("text", "") or n.get("content-desc", "")
    return None


def texts(xml):
    return [n.get("text") for n in nodes(xml) if n.get("text")]


def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    xml = dump()
    with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    return xml


def tap(pattern, scrolls=10):
    """Taps the first node matching [pattern], scrolling the page down until it shows."""
    for _ in range(scrolls):
        p = find(dump(), pattern)
        if p:
            print("tap", repr(p[2]), flush=True)
            adb("shell", "input", "tap", str(p[0]), str(p[1]))
            return True
        adb("shell", "input", "swipe", "900", "620", "900", "220", "400")
        time.sleep(1.5)
    print("not found:", pattern, flush=True)
    return False


def alive():
    return bool(adb("shell", "pidof", PKG).strip())


def wait_report(name, limit_s=1200):
    """Until the report says Done / Failed, the app dies, or an « app has stopped » dialog shows."""
    t0 = time.time()
    n = 0
    while time.time() - t0 < limit_s:
        time.sleep(5)
        if not alive():
            return "CRASH: process gone"
        xml = dump()
        crash = find(xml, r"has stopped|keeps stopping|isn't responding|s'est arrêtée|ne répond pas")
        if crash:
            shot(f"{name}-dialog")
            return "CRASH / ANR dialog: " + crash[2]
        for t in texts(xml):
            if t.startswith(("Done:", "Failed:")):
                return t
        if int(time.time() - t0) // 60 > n:
            n += 1
            shot(f"{name}-{n}min")
    return "still running after %d s" % limit_s


def save_logcat(name):
    with open(os.path.join(OUT, f"logcat-{name}.txt"), "w", encoding="utf-8") as f:
        f.write(adb("logcat", "-d", "-v", "threadtime", "-b", "all", timeout=300))
    crash = adb("logcat", "-d", "-v", "threadtime", "-b", "crash")
    with open(os.path.join(OUT, f"crash-{name}.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    return crash


def start_app():
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    time.sleep(12)


def run_app_folder(results):
    start_app()
    shot("1-start")
    tap(r"^Export to the app")
    time.sleep(2)
    shot("1-started")
    results["app folder"] = wait_report("1")
    shot("1-end")
    crash = save_logcat("app-folder")
    if crash.strip():
        results["app folder"] += " | crash buffer not empty (crash-app-folder.txt)"


def run_picker(results):
    start_app()
    if not tap(r"^Choose the folder"):
        results["folder picker"] = "button not found"
        return
    time.sleep(4)
    shot("2-picker")
    # DocumentsUI (Android 9): open the device's storage from the roots drawer, then « SELECT ».
    if not find(dump(), r"^SELECT$|^Select$|^USE THIS FOLDER$|^Use this folder$"):
        tap(r"Show roots|^Open navigation", scrolls=1)
        time.sleep(2)
        shot("2-roots")
        tap(r"Android SDK built|Internal storage|^SDCARD$|Download", scrolls=1)
        time.sleep(3)
        shot("2-storage")
    tap(r"^SELECT$|^Select$|^USE THIS FOLDER$|^Use this folder$", scrolls=1)
    time.sleep(2)
    tap(r"^ALLOW$|^Allow$", scrolls=1)
    time.sleep(2)
    shot("2-started")
    results["folder picker"] = wait_report("2")
    shot("2-end")
    crash = save_logcat("folder-picker")
    if crash.strip():
        results["folder picker"] += " | crash buffer not empty (crash-folder-picker.txt)"


def keep_reports():
    listing = adb("shell", "ls", "-l", DIAG)
    with open(os.path.join(OUT, "diag-listing.txt"), "w") as f:
        f.write(listing)
    for name in re.findall(r"(LibreHU-diag-\S+\.zip)", listing):
        local = os.path.join(OUT, name)
        adb("pull", f"{DIAG}/{name}", local)
        try:
            z = zipfile.ZipFile(local)
            with open(local + ".manifest.json", "wb") as f:
                f.write(z.read("manifest.json"))
            with open(local + ".list.txt", "w") as f:
                f.write("\n".join(f"{i.file_size:>10}  {i.filename}" for i in z.infolist()))
        except Exception as e:  # noqa: BLE001 (a broken zip is a result too)
            print("zip:", name, e, flush=True)
        os.remove(local)


def main():
    os.makedirs(OUT, exist_ok=True)
    adb("wait-for-device")
    for _ in range(120):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(2)
    adb("shell", "wm", "size", "1280x720")
    adb("shell", "wm", "density", "160")
    adb("shell", "input", "keyevent", "82")
    print(adb("install", "-r", "-g", APK), flush=True)
    with open(os.path.join(OUT, "device.txt"), "w") as f:
        f.write(adb("shell", "getprop"))
    results = {}
    run_app_folder(results)
    run_picker(results)
    keep_reports()
    summary = "\n".join(f"{k}: {v}" for k, v in results.items())
    print(summary, flush=True)
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        f.write(summary + "\n")
    with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a") as f:
        f.write("## Report on Android 9\n\n```\n" + summary + "\n```\n")


if __name__ == "__main__":
    main()
