# Mine Study Android

Native Android client for the Mine Study learning library, built with Kotlin, Jetpack Compose and ExoPlayer (not a WebView).

## Features
- Server 1 / Server 2 batch selection
- Subjects → chapters → lessons navigation with Android Back support
- Lectures, Notes, DPP notes and DPP videos
- Play action for lectures and DPP videos
- Open PDF action for Notes and DPP
- Per-item download action and a player-screen download action
- API key generation link, clipboard copy, verification, save and clear controls
- API keys expire locally after 24 hours
- CDN chunk-range adapter for playback and downloads (`?s=start&e=end`)
- Downloads saved to `Downloads/Mine Study` on Android 10+ (app-specific Downloads on older Android versions)

## Build
1. Clone this repository and open its root in Android Studio.
2. Use JDK 17 and allow Gradle sync to complete.
3. Run the app on an Android device or emulator.

GitHub Actions builds a debug APK on every push to `main`. Open the repository's **Actions** tab, select the latest **Build Android APK** run, and download the `mine-study-debug-apk` artifact.

## API configuration
API base: `https://v1.sgmvv1.xyz/api/v1`

The app sends the user-provided API key in the `x-api-key` request header. No API key is hard-coded. Key generation uses `/auth/generate?server=1`, matching the website's behavior; after opening the generated link, obtain the actual API key and paste it into Settings, then choose **Verify API key**.

Batch IDs:
- Server 1: `6a0eb0a7f33c042849418636`
- Server 2: `6a0eaf70f4ae45d1901b7196`

## Verification notes
The GitHub Actions debug build is checked on each push. A successful build confirms compilation and APK packaging; it does not replace testing on a physical Android device with a valid API key. Live API responses, actual playback, and completed downloads still need device-level confirmation.