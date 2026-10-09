# Unofficial Telegram Feed — Architecture

## 1. Summary

The app is the official Telegram client for Android (DrKLO/Telegram, 12.10.6) with the fork's code added inside it. The fork keeps Telegram's modules, build and native code and changes as little of Telegram's Java as the three features need. Upstream is merged on every Telegram release.

## 2. Build

The app is built from `TMessagesProj_App` only. The Huawei, HockeyApp and Standalone modules stay in the tree for upstream merges and are not built; they still reference Telegram's own package and Firebase config and do not build with the fork's settings.

### Requirements on Windows

- Android SDK at `C:\Users\<user>\AppData\Local\Android\Sdk` (`ANDROID_HOME`), with platform 36, build-tools 36.0.0, CMake 3.22.1 and NDK 27.2.12479018 (`TMessagesProj/build.gradle` pins the NDK; install it with `sdkmanager "ndk;27.2.12479018"`, which itself needs `JAVA_HOME` set to a Java 17 or newer).
- JDK 17 as `JAVA_HOME`. Gradle 8.13 (the wrapper) does not run on Java 24 or newer: Android Studio's bundled JBR is Java 25 and fails with "Unsupported class file major version 69". The Temurin 17 that Gradle provisioned for the Flutter app, at `C:\Users\<user>\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2`, works.
- No `local.properties` is needed when `ANDROID_HOME` is set.
- The `secrets/` directory (section 4).

### Debug APK

```bash
export JAVA_HOME="C:/Users/<user>/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
export ANDROID_HOME="C:/Users/<user>/AppData/Local/Android/Sdk"
./gradlew :TMessagesProj_App:assembleAfatDebug
```

The APK is `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk` (about 107 MB, all four ABIs). The `afat` flavour is the only one the debug build type accepts (`variantFilter` in `TMessagesProj_App/build.gradle`).

The native part compiles only Telegram's own C++ (`tmessages.49`, about 1730 object files per ABI); ffmpeg, BoringSSL, dav1d, libvpx, opus, tde2e, tlottie and wamr are prebuilt static libraries under `TMessagesProj/jni/prebuild/lib/<abi>`. A clean build of all four ABIs takes 28 minutes on the development machine; a build after a Java or resource change takes one to five minutes. Gradle's daemon needs the 8 GB heap set in `gradle.properties`.

### Emulator

Manual runs use the `Pixel_10` AVD (Android 16, `google_apis`, x86_64):

```bash
emulator -avd Pixel_10
adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
adb shell monkey -p org.unofficial.telegramfeed -c android.intent.category.LAUNCHER 1
```

Telegram login on the emulator uses a spare real account on the production DC; the owner of that account types the phone number and code.

## 3. Identity

