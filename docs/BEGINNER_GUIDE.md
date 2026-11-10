# TunerOS beginner guide (early alpha)

TunerOS is a television app being built for Android TV boxes and Android phones.

## What works in the early alpha source

1. Open the application on a compatible Android device (after a successful APK build and install).
2. Paste the URL of a playlist you are authorized to watch into **M3U playlist URL**.
3. Choose **Load channels**. The channel list appears when import succeeds.
4. Optionally paste an XMLTV program guide URL and choose **Load guide**.
5. Search for channels by typing part of their names.
6. Turn **Hide channels with no EPG** on or off to suit your preference.
7. Select a channel on the TV remote (OK) or touch it to attempt playback.

**Privacy and import notes:** Playlist and guide URLs may contain account credentials. TunerOS now attempts to encrypt saved URLs using Android Keystore; if secure saving fails, the URL is used for the current import without being saved. Prefer HTTPS sources. If XMLTV times omit a timezone, TunerOS assumes UTC and displays a warning; contact the provider if listings are shifted. If an import exceeds channel/programme caps, TunerOS displays a truncation warning.

**Important:** This is alpha code. These steps describe the intended interface; a debug APK has been compiled in GitHub Actions, but real-device installation has not been verified yet. TunerOS does not ship paid TV sources or provider credentials.

## How to build

Install Android SDK API 35, JDK 17 and Gradle 8.9. From the repository root run `gradle :core:test :app:assembleDebug`. If successful, the debug installer will be under `app/build/outputs/apk/debug/`.

For problems, save the build errors and Android device logs with timestamps. Avoid putting private playlist URLs, tokens or passwords into publicly shared logs.
