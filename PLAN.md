# Unofficial Telegram Feed — Plan

Open work only. A task leaves this file in the commit that finishes it; `git log` records finished work.

Legend: `[ ]` not started, `[~]` in progress (name the branch).

**Current phase:** P4 — Read aloud. **Next task:** P4-1.

The fork of the official Telegram Android client (DrKLO/Telegram, 12.10.6) that takes over three features of the Flutter app `telegram-feed`: feeds that group channels into one timeline, keyword rules per channel that decide which posts notify and are read aloud, and a switch that hides stories. Everything else stays as the official app has it. Each phase from P1 on ends with a tagged release.

## P4 — Read aloud

- [ ] P4-1 Speech. `ReadAloudController`: Android `TextToSpeech`, "New post in <channel>" followed by the post text (the spoken prefix in the post's language when it is English or Ukrainian, otherwise in the interface language), language detection with the ML Kit language id the fork already ships (`LanguageDetector`), a voice per detected language, a queue that never overlaps or drops posts, audio ducking, a phone call pauses speech, speech with the screen off. Posts are read when a matching rule asks for it.
- [ ] P4-2 Stopping. A banner under the action bar of every screen while a post is read, naming the channel and the waiting count, with "Stop" and "Stop and clear queue"; volume down and a headset's pause stop the current post and clear the queue without changing the volume; with nothing read the keys work as usual.
- [ ] P4-3 Notification actions. Every rule notification carries "Listen" (reads the posts it lists that were not read yet, all of them when every one was) or "Stop" while one of its posts is read or waits; swiping the notification away and "Clear all" stop its posts.
- [ ] P4-4 Settings. "Read aloud" screen in the fork's settings section: speed, pitch, maximum length, language when detection fails, a preview, voices listed by language with a play button each, "Add language" from a searchable list, the phone's default voice for every other language.
- [ ] P4-5 Hands-on pass with the screen on, off and locked; the phase's release.

## P5 — Release 1.0

- [ ] P5-1 Core-flow sweep on the emulator: login, chat list, folders, feeds, rules, read aloud, stories switch, light and dark, en and uk, Samsung notification behaviour noted in SPEC.md.
- [ ] P5-2 Release 1.0.0 on GitHub Releases. `telegram-feed`'s README points to the fork as the continued app.
- [ ] P5-3 First upstream merge after the release, following the procedure in `docs/ARCHITECTURE.md`.

## Later

- [ ] L-1 Google Play listing.
- [ ] L-2 Google Drive sync of feeds and rules, as in the Flutter app.
- [ ] L-3 Instant rules: a foreground service keeps the connection open while any rule is instant, so posts of muted channels notify at once.
- [ ] L-4 AI semantic rules through an OpenAI-compatible endpoint.
- [ ] L-5 Feeds and rules on the app icon's badge count.
