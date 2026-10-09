# Architecture

## Build

The app is built from `TMessagesProj_App` only. The Huawei, HockeyApp and Standalone modules stay in the tree for upstream merges and are not built; they still reference Telegram's own package and Firebase config and do not build with the fork's settings.

### Identity

- Package `org.unofficial.telegramfeed` (`APP_PACKAGE` in `gradle.properties`), the same for debug and release builds: the upstream `.beta` suffix of debug builds is removed in `TMessagesProj_App/build.gradle`. The Java namespace stays `org.telegram.messenger`, so class names in manifests and `LauncherIconController` are unchanged.
- App name "Unofficial Telegram Feed" (`AppName` in every `values*/strings.xml`; the manifests of `TMessagesProj/config` label the application with it) and launcher label "TG Feed" (`AppLauncherLabel`, set on the launcher activity aliases). Inside the app, Telegram's cloud language packs (`BuildVars.USE_CLOUD_STRINGS`) override `AppName` at run time with "Telegram"; the manifest labels are not affected.
- Launcher icon: the Flutter app's icon (`telegram-feed/docs/icon.png`) as `ic_launcher` and `ic_launcher_round` in every density, as the adaptive icon (`drawable/tgfeed_icon_background.xml` and `tgfeed_icon_foreground.xml`, copied from the Flutter app's launcher drawables), and as `drawable/ic_launcher_dr` (the icon of notifications' persons and of the sync account). The alternative icons of Settings > Appearance (`icon_2` to `icon_6`) are Telegram's.
- The contacts sync account type is the package (`@string/tgfeed_account_type`, a `resValue` of the app module, defaulted in `values/tgfeed.xml`), so the fork and the official app install side by side.
- `BuildVars.SUPPORTS_PASSKEYS` is off; passkeys work only with Telegram's own app ids.

### Secrets

Nothing secret is in the repo. A directory outside it, named by the gradle property `TGFEED_SECRETS_DIR` (relative to the repo root; default `../secrets`, or `../telegram-feed/secrets` when `../secrets` does not exist), holds:

- `telegram.json` with `TG_API_ID` and `TG_API_HASH`, the file the Flutter app uses. `TMessagesProj/build.gradle` reads it into `BuildConfig.APP_ID` and `BuildConfig.APP_HASH`, which `BuildVars` exposes.
- `google-services.tgfeed.json`, the `google-services.json` of the Firebase project `tg-feed-a0fce` with an Android client for the package `org.unofficial.telegramfeed`. The google-services gradle plugin is not applied; `TMessagesProj_App/build.gradle` reads the file and sets the string resources the plugin would generate (`google_app_id`, `gcm_defaultSenderId`, `google_api_key`, `project_id` and the rest). The build fails when the file is missing or has no client for the package.

The root `build.gradle` holds the directory lookup (`tgfeedSecretsDir`) and the reader (`tgfeedReadSecretJson`).

### Requirements on Windows

- Android SDK at `C:\Users\<user>\AppData\Local\Android\Sdk` (`ANDROID_HOME`), with platform 36, build-tools 36.0.0, CMake 3.22.1 and NDK 27.2.12479018 (`TMessagesProj/build.gradle` pins the NDK; install it with `sdkmanager "ndk;27.2.12479018"`, which itself needs `JAVA_HOME` set to a Java 17 or newer).
- JDK 17 as `JAVA_HOME`. Gradle 8.13 (the wrapper) does not run on Java 24 or newer: Android Studio's bundled JBR is Java 25 and fails with "Unsupported class file major version 69". The Temurin 17 that Gradle provisioned for the Flutter app, at `C:\Users\<user>\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2`, works.
- No `local.properties` is needed when `ANDROID_HOME` is set.

### Debug APK

```bash
export JAVA_HOME="C:/Users/<user>/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
export ANDROID_HOME="C:/Users/<user>/AppData/Local/Android/Sdk"
./gradlew :TMessagesProj_App:assembleAfatDebug
```

The APK is `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk` (about 107 MB, all four ABIs). The `afat` flavour is the only one the debug build type accepts (`variantFilter` in `TMessagesProj_App/build.gradle`).

The native part compiles only Telegram's own C++ (`tmessages.49`, about 1730 object files per ABI); ffmpeg, BoringSSL, dav1d, libvpx, opus, tde2e, tlottie and wamr are prebuilt static libraries under `TMessagesProj/jni/prebuild/lib/<abi>`. A clean build of all four ABIs takes 28 minutes on the development machine; Gradle's daemon needs the 8 GB heap set in `gradle.properties`.

### Emulator

Manual runs use the `Pixel_10` AVD (Android 16, `google_apis`, x86_64):

```bash
emulator -avd Pixel_10
adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
adb shell monkey -p org.unofficial.telegramfeed -c android.intent.category.LAUNCHER 1
```

Telegram login on the emulator uses a spare real account on the production DC; the owner of that account types the phone number and code.
