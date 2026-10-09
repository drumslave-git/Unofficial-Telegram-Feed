# Unofficial Telegram Feed — Plan

Open work only. A task leaves this file in the commit that finishes it; `git log` records finished work.

Legend: `[ ]` not started, `[~]` in progress (name the branch).

**Current phase:** P0 — Repo and build. **Next task:** P0-4.

The fork of the official Telegram Android client (DrKLO/Telegram, 12.10.6) that takes over three features of the Flutter app `telegram-feed`: feeds that group channels into one timeline, keyword rules per channel that decide which posts notify and are read aloud, and a switch that hides stories. Everything else stays as the official app has it. Each phase from P1 on ends with a tagged release.

## P0 — Repo and build

- [ ] P0-4 CI. GitHub Actions: `ci.yml` builds the debug APK and runs the tests on every push; `release.yml` builds a signed release APK on a tag from the keystore, `api_id`/`api_hash` and `google-services.json` secrets, as `telegram-feed/.github/workflows/release.yml` does, and fails without the Firebase secret.
- [ ] P0-5 Unit-test module. A plain JVM test module (JUnit) for the fork's own code (rule engine, feed merge order, filters); `TMessagesProj_AppTests` holds Telegram's instrumented tests and is left as it is.

## P1 — Stories switch

- [ ] P1-1 Setting. A section "Unofficial Telegram Feed" in Settings (`ProfileActivity`'s settings rows, before Chat Settings) with the switch "Hide stories", stored device-wide in `SharedConfig`, off at first. Strings in en and uk.
- [ ] P1-2 Hiding. With the switch on: the stories bar in the chat list (`DialogStoriesCell` in `DialogsActivity`), stories in the archive, the story rings and stories tabs in profiles (`ProfileActivity`, `SharedMediaLayout` stories and archived-stories tabs), story notifications (`NotificationsController` story pushes), "Repost as story", share-to-story and stories in the forward sheet, the stories camera and its hint, story mentions and replies in chats drawn as plain text, and `StoriesController` not loading stories at all. Toggling applies at once without a restart. Checked light and dark on the emulator.
- [ ] P1-3 Release 0.1.0: tag, signed APK, README.

## P2 — Feeds

- [ ] P2-1 Data model and storage. `Feed` (id, name, order, ordered channel ids, filter, "show minimized", "show the whole post") per account in the fork's SQLite file; `FeedsController` per account (created like the other `AccountInstance` controllers), with `NotificationCenter` events for changes. Unit tests.
- [ ] P2-2 "Feeds" tab. A tab in `FilterTabsView` after "All chats" and before the folders. It lists the feeds (name, channel count, channels with new posts, unread count in the accent colour), a "New feed" button, a drag handle to reorder, a row menu (edit channels, rename, mark read, delete with the count of rules that go with it). Empty state explains what a feed is and offers to create one. The tab counts channels with unread posts once, however many feeds hold them. A tap on the open tab scrolls to the top.
- [ ] P2-3 Feed editor. Name with a pencil; the ordered channels with remove and Undo; the add-channel sheet over the joined channels with search, multi-select, one "Add" press, and a remembered checkbox that hides channels already in a feed; "New feed" offers to start from a Telegram folder's channels (a one-time copy). Channels only: groups, bots and private chats are never offered.
- [ ] P2-4 Feed timeline. `FeedActivity`: one chronological list of the posts of all the feed's channels, oldest on top, drawn with `ChatMessageCell` in the group layout so every post carries its channel's name and photo (a tap opens the channel). History per channel through `MessagesController.loadMessages` with a `classGuid` per channel, merged by date then id, older posts loading as the list scrolls up; new posts through `didReceiveNewMessages`, edits and deletions applied in place; albums kept together. "Unread posts" divider at the first unread post, the button to the newest posts with the unread count, day labels and the floating day, the position restored when the feed is opened again. Reading marks each channel read up to the newest post seen in it, so the official app agrees.
- [ ] P2-5 Post actions in a feed. The post menu as in `ChatActivity` (reactions, comments, copy, forward, share, save, report, "Show in chat" which opens the channel at the post), selection of several posts, and `PhotoViewer` paging over the pictures and videos of the whole feed.
- [ ] P2-6 Feed filters and minimized posts. Per feed: all posts / only media / only text, media types, minimum video length, minimum text length, and a word condition built like a rule's; hidden posts gone or, with "Show minimized", folded to one line that a tap opens. Hidden and minimized posts are read with their neighbours and count for nothing.
- [ ] P2-7 Feed search. The magnifier in a feed searches all its channels (`messages.search` per channel, merged), goes to the newest match with the words marked, a bar with arrows and "3 of 47", "Show as list" with the results under the channel names, filter chips for media, links, files, music and voice.
- [ ] P2-8 Shared media of a feed. The feed's info screen has the Media, Files, Links, Music, Voice and GIFs tabs over all of its channels: `SharedMediaLayout` gets a data source that merges several dialogs.
- [ ] P2-9 Channels and feeds together. Every channel row in the chat list tags the feeds it belongs to; the long-press menu of a channel row adds it to a feed (or creates the first one); a long press on a folder tab creates a feed from the folder.
- [ ] P2-10 Counting. The "Count unread posts" switch in Notifications and Sounds decides whether a feed shows posts or channels with unread posts; "Mark as read" on a feed marks every channel of it read.
- [ ] P2-11 Hands-on pass on the spare account in NewsFeed and Real News, light and dark, en and uk; release 0.2.0.

## P3 — Rules and notifications

- [ ] P3-1 Rule engine. `Rule` (id, name, enabled, channel id, optional feed id, condition tree, priority silent / normal / urgent, read aloud, schedule of weekdays and two times) in the fork's SQLite file; the condition grammar ported from `telegram-feed/packages/core/lib/src/rule_engine.dart` (AND / OR / NOT, phrases, whole word, case sensitivity, the text syntax); matching over post text and captions only. A rule scoped by a feed sees only the posts the feed shows. `RulesController` per account. Unit tests ported from the Flutter engine's tests.
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
