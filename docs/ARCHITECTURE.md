# Architecture

## Build

The app is built from `TMessagesProj_App` only. The Huawei, HockeyApp and Standalone modules stay in the tree for upstream merges and are not built.

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

The APK is `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk` (about 107 MB, all four ABIs). The `afat` flavour is the only one the debug build type accepts (`variantFilter` in `TMessagesProj_App/build.gradle`). The debug manifest (`TMessagesProj/config/debug/AndroidManifest_SDK23.xml`) gives the package the suffix `.beta`.

The native part compiles only Telegram's own C++ (`tmessages.49`, about 1730 object files per ABI); ffmpeg, BoringSSL, dav1d, libvpx, opus, tde2e, tlottie and wamr are prebuilt static libraries under `TMessagesProj/jni/prebuild/lib/<abi>`. A clean build of all four ABIs takes 28 minutes on the development machine; Gradle's daemon needs the 8 GB heap set in `gradle.properties`.

### Emulator

Manual runs use the `Pixel_10` AVD (Android 16, `google_apis`, x86_64):

```bash
emulator -avd Pixel_10
adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
adb shell monkey -p org.telegram.messenger.beta -c android.intent.category.LAUNCHER 1
```

Telegram login on the emulator uses a spare real account on the production DC; the owner of that account types the phone number and code.
