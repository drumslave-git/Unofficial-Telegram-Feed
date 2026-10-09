# Unofficial Telegram Feed

An unofficial Telegram client for Android for people who follow many channels. It is the official Telegram app ([DrKLO/Telegram](https://github.com/DrKLO/Telegram)) with three additions:

- **Feeds**: channels combined into one chronological timeline, in a "Feeds" tab of the chat list.
- **Rules**: keyword rules per channel that decide which posts notify, at which priority, and which are read aloud.
- **Hide stories**: one switch that removes stories from the app.

Everything else is the official app, unchanged. This app is not made by Telegram.

- [Product spec](docs/SPEC.md): what the app does.
- [Architecture](docs/ARCHITECTURE.md): how it is built.
- [Plan](PLAN.md): open work.

## Build

Requirements: Android SDK with platform 36, build-tools 36.0.0, CMake 3.22.1 and NDK 27.2.12479018; JDK 17 (Gradle 8.13 does not run on Java 24 or newer).

Telegram API credentials and the Firebase config are not in the repository. Create the gitignored directory `secrets/` at the repository root with:

- `telegram.json`: `{"TG_API_ID": "12345", "TG_API_HASH": "abcdef..."}` from https://my.telegram.org ("API development tools").
- `google-services.json` from a Firebase project with an Android app for the package `org.unofficial.telegramfeed`. Push notifications need the project's Cloud Messaging credentials uploaded at my.telegram.org for that `api_id`.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :TMessagesProj_App:assembleAfatDebug
adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
```

`tool/ci.sh` builds the debug APK and runs the unit tests. The build recipe in detail, the emulator and the upstream merge procedure are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Telegram's conditions

Telegram asks every third-party client to use its own `api_id`, to say that it is unofficial, and not to use Telegram's logo. This app does all three: the credentials come from `secrets/`, the name begins with "Unofficial", and the launcher icon is its own.

## License

GPL-2.0, the licence of the official client. See [LICENSE](LICENSE).