- Package `org.unofficial.telegramfeed` (`APP_PACKAGE` in `gradle.properties`), the same for debug and release builds: the upstream `.beta` suffix of debug builds is removed in `TMessagesProj_App/build.gradle`. The Java namespace stays `org.telegram.messenger`, so class names in manifests and `LauncherIconController` are unchanged.
- App name "Unofficial Telegram Feed" (`AppName` in every `values*/strings.xml`; the manifests of `TMessagesProj/config` label the application with it) and launcher label "TG Feed" (`AppLauncherLabel`, set on the launcher activity aliases). Inside the app, Telegram's cloud language packs (`BuildVars.USE_CLOUD_STRINGS`) override `AppName` at run time with "Telegram"; the manifest labels are not affected.
- Launcher icon: the Flutter app's icon (`telegram-feed/docs/icon.png`) as `ic_launcher` and `ic_launcher_round` in every density, as the adaptive icon (`drawable/tgfeed_icon_background.xml` and `tgfeed_icon_foreground.xml`, copied from the Flutter app's launcher drawables), and as `drawable/ic_launcher_dr` (the icon of notifications' persons and of the sync account). The alternative icons of Settings > Appearance (`icon_2` to `icon_6`) are Telegram's.
- The contacts sync account type is the package (`@string/tgfeed_account_type`, a `resValue` of the app module, defaulted in `values/tgfeed.xml`), so the fork and the official app install side by side.
- `BuildVars.SUPPORTS_PASSKEYS` is off; passkeys work only with Telegram's own app ids.

## 4. Secrets

Nothing secret is committed. The gitignored directory `secrets/` at the repo root (another one is named with the gradle property `TGFEED_SECRETS_DIR`, relative to the repo root) holds:

- `telegram.json` with `TG_API_ID` and `TG_API_HASH`, in the format of the Flutter app's file. `TMessagesProj/build.gradle` reads it into `BuildConfig.APP_ID` and `BuildConfig.APP_HASH`, which `BuildVars` exposes.
- `google-services.json`, the `google-services.json` of the Firebase project `tg-feed-a0fce` with an Android client for the package `org.unofficial.telegramfeed`. The google-services gradle plugin is not applied; `TMessagesProj_App/build.gradle` reads the file and sets the string resources the plugin would generate (`google_app_id`, `gcm_defaultSenderId`, `google_api_key`, `project_id` and the rest). The build fails when the file is missing or has no client for the package.

The root `build.gradle` holds the directory lookup (`tgfeedSecretsDir`) and the reader (`tgfeedReadSecretJson`).

## 5. Code layout

- The fork's own code is in the package `org.unofficial.telegramfeed` under `TMessagesProj/src/main/java`, in the same module as Telegram's code so that it reaches Telegram's controllers, cells and fragments directly. Its resources carry the prefix `tgfeed_`.
- The subpackage `org.unofficial.telegramfeed.core` holds the pure logic (the rule engine, the order of a feed's posts, the feed filters) and imports nothing from Android or Telegram. The plain JVM module `TMessagesProj_FeedTests` compiles that directory as its main source set and holds its JUnit 4 tests under `src/test/java`, so an Android import there breaks the tests' compilation.
- Every edit to a Telegram file is marked on its changed lines with a `TGFEED` comment (`// TGFEED` in Java and gradle, `<!-- TGFEED -->` in XML), so that `git grep TGFEED` lists every point where the fork touches Telegram and an upstream merge conflict is read in context.
- The fork's UI strings are in `TMessagesProj/src/main/res/values/strings.xml` (English) and `values-uk/strings.xml` (Ukrainian), among Telegram's strings. Telegram's build plugin (`buildSrc`, `TelegramStringsTask`) packs every string of both files, so the fork's strings need nothing else. Telegram's cloud language packs override only the keys they know; the fork's keys keep their local values in every language.

## 6. Settings

The fork's settings section, "Unofficial Telegram Feed", is the first block of `SettingsActivity` (the Settings tab), before Account and Chat Settings, built with `UItem` rows and ids from 100 up. Device-wide settings are fields of `SharedConfig` stored in the `mainconfig` preferences with the prefix `tgfeed`: `tgfeedHideStories` (the "Hide stories" switch, off at first).

### Hiding stories

With `SharedConfig.tgfeedHideStories` on, every `TGFEED` hook below answers as if there were no stories; toggling the switch posts `storiesUpdated` to every account (and loads stories again when turned off), so the chat list follows at once.

- `StoriesController`: `hasStories`, `hasOnlySelfStories`, `hasHiddenStories`, `getStories` and `getStoriesFromFullPeer` report nothing; `loadStories`, `loadAllStories`, `loadHiddenStories`, `loadNextStories` and `processUpdate` return at once, so no story is loaded or kept.
- `DialogsActivity`: `updateStoriesVisibility` hides the stories bar of the chat list and of the archive, the stories camera button (`floatingButtonStories`) and its hint stay hidden.
- `ProfileStoriesView` draws no ring; `SharedMediaLayout.includeStories` drops the Stories and Archived Stories tabs.
- `NotificationsController` skips story, story mention, story reaction and live story pushes and shows no story notification for pushes stored earlier.
- `ShareAlert` offers neither "My Story" nor "Repost to Story".
- `MessageObject` gives a story message or a story mention the text type (the link or "Story"), and `ChatMessageCell` draws a reply to a story as the words "Story" without its picture.

## 7. Storage

Feeds and rules are stored per account in the fork's own SQLite file, `tgfeed<account>.db` in the app's files directory, next to Telegram's `cache4.db`. Telegram's database is not changed, so an upstream change to its schema never touches the fork's data. Each account's controllers (`FeedsController`, `RulesController`) own that account's file and are created like the other per-account controllers of `AccountInstance`.

## 8. Upstream merges

The remote `upstream` is https://github.com/DrKLO/Telegram.git. On every Telegram release:

1. `git fetch upstream --tags` and `git merge <release tag>` on `master`.
2. Resolve conflicts; every conflict in a Telegram file is at a `TGFEED` line or next to one.
3. Update the version lines in `PLAN.md` and this file, rebuild with `tool/ci.sh`, and do a hands-on pass on the emulator: login, chat list, the Feeds tab, a feed, a rule notification, read aloud, the stories switch.
4. Commit the merge as `chore: merge Telegram <version>`.

## 9. Tests and CI

`tool/ci.sh` runs the unit tests of `TMessagesProj_FeedTests` (`:TMessagesProj_FeedTests:test`) and builds the debug APK, and exits with the first failing step's code; it needs `JAVA_HOME` and `ANDROID_HOME` as in section 2. `TMessagesProj_AppTests` holds Telegram's instrumented tests and is not run.

The gradle property `TGFEED_ABIS` (comma-separated ABI names) limits the ABIs the native code is built for; without it all four are built.

`.github/workflows/ci.yml` runs `tool/ci.sh` on every push and pull request on an Ubuntu runner with Temurin 17, the pinned NDK and CMake, for `arm64-v8a` only, and keeps the debug APK as the artifact `debug-apk`. It writes `secrets/` from the repository secrets `TG_API_ID`, `TG_API_HASH` and `GOOGLE_SERVICES_JSON_BASE64` (`base64 -w0 google-services.json`) and fails when one is missing.

## 10. Releases

Releases are automatic. `.github/workflows/release.yml` runs on every push to `master` and computes the next version from the conventional commits since the last tag `vX.Y.Z` with `tool/next_version.sh`: a breaking change (`type!:` or a `BREAKING CHANGE:` footer) raises the major version, or the minor one while the major is 0; `feat` raises the minor; `fix` and `perf` raise the patch; commits of other types alone (`docs`, `chore`, `test`, `ci`, `refactor`) release nothing. Without any tag the version is 0.1.0. When a release is due the workflow runs the unit tests, builds `:TMessagesProj_App:assembleAfatRelease` with all four ABIs, signed with the keystore from the secret `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.keystore`, written over the dummy `TMessagesProj/config/release.keystore` in the runner's checkout) and the gradle properties `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` from the secrets `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`, tags the commit and publishes `unofficial-telegram-feed-<version>.apk` on the GitHub release with a changelog (breaking changes, features, fixes, with the task id as the scope). It needs the same three secrets as `ci.yml` and fails when any secret is missing. Only the tip of `master` is released.

The version of a release is passed to gradle by the workflow: `APP_VERSION_NAME` is the computed version and `APP_VERSION_CODE` is the number of commits on `master` (`git rev-list --count HEAD`), which grows with every push; Telegram's build script multiplies the code by ten and adds the flavour's digit. `gradle.properties` holds `dev` and `1` for local builds. `TELEGRAM_BASE_VERSION` in the same file names the Telegram version the fork is built on; it reaches the code as `BuildVars.TELEGRAM_BASE_VERSION`, the version line at the bottom of Settings shows the fork's version with its code and then "Telegram for Android v<base>", the release notes open with it, and an upstream merge updates it. `BuildVars.CHECK_UPDATES` is off, so the app never asks for Telegram's own updates.

The keystore is created once, locally, and kept out of git:

```bash
keytool -genkey -v -keystore release.keystore -keyalg RSA -keysize 2048 -validity 10000 -alias <alias>
```

A local signed build puts that keystore at `TMessagesProj/config/release.keystore` and passes the three properties on the command line (`-PRELEASE_STORE_PASSWORD=...`); the dummy keystore and passwords in `gradle.properties` are Telegram's and sign nothing that is published.
