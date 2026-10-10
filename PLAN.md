# Unofficial Telegram Feed — Plan

Open work only. A task leaves this file in the commit that finishes it; `git log` records finished work.

Legend: `[ ]` not started, `[~]` in progress (name the branch).

**Current phase:** P2 — Feeds. **Next task:** P2-9.

The fork of the official Telegram Android client (DrKLO/Telegram, 12.10.6) that takes over three features of the Flutter app `telegram-feed`: feeds that group channels into one timeline, keyword rules per channel that decide which posts notify and are read aloud, and a switch that hides stories. Everything else stays as the official app has it. Each phase from P1 on ends with a tagged release.

## P2 — Feeds

- [ ] P2-9 Channels and feeds together. Every channel row in the chat list tags the feeds it belongs to; the long-press menu of a channel row adds it to a feed (or creates the first one); a long press on a folder tab creates a feed from the folder.
- [ ] P2-10 Counting. The "Count unread posts" switch in Notifications and Sounds decides whether a feed shows posts or channels with unread posts; "Mark as read" on a feed marks every channel of it read. The Feeds tab's counts still include posts a feed's filter hides (the timeline's own counter already leaves them out).
- [ ] P2-11 Hands-on pass on the spare account in NewsFeed and Real News, light and dark, en and uk; release 0.2.0.

## P3 — Rules and notifications

- [ ] P3-1 Rule engine (deleting a feed then says how many rules go with it). `Rule` (id, name, enabled, channel id, optional feed id, condition tree, priority silent / normal / urgent, read aloud, schedule of weekdays and two times) in the fork's SQLite file; the condition grammar ported from `telegram-feed/packages/core/lib/src/rule_engine.dart` (AND / OR / NOT, phrases, whole word, case sensitivity, the text syntax); matching over post text and captions only. A rule scoped by a feed sees only the posts the feed shows. `RulesController` per account. Unit tests ported from the Flutter engine's tests.
- [ ] P3-2 Notification hook. In `NotificationsController.processNewMessages` and `processLoadedUnreadMessages` (the push path): a post of a channel with at least one enabled rule is dropped unless a rule matches; a matching post takes the highest matching priority, names the rule in the notification, and is grouped per channel as Telegram groups a chat. Silent goes to the tray only, normal pops up, urgent breaks through Do Not Disturb where Android permits, each through its own notification channel. Edited posts are not matched again and do not sound again. Channels without rules are untouched.
- [ ] P3-3 Rules UI. The rules list (every rule under its channel, with the feed's name where scoped) reached from the "Unofficial Telegram Feed" settings section, from a channel's profile and its notification settings ("Rules" row), and from a feed's menu. The rule editor: name and switch, channel, optional feed, the condition as a visual builder or as text with its syntax sheet, a dry run over the channel's recent posts that says how many posts it checked and would have matched, priority with an explanation of each level and a shortcut to Android's Do Not Disturb setting for urgent, read aloud, schedule. Leaving with unsaved changes asks first. Saving the first rule asks for the notification permission.
- [ ] P3-4 Pause. A bell-with-slash button in the chat list's action bar pauses every rule and the speech until pressed again, surviving restarts; while paused it is red and a banner under the action bar of every screen says so with "Resume".
- [ ] P3-5 Sounds. Notifications and Sounds gets a "Rules" block: sound and vibration for normal and for urgent rule notifications, applied at once.
- [ ] P3-6 Push while the app is closed. Telegram pushes only unmuted channels, so a channel with rules must be unmuted in Telegram: saving a rule for a muted channel offers to unmute it and otherwise says the rule notifies only while the app is open. The battery-optimisation request on the rules screens. Verified on the emulator with the debug push broadcast and with a real post on the spare account.
- [ ] P3-7 Missed posts. Posts that came while the phone was off or offline are matched when the app connects again, unless already read; rules match only posts newer than their creation.
- [ ] P3-8 Hands-on pass; release 0.3.0.

## P4 — Read aloud

- [ ] P4-1 Speech. `ReadAloudController`: Android `TextToSpeech`, "New post in <channel>" followed by the post text (the spoken prefix in the post's language when it is English or Ukrainian, otherwise in the interface language), language detection with the ML Kit language id the fork already ships (`LanguageDetector`), a voice per detected language, a queue that never overlaps or drops posts, audio ducking, a phone call pauses speech, speech with the screen off. Posts are read when a matching rule asks for it.
- [ ] P4-2 Stopping. A banner under the action bar of every screen while a post is read, naming the channel and the waiting count, with "Stop" and "Stop and clear queue"; volume down and a headset's pause stop the current post and clear the queue without changing the volume; with nothing read the keys work as usual.
- [ ] P4-3 Notification actions. Every rule notification carries "Listen" (reads the posts it lists that were not read yet, all of them when every one was) or "Stop" while one of its posts is read or waits; swiping the notification away and "Clear all" stop its posts.
- [ ] P4-4 Settings. "Read aloud" screen in the fork's settings section: speed, pitch, maximum length, language when detection fails, a preview, voices listed by language with a play button each, "Add language" from a searchable list, the phone's default voice for every other language.
- [ ] P4-5 Hands-on pass with the screen on, off and locked; release 0.4.0.

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
