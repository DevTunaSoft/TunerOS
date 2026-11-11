# TunerOS — User manual (early alpha)

## What the app currently does

TunerOS is a native Android TV/phone IPTV player with M3U playlist import, XMLTV programme guide, cable-style timeline, Media3 playback, and an experimental recording library. It does not bundle channels. Supply only authorized source URLs.

## First launch

1. Open TunerOS. It automatically runs a small, unattended, offline device self-test in the background. **No action is required to start tests.**
2. If you have authorized playlist and guide URLs, enter them in the source fields and choose `Load channels` and `Load guide`. The import and self-check outcomes are recorded automatically.
3. Browse the guide with your remote or touch screen, use the search field, and enable `Hide channels with no EPG` if desired.
4. Select a channel to play. The app records numeric readiness/error observations locally, without including source URLs.
5. The horizontally scrolling DVR toolbar contains recording, scheduling, recording-library, storage and self-test commands. Use `Guide / Recordings` to switch views.

## Autonomous diagnostics

The `Autonomous QA` line below the player status summarizes the latest completed checks. In the horizontally scrolling actions row choose `Self-test report`. You may read the complete result, `Run again` (optional), or `Share report` with your explicit approval.

Status meanings: **PASS** = an actual assertion succeeded; **FAIL** = an assertion failed; **BLOCKED** = an Android permission/device prerequisite prevents a check; **NOT_RUN** = a source, scenario or automated test implementation is not available. `NOT_RUN` is not a proof of failure or success. Synthetic self-tests never start provider playback or a real recording.

The on-device report is private until explicitly shared. Do not paste an M3U/XMLTV URL containing login credentials into a support message. Diagnostic reports intentionally omit those URLs. Exact-alarm access may require a device setting to use scheduled DVR; an automated check cannot grant it.

## Current limitations

The player and DVR remain alpha quality. Remote focus behavior, all provider stream formats, true recording recovery, disconnected removable storage, and low-memory stress behavior have **not** passed autonomous hardware acceptance. See [the QA coverage ledger](ON_DEVICE_QA.md).

## Testing behavior (debug vs. normal installs)

Normal TunerOS installations automatically run short, safe offline checks without interrupting playback or changing focus. The larger guide stress and interface focusability tests run only in an explicitly launched **debug/CI** build and are not part of normal viewing. The local report records whether each check actually ran; `INCOMPLETE` means the app still lacks evidence for one or more release scenarios. The report's share option is optional, not a required testing step.

## Android TV remote navigation

In the Classic Cable programme guide, use **Left** and **Right** to move between programmes. When you reach the beginning or end of the visible timeline, pressing the same direction shifts the time window by 30 minutes. The highlighted programme is chosen near the previous selection in the direction you pressed, without building additional programme views. Press **OK / Enter** to play the selected channel. A channel name itself also provides a selectable target where the programme guide has no schedule. **This navigation update is in source; physical-device verification is pending.**

## Empty channels and missing schedules

A channel without an EPG listing remains navigable in the cable guide. If a selected time window contains only empty gaps, use **Left** and **Right** to continue moving through time. Empty gaps do not launch playback when pressing OK. To hide channels with no schedule at all, use the optional **Hide channels with no EPG** setting. The new empty-guide navigation handling is implemented in source; Android device acceptance is still pending.

## Restart and recovery of sources

After a successful playlist or guide import, TunerOS keeps the validated source address in its encrypted source store. When Android terminates TunerOS and the app is reopened, the app attempts to fetch those sources again in the background without requiring you to re-enter the addresses. When Android supplies a saved activity state, the app also restores the search text (up to 256 characters) and guide time position. Cold launches without saved activity state start with a fresh search/time position. Large channel and programme lists and private source URLs are never copied into Android's activity state bundle.

If a new source is empty or fails to download or parse, TunerOS retains the previously saved address rather than replacing it. An explicitly requested new import takes precedence over automatic recovery. Internet access is still required for reloading provider feeds after a process restart. Real-device recovery is **not yet verified**; this is early-alpha behavior.

## Current-time indicator and power use

While the guide is open, the current-time line moves as the clock advances. TunerOS updates the visible time markers rather than reloading channel rows each minute. The minute refresh is inactive when the app is stopped, no programme guide has been loaded, or the recording library is displayed. This behavior is implemented in source and has a debug-only self-test; device verification remains pending.
