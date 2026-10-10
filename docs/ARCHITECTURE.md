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

The fork's settings section, "Unofficial Telegram Feed", is the first block of `SettingsActivity` (the Settings tab), before Account and Chat Settings, built with `UItem` rows and ids from 100 up. Device-wide settings are fields of `SharedConfig` stored in the `mainconfig` preferences with the prefix `tgfeed`: `tgfeedHideStories` (the "Hide stories" switch, off at first), `tgfeedCountPosts` ("Count unread posts", on at first) and `tgfeedRulesPaused` (the pause, off at first).

### Hiding stories

With `SharedConfig.tgfeedHideStories` on, every `TGFEED` hook below answers as if there were no stories; toggling the switch posts `storiesUpdated` to every account (and loads stories again when turned off), so the chat list follows at once.

- `StoriesController`: `hasStories`, `hasOnlySelfStories`, `hasHiddenStories`, `getStories` and `getStoriesFromFullPeer` report nothing; `loadStories`, `loadAllStories`, `loadHiddenStories`, `loadNextStories` and `processUpdate` return at once, so no story is loaded or kept.
- `DialogsActivity`: `updateStoriesVisibility` hides the stories bar of the chat list and of the archive, the stories camera button (`floatingButtonStories`) and its hint stay hidden.
- `ProfileStoriesView` draws no ring; `SharedMediaLayout.includeStories` drops the Stories and Archived Stories tabs.
- `NotificationsController` skips story, story mention, story reaction and live story pushes and shows no story notification for pushes stored earlier.
- `ShareAlert` offers neither "My Story" nor "Repost to Story".
- `MessageObject` gives a story message or a story mention the text type (the link or "Story"), and `ChatMessageCell` draws a reply to a story as the words "Story" without its picture.

## 7. Feeds tab

The "Feeds" tab sits in the chat list's tab bar (`FilterTabsView`) right after "All chats", in the main chat list only (not in the archive, forward or selection modes), and the tab bar is shown even when the account has no folders. Telegram gives a folder tab the folder's index as its id; the Feeds tab has the id `FilterTabsView.TGFEED_TAB_ID`, outside that range, and `FilterTabsView` maps tab positions to folder indexes around it when folders are reordered in the tab bar's edit mode, where the Feeds tab itself cannot be dragged, removed or passed over.

