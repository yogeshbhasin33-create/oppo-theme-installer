# OPPO Theme Installer (starter Android project)

## What works
- Native Android app built with Kotlin + Jetpack Compose.
- Selects a local file using Android's Storage Access Framework.
- Copies the selected archive into app-private storage.
- Safely inspects ZIP entries, validates paths and size limits, parses `themeInfo.xml` with external XML entities/DOCTYPE disabled.
- Extracts and previews an image under `picture/`.
- Detects the `lockscreen/lockstyle` entry.
- Can set the extracted preview image as the lock-screen wallpaper after the user taps the button.
- Offers a best-effort launcher for an installed Theme Store app.

## Important limitation
This app does **not** install OPPO's proprietary `.theme`/`lockstyle` package. No verified public Android/ColorOS API for importing arbitrary third-party `.theme` archives was identified. Setting the preview image as the lock-screen wallpaper is only a still wallpaper change; it does not install the lock-screen layout, clock, animations, or other proprietary theme behavior. The app does not copy files into protected Theme Store directories or claim false installation success.

OPPO support information says theme availability/compatibility varies by model and ColorOS version, and notes that copied local theme files may not be usable in newer Theme Store versions. See:
- https://support.oppo.com/ng/answer/?aid=neu10882
- https://oppo-parts.trackit.co.in/support/unable-to-apply-theme-in-the-theme-store-if-copy-it-to-the-phone-folder-directly
- https://oppo-parts.trackit.co.in/support/unable-to-apply-a-theme-in-theme-store

## Build
1. Install Android Studio (current stable) and Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Let Gradle sync. Android Studio may download the Gradle wrapper/build dependencies automatically; if prompted, accept.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

JDK 17 is required. This environment generated source files but did not run Android Gradle or test on a physical OPPO device.

## Test the sample
Copy `Horse_LockScreen_Only.theme` to the phone. Open the app, tap Import .theme file, and select it. Confirm that the app displays Horse Lock, component `lockscreen`, the listed resolutions and its preview. Test the wallpaper button only if you want the preview image applied to the lock screen.

## Build an APK without installing Android Studio locally (GitHub Actions)
This project includes `.github/workflows/build-apk.yml`.
1. Create a GitHub repository and upload the extracted project contents (including the hidden `.github` folder).
2. In the repository, open **Actions** and enable workflows if prompted.
3. Run **Build OPPO Theme Installer APK** using **Run workflow**, or push to the `main` branch.
4. When the workflow completes, open its run and download the `oppo-theme-installer-debug-apk` artifact.
5. Extract the downloaded artifact to obtain `app-debug.apk`.

The workflow builds the project on GitHub's hosted Ubuntu runner with JDK 17, Android SDK and Gradle 8.9. This has not been run or verified yet.
