# TunerOS beginner guide (early alpha)

TunerOS is a television app being built for Android TV boxes and Android phones.

## What works in the early alpha source

1. Open the application on a compatible Android device (after a successful APK build and install). Use **Show sources** to reveal the playlist/EPG fields if they are hidden; **Hide sources** returns more screen space to the guide.
2. Paste the URL of a playlist you are authorized to watch into **M3U playlist URL**.
3. Choose **Load channels**. The channel list appears when import succeeds.
4. Optionally paste an XMLTV program guide URL and choose **Load guide**.
5. Search for channels by typing part of their names.
6. Turn **Hide channels with no EPG** on or off to suit your preference.
7. Select a channel on the TV remote (OK) or touch it to attempt playback.
8. To record the program currently under guide focus, choose **Record focused program**. A current program starts immediately; a future program is stored as a schedule.
9. On Android 12 and later, choose **Exact DVR timing** and allow **Alarms & reminders** before relying on a future scheduled recording. TunerOS keeps an unarmed schedule if this access is unavailable and re-arms pending schedules after access is granted or after reboot.
10. Choose **Stop recording** to cancel the active recording. Choose **Guide / Recordings** to switch the same recycled list between the guide and the most recent recordings; completed recordings can be selected for local playback.
11. To remove a future job before it starts, focus that same future program and choose **Cancel scheduled recording**.
12. In **Recordings**, focus an item and choose **Protect / Unprotect** to guard it from deletion. Choose **Delete recording** to remove an unprotected, inactive recording and its catalog row. TunerOS validates the recording location before playback or deletion.
13. Choose **Choose recording storage** to select writable local shared, SD-card, or USB storage through Android's folder picker. TunerOS requires persisted read/write access and a provider-reported free-space value with at least the 512 MiB safety reserve. If the selected drive is unavailable, a new recording fails rather than silently switching destinations. Choose **Use internal recording storage** for future recordings to return to TunerOS app storage; existing external recordings keep their persisted access grant.

**DVR format limits in this alpha:** Raw/progressive HTTP(S) streams and unencrypted MPEG-TS HLS are implemented in source. HLS master playlists, media sequences, byte ranges and provider User-Agent/Referer headers are handled. TunerOS deliberately refuses encrypted HLS, fMP4 HLS using `EXT-X-MAP`, HLS variants that require separate audio/video rendition capture, and DASH recording until those paths can be written without corrupt or incomplete output. A missing live HLS sequence is treated as a partial recording rather than silently skipped. Real-device recording remains unverified.

**Privacy and import notes:** Playlist and guide URLs may contain account credentials. TunerOS now attempts to encrypt saved URLs using Android Keystore; if secure saving fails, the URL is used for the current import without being saved. Prefer HTTPS sources. If XMLTV times omit a timezone, TunerOS assumes UTC and displays a warning; contact the provider if listings are shifted. If an import exceeds channel/programme caps, TunerOS displays a truncation warning.

**Important:** This is alpha code. These steps describe the intended interface; a debug APK has been compiled in GitHub Actions, but real-device installation has not been verified yet. TunerOS does not ship paid TV sources or provider credentials.

## How to build

Install Android SDK API 35, JDK 17 and Gradle 8.9. From the repository root run `gradle :core:test :app:assembleDebug`. If successful, the debug installer will be under `app/build/outputs/apk/debug/`.

For problems, save the build errors and Android device logs with timestamps. Avoid putting private playlist URLs, tokens or passwords into publicly shared logs.


## Windows build shortcut (experimental)

If you have JDK 17 and Android SDK API 35 installed, open PowerShell in the TunerOS repository and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

The script downloads Gradle 8.9 to your user profile if it is missing, checks the official distribution SHA-256, and runs the tests and debug APK build. This helper has **not yet been tested on a Windows computer**. You can also install Gradle 8.9 manually and run `gradle --no-daemon :core:test :app:assembleDebug`.


**External-storage status:** The SAF/USB/SD path is implemented in source and passes automated APK build/tests, but physical media insertion/removal/reinsertion and real recording playback on Android hardware are still unverified.

## TV remote and compact controls (source update 2026-11-11)

The DVR buttons are in one horizontally scrollable action row, rather than occupying nine vertical rows above the guide. Move focus left/right to reach off-screen actions. The main guide uses recycled rows; selecting a channel attempts playback. Android TV remote/focus and portrait-screen checks are **NOT RUN**. The current guide UI does not display XMLTV programme descriptions, so imports now omit those strings; channel names, programme titles, start/end times and scheduled-program focus remain available. This is a code-level optimization, not a measured device memory claim.

**Recording integrity:** For HLS byte-range streams, a short or oversized segment response is treated as a partial recording instead of silently accepting a damaged segment. This path is covered by JVM tests, but live provider/USB playback remains UNVERIFIED.


## Stream Doctor (early alpha)

If a channel or recording fails to play, TunerOS shows a **Stream Doctor** message with a numeric playback code, a short explanation and a recommended next check. Common cases include a network timeout, rejected provider access, unsupported formats, or a missing recording. You can report the **numeric code** when asking for help; do not share your provider URL, credentials, request headers or token. This screen does not send diagnostic information to a server. The initial decision table is unit-tested; live device accuracy and recovery are **NOT RUN**.


**Full channel guide:** The virtualized channel list no longer hides everything after channel 500. All successfully imported channels (up to the parser's 25,000-channel safety cap) can be browsed, searched and optionally filtered by EPG. Filters are cached between clock updates and timeline shifts to avoid repeatedly scanning a large playlist. This code has automated JVM coverage; scrolling a full guide on a physical Android TV remains **NOT RUN**.

**Import memory safeguards:** Extremely long XMLTV titles and channel names are limited to 256 characters, and optional descriptions to 2,048 characters. TunerOS reports a warning when a field is shortened. The current Android guide omits descriptions entirely to reduce memory use. These limits are validated with JVM tests; device heap consumption remains unmeasured.