In `DialogsActivity`, a page whose selected tab is the Feeds tab gets the dialogs type `DIALOGS_TYPE_TGFEED`, for which `getDialogsArray` is empty and nothing is loaded, and a `FeedsTabView` (`org.unofficial.telegramfeed.ui`) is laid over the page's dialogs list with that list's paddings. The view lists the feeds as `FeedCell` rows (name, channel count, channels with new posts, the unread posts in the accent counter, a drag handle that reorders through `FeedsController.moveFeed`), the "New feed" row, which asks for a name, and the empty state. A long press on a row opens the menu (edit the channels, rename, the feed's rules, mark as read, delete after a confirmation). The accent counter of a row and the tab's counter follow the "Count unread posts" switch in Notifications and Sounds (`SharedConfig.tgfeedCountPosts`, on by default, a row under the badge settings): on, a row counts the feed's unread posts and the tab the unread posts of every channel in any feed, each channel once; off, both count channels with unread posts. The switch posts `tgfeedFeedsChanged` so the tab refreshes at once. A tap on the open tab scrolls the list to the top. The view and the counter refresh on `tgfeedFeedsChanged`, `dialogsNeedReload`, `updateInterfaces` and `dialogsUnreadCounterChanged`.

"New feed" asks for a name (`TgfeedAlerts.promptName`) and, when the account has folders that contain channels, offers to start with the channels of one of them (a one-time copy of what the folder shows at that moment), then opens the editor. A tap on a feed row and "Edit channels" in its menu open the editor too.

`FeedEditActivity` shows the feed's name in the action bar with a pencil that renames it, "Add channels", and the channels in the feed's order as `UserCell` rows with a close icon; a removed channel comes back at its place through the Undo bulletin. `AddChannelsSheet` (a `BottomSheetWithRecyclerListView` like Telegram's country picker) lists the joined channels (`ChatObject.isChannelAndNotMegaGroup`, not left) that are not in the feed, with a search over title and username, multi-select checkboxes in selection order, one "Add" button with the count, and the checkbox "Hide channels already in a feed", remembered in the `mainconfig` preference `tgfeedHideChannelsInFeeds` (on at first).

### Channels and feeds in the chat list

A channel row in the main chat list (`DialogCell` in the default and folder lists) carries a tag with the names of the feeds the channel is in, drawn as a small pill in the time's row before the clock or check marks, with the name narrowed to make room; the tag is built in `buildLayout` from `FeedsController.getFeedsOfChannel` and every visible row is rebuilt when `tgfeedFeedsChanged` arrives. The chat preview a long press on a channel's photo opens gets "Add to feed" in its menu: with feeds, it swipes to a list of them with a check on each the channel is in, a tap adds or removes the channel with a bulletin, and "New feed" at the end asks for a name and makes a feed of the channel; without feeds, "Add to feed" asks for the name at once. A long press on a folder tab adds "Feed from this folder" to the folder's menu, which asks for a name (the folder's to start with), makes a feed of the folder's channels (`FeedsTabView.channelsOfFolder`) and opens it.

### Feed timeline

`FeedActivity` (`org.unofficial.telegramfeed.ui`) shows a feed's posts the way `ChatActivity` shows a chat, with `ChatMessageCell` in the group layout (`isChat`, `MessageObject.forceAvatar`) so every post carries its channel's name and photo; the list draws the photo at the bottom of a run of pinned cells as Telegram's chat list does, and a tap on it opens the channel. The fragment view sits below the action bar in this Telegram version, so the list needs no action-bar padding and the wallpaper frame is told there is no overlapping action bar.

The list is laid out as `ChatActivity`'s: a reversed `GridLayoutManagerFixed` with 1000 spans, where adapter position 0 is the newest post at the bottom and the loading row comes last, at the top. An album's parts share a row through the span sizes of their `GroupedMessagePosition`s (the layout manager's `hasSiblingChild` and the item decoration with the negative bottom offsets are ported from the chat), so a part is measured to its span and placed beside its siblings; the parts of an album are sorted oldest first before `GroupedMessages.calculate`, because the grid puts the first slot on the left. The list draws albums in two passes as the chat's list does: before the children, one bubble under all the cells of an album (`ChatMessageCell.drawBackground` with the union of the cells' background bounds) and the selection band; after the children, the album's time, name, caption and reactions once, from the cells queued while drawing (`drawTime`, `drawNamesLayout`, `drawCaptionLayout`, `drawReactionsLayout`). A single post draws itself. Android's default focus highlight is off on the list, so a hardware Enter never dims it.

History is loaded per channel with `MessagesController.loadMessages` and a `classGuid` per channel (the newest 30 posts first, then 50 older ones at a time when the list is scrolled near its top, from the cache until it ends, then from the server). Every loaded post goes into one map keyed by channel and message id; the rows are rebuilt from it, newest first as in `ChatActivity`, sorted by date, channel and id (`FeedOrder`), with albums grouped into `GroupedMessages`, a date row before each day and the "Unread posts" divider (`ChatUnreadCell`) above the oldest post that was unread when the feed opened. Posts older than the oldest loaded post of any channel that still has history wait until that channel has loaded as far, so the merged list never shows a gap. New posts come through `didReceiveNewMessages`, edits through `replaceMessagesObjects` and deletions through `messagesDeleted`.

A jump to a post that is not loaded (a search match) reloads every channel around the post's date (`loadMessages` with load type 4) and the list then also loads newer posts as it is scrolled down (load type 1, 50 at a time), with a ceiling that mirrors the floor: posts newer than the newest loaded post of any channel that still has newer history wait. While a channel has newer history, its new posts are left to that loading, and the button to the newest posts throws the loaded history away and loads the newest again.

A tap on a post opens its menu (`ItemOptions` over the cell) with Telegram's reactions bar (`ReactionsContainerLayout`, sending through `SendMessagesHelper.sendReaction`) and the rows Comments and "Show in chat" (both open the channel at the post), Copy (text or caption), Forward (the dialogs picker in forward mode, sent with `SendMessagesHelper.sendMessage`), Share (`ShareAlert` with the post's public link), Save to gallery (when the file is on the phone) and Report (`ReportBottomSheet`). A long press starts a selection, shown with the cells' checkboxes and an action mode with the count, copy, share and forward; up to 100 posts. A tap on a picture or video opens `PhotoViewer` over the pictures and videos of the whole loaded feed, with the list's cell as the place to animate from.

The button to the newest posts shows the sum of the channels' unread counts and goes first to the divider, then to the end. The list's scroll position is saved per feed in the `mainconfig` preference `tgfeedPos_<account>_<feed>` as the first visible post and its offset, and restored when the feed opens again; without it the feed opens at the divider or at the newest post. When the list stops, each channel is marked read up to the newest of its posts on the screen (`markDialogAsRead`), so the official app agrees.

### Feed search

The magnifier in a feed's action bar opens Telegram's search field (`ActionBarMenuItem` as a search field). Under it a row of chips (Media, Links, Files, Music, Voice, from `FiltersView.filters`) narrows the search to one kind of message; a tapped chip goes into the field as the search item's filter and comes out again with backspace. The search runs on Enter. `FeedSearch` (`org.unofficial.telegramfeed.feeds`) sends `messages.search` to every channel of the feed, 20 matches a page, builds `MessageObject`s with the query set on them, and merges the pages newest first; as the timeline does for history, a channel that still has older matches holds the merged list back at its oldest loaded match, and `loadMore` asks the channels that hold it back for their next page. The total is the sum of the channels' counts.

The bar above the keyboard shows "3 of 47" (or "No results"), arrows to the older and the newer match, and "Show as list". Going to a match scrolls the timeline to it, lights the cell up for a moment and marks the words in every loaded match (`ChatMessageCell.setHighlightedText`); a match the timeline has not loaded reloads the history around it first, and a match the feed's filter hides opens the channel at the post. "Show as list" covers the timeline with the matches as `DialogCell` rows under the channels' names, loading the next pages near the end; a tap on a row goes to that match. Closing the search clears all of it.

### Feed info

The "i" in a feed's action bar opens `FeedInfoActivity`: the feed's name and channel count in the action bar, the editor behind the pencil, and the shared media of all its channels together under the tabs Media, Files, Links, Music, Voice and GIFs (`ScrollSlidingTextTabStrip`). Telegram's `SharedMediaLayout` reads one dialog (plus a migrated one), so the tabs are not it: each tab is a `FeedSearch` with an empty query and that kind's `messages.search` filter (photo/video, document, URL, music, voice, GIF), merged newest first across the channels and paged near the end of the list. The rows are Telegram's cells (`SharedPhotoVideoCell2` in a grid of three, `SharedDocumentCell`, `SharedLinkCell`, `SharedAudioCell`, and `ContextLinkCell` for GIFs in the chat's flow layout, `ExtendedGridLayoutManager` with each GIF's own size), and taps do what the chat's shared media does: `PhotoViewer` over the tab's items for media, GIFs and previewable files, play and a playlist of the tab for music and voice, download or open for files, and the article viewer, the embed sheet or the browser for links. A tab loads when first opened and reloads when the feed's channels change.

### Feed filters

A feed's filter (`FeedFilter` in `core`) has a mode (all posts, only posts with media, only text posts), a bitmask of media kinds (photo, video, GIF, music, voice, file, other), a minimum video length, a minimum text length and a word condition in the rule syntax. It is edited on the feed editor's "Filter" section: radio dialogs for the mode and the lengths (fixed steps: 30 s to 30 min, 50 to 1000 characters), a check-box dialog for the media kinds, and a text dialog for the condition that parses the text on Save and keeps the dialog open with the error (`TgfeedAlerts.describe` turns a `RuleParser.SyntaxException` into the reader's language). Every change is written at once through `FeedsController.updateFeed`, and an open timeline rebuilds its rows on `tgfeedFeedsChanged`.

`PostFilter` (`org.unofficial.telegramfeed.feeds`) describes a `MessageObject` to the filter as a `FeedFilter.Post`: the media kind from `MessageObject`'s `isPhoto`/`isGif`/`isVideo`/`isMusic`/`isVoice`/`isDocument` (round videos, stickers, polls, locations and the rest are "other"; a web page preview is no media), the duration for videos, the text or caption, the album id, and whether the message is a service line (a message with an `action`, shown only by an empty filter). `FeedActivity.applyFilter` judges each single post and each album as one unit with `FeedFilter.shownParts`: the word condition over all captions of the album together, the media and length settings part by part, and with "Show whole albums" one passing part brings the whole album. What is left out is dropped from the rows, or, with "Show hidden posts minimized", keeps its newest part as a one-line row (`MinimizedPostCell`: the channel's name and the first caption) that a tap opens in place for the rest of the visit. The "Unread posts" divider is placed above the oldest unread post that is shown; the button's counter is the feed's count from `FeedCounts`; and marking as read goes from the newest post on the screen over the hidden posts right after it, so hidden posts are read with their neighbours. The Feeds tab counts the same way: see Counts without hidden posts.

### Counts without hidden posts

`FeedCounts` (`org.unofficial.telegramfeed.feeds`, one per account, UI thread) gives the unread counts of the Feeds tab, of its rows and of a feed's button to the newest posts without the posts a feed's filter hides or folds. A channel in a feed without a filter counts as Telegram does (`dialog.unread_count`). For a channel in a feed with a filter the unread posts themselves are kept: fetched once with `messages.getHistory` above the read mark (`min_id = read_inbox_max_id`, pages of 100, at most 500 posts; unread posts past that count as shown), trimmed locally when the read mark moves, extended by fetching only what came after the newest known post when `top_message` grows, and fetched whole again when the read mark moves back or the count does not add up. Each feed judges the kept posts with its filter, an album as one post through `shownParts`, so a feed's count is the number of unread messages it shows; until a channel's posts are in, its count is Telegram's. The tab's counter counts a channel once, with the most any feed of it shows; with "Count unread posts" off, a channel counts when some feed of it shows an unread post. A finished fetch posts `NotificationCenter.tgfeedCountsChanged`, on which the chat list and an open feed refresh.

### Rule engine

The rule syntax is shared by feed filters and the keyword rules, and lives in `core` with no Android dependencies. `RuleExpr` is the tree (`And`, `Or`, `Not`, `Term` with `wholeWord` and `caseSensitive`), `RuleParser.parse` reads the text form and `RuleParser.format` writes it back: bare words or `"quoted phrases"` (with `\"` and `\\` escapes), `~` for a substring match and `=` for a case-sensitive one, `AND`, `OR`, `NOT` in any case and parentheses; AND binds tighter than OR, NOT tightest. A keyword used as a bare word is an error (quote it); errors carry a `Problem`, the offending text and a 1-based position. `RuleEvaluator` compiles each term once to a regular expression: a whole-word term is fenced by look-arounds on Unicode letters, digits and underscore, so it stops at punctuation and spaces in any script; case-insensitive terms use Unicode case folding; a phrase matches across any whitespace run. `Schedule` (weekdays and a daily window that may wrap midnight, judged by the weekday the window started on) is a rule's schedule. `RuleBuilder` is the visual builder's form of a condition: groups joined by OR, each an AND of terms that may be negated; any condition of that shape converts both ways without loss, and a deeper one (`a AND (b OR c)`, `NOT (a AND b)`) is edited as text only. The JUnit tests in `TMessagesProj_FeedTests` (`RuleParserTest`, `FeedFilterTest`, `RuleBuilderTest`) cover the grammar, the matching and the filter semantics; the test module compiles its sources as UTF-8 because the Cyrillic and CJK test strings break under the Windows default.

## 8. Storage

Feeds and rules are stored per account in the fork's own SQLite file, `tgfeed.db` in the account's files directory next to Telegram's `cache4.db` (`files/` for the first account, `files/account<N>/` for the others). Telegram's database is not changed, so an upstream change to its schema never touches the fork's data. `FeedsStorage` (`org.unofficial.telegramfeed.feeds`) opens the file through Telegram's `SQLiteDatabase` wrapper on its own `DispatchQueue`, creates the tables and sets `PRAGMA user_version` to the schema version (2; the tables are created when missing, so a file of version 1 gains the `rules` table):

- `feeds(id, name, sort, show_minimized, show_whole_post, filter_mode, filter_media, min_video_seconds, min_text_length, filter_words)`: one row per feed; `filter_media` is the bitmask of `FeedFilter`'s media types and `filter_words` the word condition in the rule syntax.
- `feed_channels(feed_id, channel_id, position)`: the feed's channels in their order.
- `rules(id, name, enabled, channel_id, feed_id, condition, priority, read_aloud, schedule, created_at)`: one row per rule; `feed_id` is 0 for a rule no feed scopes, `condition` is the rule syntax (empty for every post), `priority` 0 silent, 1 normal, 2 urgent, `schedule` the `Schedule.encode` form (`1,2,3|09:00|18:00`, empty for always) and `created_at` Unix seconds.

`FeedsController` (`getInstance(account)`, also `AccountInstance.getFeedsController()`) keeps the account's feeds in memory on the UI thread, loads them once from the file, gives new feeds the next free id, writes every change through to the storage and posts `NotificationCenter.tgfeedFeedsChanged` (an account event) after a load and after every change. `MessagesController.performLogout` calls its `cleanup`, which forgets the feeds and deletes the file. The model classes `Feed` and `FeedFilter` live in the `core` package and have no Android dependencies.

`RulesController` (`getInstance(account)`, also `AccountInstance.getRulesController()`) does the same for the rules on the same storage queue and posts `NotificationCenter.tgfeedRulesChanged`; a new rule gets the next free id and the current time as `created_at`. Deleting a feed deletes the rules it scopes, and the feed's delete confirmation says how many. `MessagesController.performLogout` calls the rules' `cleanup` before the feeds', whose cleanup deletes the file.

### Rules

A rule (`core.Rule`) belongs to one channel and may be scoped by one feed. `core.RuleMatcher` decides which rules match a new post, purely from the rules, the feeds as `FeedScope`s (channels, filter, whole albums) and the moment the post came: a service line is not a post; a rule counts when it is on, its condition parses, the post is not older than the rule and its schedule is active at the post's time; a rule scoped by a feed counts only when that feed holds the channel and shows the post (`FeedFilter.mayShow` for a single message, where an album part counts as shown when the feed shows whole albums and is not text-only, and `shownParts` for a complete album); a post without text matches only a rule with no condition. An album is matched as one post over all its captions and is named by its first part with a caption. A match carries its rules, the highest priority, whether any rule reads aloud, the rule names with those of the highest priority first and the feed of the first rule of the highest priority. `RulesController.match` and `matchAlbum` run it against the account's rules and feeds; `hasEnabledRules(channelId)` tells whether a channel notifies only for matches. The grammar of the condition is `RuleParser`'s (section 7, Rule engine).

### Rules screens

`ui.RulesActivity` lists rules under their channels' titles, all of them or those of one channel (`ofChannel`) or of one feed (`ofFeed`); a row's switch turns the rule on or off, a tap opens it, a long press deletes it after asking. It is opened from the "Rules" item of the fork's settings section (id 101), from a "Rules" row in a broadcast channel's `ProfileActivity` and `ProfileNotificationsActivity` (both showing the channel's rule count), and from the menu of a row of the Feeds tab. `ui.RuleEditActivity` edits a copy of the rule and writes it with `createRule` or `updateRule` when the action bar's check is pressed. The condition is edited in the builder while it fits `RuleBuilder` and as text otherwise; switching to the builder refuses a condition too nested for it. The dry run fetches the channel's newest 100 posts with `messages.getHistory` and matches the draft with `RuleMatcher` as if it were on and had neither a schedule nor a creation time, an album counting as one post. Choosing "Urgent" offers Android's settings of the `tgfeed_rules_urgent` channel (`Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS`), where "Override Do Not Disturb" is switched on. Back, the back arrow and swipe-back ask before dropping unsaved changes. Saving the account's first rule asks for `POST_NOTIFICATIONS` on Android 13 and later when it is not granted.

### Pause

`SharedConfig.setTgfeedRulesPaused` stores the pause and posts the global `NotificationCenter.tgfeedPauseChanged`. While paused, `RuleNotifications.decide` drops every post of a channel that has rules, so such channels stay silent and nothing is remembered to notify later; channels without rules keep Telegram's notifications. The pause button is an item of the chat list's action bar (`PauseBanner.bindPauseButton`) whose slashed bell draws itself red while paused, whatever colour the bar gives its items. `ui.PauseBanner` says so with "Resume" and follows the pause by itself:

- On a screen the fragment container lays out below its action bar (every `ActionBar` whose parent is `ActionBarLayout.LayoutContainer`), the action bar holds the banner below its own height and measures that much taller, so the container moves the screen's content down.
- Screens that place their action bar themselves get it in their own way: first in the stack of top panels of the chat list, of a chat, of a forum's topics and of the call log (`PauseBanner.addTo`), and as the first row of the fork's section on the Settings tab.

### Rule notifications

`RuleNotifications` (`org.unofficial.telegramfeed.feeds`, one per account) gives the rules their say in `NotificationsController`; every call runs on Telegram's notifications queue. The rules decide about a channel that has at least one enabled rule; every other dialog, and mentions, reactions and stories, stay Telegram's.

- Telegram's unread counting in `processDialogsUpdateRead` and in the push branch of `processNewMessages` checks the dialog's notification settings a second time before it counts a message and asks for an alerting update; for a governed channel the dialog counts exactly when one of its messages in the tray notified through a rule, so a match on a muted channel alerts and a post no rule matches never re-alerts older notifications.
- In `processNewMessages` the decision comes right after Telegram's checks for duplicates and edits, before a pushed message is stored with `putPushMessage`: a post no rule matches is dropped there, so it is neither stored, counted nor shown; a matching post is shown whatever the channel's notification settings say, muted included. A pushed message carries only the text of a text post (a photo arrives as the word "Photo"), so for a push the post is first fetched by id with `channels.getMessages`, waiting up to 8 seconds, and the pushed text is used when the fetch fails. Of an album only the first matching part notifies. Edits never notify again, since Telegram's own edit path does not reach the decision.
- The rules are read from `RulesController.Snapshot`, copies of the rules and of the feeds as `FeedScope`s that `RulesController.publish` replaces on the UI thread after every change of either; a background thread waits up to 3 seconds for the first snapshot, loading both when the process was started by a push.
- Every match is remembered by dialog and message id with its priority, its feed and its rule names (those of the highest priority first), in memory and in the preferences file `tgfeed_rule_notifications<account>` (the newest 300). `processLoadedUnreadMessages`, which rebuilds the notifications from stored pushes and unread messages when the app starts, keeps of a governed channel exactly the messages that notified through a rule; `MessagesStorage.loadUnreadMessages` loads the unread messages of a governed channel even when it is muted, since Telegram would otherwise drop its rule notifications at every start.
- A governed channel counts, in the summary's "new messages" and in the unread total of the notifications, only the posts of it in the tray that notified through a rule, never its whole unread count.
- `showOrUpdateNotification` shows a remembered match even for a muted channel and lets its priority decide whether it alerts, past the dialog's sound switch, Telegram's limit of two alerts in three minutes and a post the channel sent silently: silent never alerts, normal and urgent always do. The notification goes to the priority's own channel in the group "Rules", made by `RuleSounds`: `tgfeed_rules_silent` (low importance, no sound or vibration), `tgfeed_rules_normal` (high importance) and `tgfeed_rules_urgent` (high importance, asks to bypass Do Not Disturb, which Android grants when the user allows it for the channel). The sound and vibration of normal and urgent come from the "Rules" block of Notifications and Sounds, stored device-wide in the preferences file `tgfeed_rule_sounds` (the default notification sound; vibration by default for normal and a long pattern for urgent). Android fixes a channel's sound and vibration once it exists, so a change deletes that priority's channel and creates it again under the next id (`tgfeed_rules_normal_1`, `_2`, ...); the new channel loses what the user set for the old one in Android's settings, and when the old urgent channel was let through Do Not Disturb, the app offers that setting again. An update that does not alert keeps Telegram's silent channel. The notification of the channel names the rules as its subtext.

### Push while the app is closed

Telegram's server pushes a channel's posts through Firebase only while the channel is not muted in Telegram; the push starts the app's process, and `PushListenerController` hands the post to `processNewMessages` as above. Saving an enabled rule of a muted channel offers to unmute it (`NotificationsController.muteDialog`), and the editor notes under the channel that a muted channel's rules notify only while the app is open. The rules list asks, while it has rules, to let the app off battery optimisation (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

### Missed posts

Posts that came while the app was not connected reach the rules when it connects again. Telegram's catch-up (`getDifference`, then `getChannelDifference` per channel with a gap) hands the unread posts it brings to `processNewMessages`, where the rules decide as for a live post. When a channel's gap is longer than the request's limit of 100 updates, the server answers `updates.channelDifferenceTooLong` with the channel's latest posts, which Telegram stores without notifying; for a channel whose rules decide, the unread ones among them go to `processNewMessages` as well, oldest first. Read posts and posts older than a rule never notify, and a post already in the tray is not notified twice.

Debug builds declare `debug.DebugPushReceiver` (in `TMessagesProj/config/debug/AndroidManifest*.xml`, guarded by the `DUMP` permission, so only the shell can send to it). It builds the push Telegram sends for a channel post, encrypts it with the account's push key as the server does and passes it to `PushListenerController.processRemoteMessage`, which tests the whole push path, decryption included, with the process not running:

```
adb shell "am broadcast -n org.unofficial.telegramfeed/org.unofficial.telegramfeed.debug.DebugPushReceiver --es channel_id <id> --es msg_id <id> --es title '<channel>' --es text '<text>'"
```

### Read aloud

When a match asks for read-aloud, `RuleNotifications.decide` hands the post's text and its channel's title to `ReadAloudController` (`org.unofficial.telegramfeed.feeds`, one per process, UI thread), unless the rules are paused; a post already queued or read is not queued again. The controller reads one post at a time in the order they came, with Android's `TextToSpeech`:

- `core.SpeechText` turns the post into what is spoken: links become the word for "link", mentions, emoji and formatting marks go, whitespace collapses, a post longer than 600 characters is cut at a sentence end or a word and ends with "… and more", and "New post in <channel>." goes first.
- The post's language comes from ML Kit's language identification through Telegram's `LanguageDetector`. The words the app adds are in that language when it is English or Ukrainian and in the interface language otherwise (taken from the app's own resources for that language), and the post is spoken with the engine's voice for its language, or for the interface language when the engine has none.
- Speech uses the media stream (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) with a transient audio focus that lets other audio duck. Losing the focus, to a call or to another app, stops the post; it is read again from the start once the focus returns. While the audio mode is a call or ringing, the queue waits and checks again every 3 seconds.
- While the queue has work, `ReadAloudService`, a foreground service of type `mediaPlayback` with a silent notification on the channel `tgfeed_read_aloud`, and a partial wake lock keep the process speaking with the screen off and after a push. Android lets a push start it; when Android refuses, speech still runs while the process lives.
- Pausing the rules stops the post being read and empties the queue. `NotificationCenter.tgfeedReadAloudChanged` (global) is posted whenever the post being read or the queue changes.
- `ui.ReadingBanner` shows while a post is read: the channel, how many posts wait, "Stop" (the next post follows) and, while posts wait, "Stop and clear queue". It sits with the pause banner wherever that one shows (`PauseBanner.createStack` under an action bar and in the Settings section, `PauseBanner.addTo` in the stacks of top panels).
- While the queue has work the controller holds a `MediaSession` that plays "remotely" through a `VolumeProvider` of its own. Android gives the volume keys and a headset's buttons to that session, also with the screen off, so volume down and a headset's pause or stop end the speech and empty the queue without changing the volume, and volume up raises the media volume. The session is released when the queue is done, so the keys work as usual again.
- After "Stop" the next post starts 400 ms later, since the engine stops asynchronously and would cut a post spoken at once.
- A rule notification carries "Listen", or "Stop" while a post of its channel is read or waits (`NotificationsController.tgfeedAddReadAloudAction`, handled by `ReadAloudReceiver`). "Listen" queues the posts the notification lists that were not read aloud yet, or all of them again when every one was; the posts queued or read are kept, the newest 500, in the preferences file `tgfeed_read_aloud`, so this holds across processes. When the set of channels being read changes, the controller asks `NotificationsController.showNotifications` to rebuild the notifications silently with the other action. Dismissing a channel's notification stops its posts, and dismissing the summary, as "Clear all" does, stops those of the account (`NotificationDismissReceiver`).

## 9. Upstream merges

The remote `upstream` is https://github.com/DrKLO/Telegram.git. On every Telegram release:

1. `git fetch upstream --tags` and `git merge <release tag>` on `master`.
2. Resolve conflicts; every conflict in a Telegram file is at a `TGFEED` line or next to one.
3. Update the version lines in `PLAN.md` and this file, rebuild with `tool/ci.sh`, and do a hands-on pass on the emulator: login, chat list, the Feeds tab, a feed, a rule notification, read aloud, the stories switch.
4. Commit the merge as `chore: merge Telegram <version>`.

## 10. Tests and CI

`tool/ci.sh` runs the unit tests of `TMessagesProj_FeedTests` (`:TMessagesProj_FeedTests:test`) and builds the debug APK, and exits with the first failing step's code; it needs `JAVA_HOME` and `ANDROID_HOME` as in section 2. `TMessagesProj_AppTests` holds Telegram's instrumented tests and is not run.

The gradle property `TGFEED_ABIS` (comma-separated ABI names) limits the ABIs the native code is built for; without it all four are built.

`.github/workflows/ci.yml` runs `tool/ci.sh` on every push and pull request on an Ubuntu runner with Temurin 17, the pinned NDK and CMake, for `arm64-v8a` only, and keeps the debug APK as the artifact `debug-apk`. It writes `secrets/` from the repository secrets `TG_API_ID`, `TG_API_HASH` and `GOOGLE_SERVICES_JSON_BASE64` (`base64 -w0 google-services.json`) and fails when one is missing.

## 11. Releases

Releases are automatic. `.github/workflows/release.yml` runs on every push to `master` and computes the next version from the conventional commits since the last tag `vX.Y.Z` with `tool/next_version.sh`: a breaking change (`type!:` or a `BREAKING CHANGE:` footer) raises the major version, or the minor one while the major is 0; `feat` raises the minor; `fix` and `perf` raise the patch; commits of other types alone (`docs`, `chore`, `test`, `ci`, `refactor`) release nothing. Without any tag the version is 0.1.0. When a release is due the workflow runs the unit tests, builds `:TMessagesProj_App:assembleAfatRelease` with all four ABIs, signed with the keystore from the secret `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.keystore`, written over the dummy `TMessagesProj/config/release.keystore` in the runner's checkout) and the gradle properties `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` from the secrets `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`, tags the commit and publishes `unofficial-telegram-feed-<version>.apk` on the GitHub release with a changelog (breaking changes, features, fixes, with the task id as the scope). It needs the same three secrets as `ci.yml` and fails when any secret is missing. Only the tip of `master` is released.

The version of a release is passed to gradle by the workflow: `APP_VERSION_NAME` is the computed version and `APP_VERSION_CODE` is the number of commits on `master` (`git rev-list --count HEAD`), which grows with every push; Telegram's build script multiplies the code by ten and adds the flavour's digit. `gradle.properties` holds `dev` and `1` for local builds. `TELEGRAM_BASE_VERSION` in the same file names the Telegram version the fork is built on; it reaches the code as `BuildVars.TELEGRAM_BASE_VERSION`, the version line at the bottom of Settings shows the fork's version with its code and then "Telegram for Android v<base>", the release notes open with it, and an upstream merge updates it. `BuildVars.CHECK_UPDATES` is off, so the app never asks for Telegram's own updates.

The keystore is created once, locally, and kept out of git:

```bash
keytool -genkey -v -keystore release.keystore -keyalg RSA -keysize 2048 -validity 10000 -alias <alias>
```

A local signed build puts that keystore at `TMessagesProj/config/release.keystore` and passes the three properties on the command line (`-PRELEASE_STORE_PASSWORD=...`); the dummy keystore and passwords in `gradle.properties` are Telegram's and sign nothing that is published.
