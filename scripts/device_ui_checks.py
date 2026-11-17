#!/usr/bin/env python3
"""Drives TunerOS on the emulator the way a person would: taps, typed PIN digits and relaunches, with each screen read
back through uiautomator. Each check writes one PASS or FAIL line to the report. A failed check also saves the screen it
failed on to the dump folder. Run by scripts/emulator-self-test.sh after the app's own checks.

Usage: device_ui_checks.py REPORT DUMP_DIR
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.devtunasoft.tuneros"
ACTIVITY = PACKAGE + "/.MainActivity"
PLAYLIST_URL = "http://10.0.2.2:8790/ui-playlist.m3u"
DUMP_FILE = "/sdcard/tuneros-ui.xml"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
KEYCODE_BACK = "4"
KEYCODE_WAKEUP = "224"

REPORT, DUMP_DIR = sys.argv[1], sys.argv[2]
WIDTH, HEIGHT = 1080, 1920
results = []


class Stop(Exception):
    """A check that later checks depend on failed; the run ends after recording it."""


def adb(*args):
    return subprocess.run(["adb", "shell", *args], capture_output=True, text=True, timeout=120)


def record(check, passed, detail):
    results.append(passed)
    line = ("PASS" if passed else "FAIL") + "\t" + check + "\t" + detail
    print(line, flush=True)
    with open(REPORT, "a", encoding="utf-8") as report:
        report.write(line + "\n")
    if not passed:
        try:
            dump("failed-" + check)
        except RuntimeError:
            pass


def require(node, check, detail):
    if node is None:
        record(check, False, detail)
        raise Stop()
    return node


def device_size():
    global WIDTH, HEIGHT
    match = re.search(r"(\d+)x(\d+)", adb("wm", "size").stdout)
    if match:
        WIDTH, HEIGHT = int(match.group(1)), int(match.group(2))


def dump(label=None):
    for _ in range(4):
        adb("uiautomator", "dump", DUMP_FILE)
        text = adb("cat", DUMP_FILE).stdout
        try:
            root = ET.fromstring(text)
        except ET.ParseError:
            time.sleep(1)
            continue
        if label:
            with open(DUMP_DIR + "/" + label + ".xml", "w", encoding="utf-8") as saved:
                saved.write(text)
        return root
    raise RuntimeError("uiautomator did not return a readable screen")


def nodes(root):
    for element in root.iter("node"):
        match = BOUNDS.fullmatch(element.get("bounds", ""))
        if match:
            yield {
                "text": element.get("text", ""),
                "desc": element.get("content-desc", ""),
                "class": element.get("class", ""),
                "clickable": element.get("clickable") == "true",
                "rect": tuple(int(value) for value in match.groups()),
            }


def centre_on_screen(rect):
    x1, y1, x2, y2 = rect
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    return x2 > x1 and y2 > y1 and 0 <= cx < WIDTH and 0 <= cy < HEIGHT


def find(root, label, clickable=None):
    wanted = label.lower()
    for node in nodes(root):
        if wanted not in (node["text"].lower(), node["desc"].lower()):
            continue
        if clickable is not None and node["clickable"] != clickable:
            continue
        if centre_on_screen(node["rect"]):
            return node
    return None


def dialog_field(root, submit_label):
    """The field of the dialog that holds the button: the text field closest above that button."""
    submit = find(root, submit_label, clickable=True)
    if submit is None:
        return None, None
    submit_top = submit["rect"][1]
    field = None
    for node in nodes(root):
        if not node["class"].endswith("EditText") or not centre_on_screen(node["rect"]):
            continue
        if node["rect"][3] <= submit_top and (field is None or node["rect"][3] > field["rect"][3]):
            field = node
    return submit, field


def tap(node):
    x1, y1, x2, y2 = node["rect"]
    adb("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def hide_keyboard():
    state = adb("dumpsys", "input_method").stdout
    if "mInputShown=true" in state or "mIsInputViewShown=true" in state:
        adb("input", "keyevent", KEYCODE_BACK)
        time.sleep(1)


def type_into(field, value):
    tap(field)
    time.sleep(1)
    adb("input", "text", value)
    time.sleep(1)
    hide_keyboard()


def wait_for(label, timeout, clickable=None):
    deadline = time.time() + timeout
    while True:
        root = dump()
        node = find(root, label, clickable)
        if node is not None or time.time() > deadline:
            return node, root
        time.sleep(2)


def scroll_to(label, clickable=None, tries=8):
    for _ in range(tries):
        node = find(dump(), label, clickable)
        if node is not None:
            return node
        adb("input", "swipe", str(WIDTH // 2), str(int(HEIGHT * 0.7)), str(WIDTH // 2), str(int(HEIGHT * 0.35)), "300")
        time.sleep(1)
    return None


def start(extras=()):
    adb("am", "force-stop", PACKAGE)
    adb("am", "start", "-n", ACTIVITY, *extras)
    time.sleep(3)


def enter_pin(pin):
    submit, field = dialog_field(dump(), "Unlock")
    require(field, "ui-pin-prompt", "the PIN prompt has no field")
    type_into(field, pin)
    submit, _ = dialog_field(dump(), "Unlock")
    tap(require(submit, "ui-pin-prompt", "the Unlock button is not on screen"))


def scenario():
    adb("input", "keyevent", KEYCODE_WAKEUP)
    adb("wm", "dismiss-keyguard")
    adb("cmd", "statusbar", "collapse")
    adb("pm", "clear", PACKAGE)
    start(["--es", "tuneros.debug.import_playlist_url", PLAYLIST_URL])
    node, _ = wait_for("News Two", 150)
    require(node, "ui-channels-loaded", "the fixture channels did not appear on the channel list")
    record("ui-channels-loaded", True, "the fixture channels appear on the channel list")

    # Set a PIN, then restrict the Adult group, both from the app's own dialogs.
    tap(require(scroll_to("Parental PIN"), "ui-pin-dialog", "the Parental PIN button is not on screen"))
    submit, field = dialog_field(dump(), "Save")
    require(field, "ui-pin-dialog", "the set-PIN dialog did not open with a field")
    type_into(field, "1234")
    submit, _ = dialog_field(dump(), "Save")
    tap(require(submit, "ui-pin-dialog", "the set-PIN Save button is not on screen"))
    node, _ = wait_for("Parental PIN saved: restricted groups need it", 30)
    record("ui-pin-saved", node is not None, "a 4-digit PIN is saved from the app's dialog")

    tap(require(scroll_to("Restrict group"), "ui-restrict-dialog", "the Restrict group button is not on screen"))
    submit, field = dialog_field(dump(), "Save")
    require(field, "ui-restrict-dialog", "the restrict dialog did not open with a field")
    type_into(field, "Adult")
    submit, _ = dialog_field(dump(), "Save")
    tap(require(submit, "ui-restrict-dialog", "the restrict Save button is not on screen"))
    node, _ = wait_for("Group restricted", 30)
    record("ui-group-restricted", node is not None, "the Adult group is restricted from the app's dialog")

    # A new process starts with the PIN session locked.
    start()
    node, _ = wait_for("News Two", 150)
    require(node, "ui-relaunch", "the channel list did not come back after a relaunch")

    tap(require(scroll_to("News Two"), "ui-unrestricted-plays", "the News Two row is not on screen"))
    node, root = wait_for("Playing: News Two", 150)
    prompted = find(root, "Unlock") is not None
    record("ui-unrestricted-plays", node is not None and not prompted,
           "an unrestricted channel plays without asking for the PIN")

    tap(require(scroll_to("Adult One"), "ui-restricted-asks", "the Adult One row is not on screen"))
    node, _ = wait_for("Unlock", 20)
    record("ui-restricted-asks-for-pin", node is not None, "a channel in the restricted group asks for the PIN")
    require(node, "ui-restricted-asks", "no PIN prompt appeared for the restricted channel")
    enter_pin("0000")
    node, root = wait_for("Wrong PIN: 4 tries left", 30)
    refused = node is not None and find(root, "Playing: Adult One") is None
    record("ui-wrong-pin-refused", refused, "a wrong PIN shows the tries left and the channel does not play")

    tap(require(scroll_to("Adult One"), "ui-right-pin", "the Adult One row is not on screen after a wrong PIN"))
    node, _ = wait_for("Unlock", 20)
    require(node, "ui-right-pin", "the PIN prompt did not come back for the second try")
    enter_pin("1234")
    node, _ = wait_for("Playing: Adult One", 150)
    record("ui-right-pin-plays", node is not None, "the right PIN starts the restricted channel")


def main():
    device_size()
    try:
        scenario()
    except Stop:
        pass
    finally:
        adb("am", "force-stop", PACKAGE)
    sys.exit(0 if results and all(results) else 1)


if __name__ == "__main__":
    main()
