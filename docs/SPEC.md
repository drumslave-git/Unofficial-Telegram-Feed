# Unofficial Telegram Feed — Product specification

## 1. Purpose

The official Telegram app for Android with three additions for people who follow many channels: channels combine into feeds read as one timeline, keyword rules per channel decide which posts notify and are read aloud, and a switch hides stories. Chats, calls, folders, settings and everything else are the official app's, unchanged.

## 2. Decisions

| Topic | Decision |
|---|---|
| Name | "Unofficial Telegram Feed" as the app's name, "TG Feed" under the launcher icon: Telegram's API terms allow "Telegram" in an app's title only after "Unofficial". The package is `org.unofficial.telegramfeed`; the launcher icon is the Flutter app's, not Telegram's logo. |
| Base | A fork of the official client, DrKLO/Telegram. Upstream is tracked and merged on every Telegram release. The fork's code lives in its own package inside the app; every edit to a Telegram file is marked. |
| Platform | Android, with the official app's minimum version. Development and testing on an x86_64 emulator. |
| Accounts | Telegram's accounts. Feeds and rules belong to an account; each logged-in account has its own. |
| Feeds | The app's own entity per account, not Telegram's folders. A feed is an ordered list of channels the account has joined. Channels only: groups, bots and private chats are never offered. |
| Feeds tab | Feeds live in a "Feeds" tab in the chat list's tab bar, after "All chats" and before the folders. |
| Feed view | One chronological timeline of the posts of all the feed's channels, oldest on top and newest at the bottom, drawn as the official app draws a chat, with every post carrying its channel's name and photo. |
| Feed filters | A feed can limit what it shows: all posts, only media or only text, which media types, a minimum video length, a minimum text length, and a word condition built like a rule's. What a feed leaves out is gone from it, or stays as one folded line when the feed shows such posts minimized. |
| Read state | Telegram's own: one read position per channel, shared by every feed, the channel itself and the official app. |
| Rules | A rule belongs to one channel and may be scoped by a feed, in which case it sees only the posts that feed shows. Conditions combine terms with AND / OR / NOT, with phrases, whole word and case sensitivity, in a visual builder or as text, with the Flutter app's grammar. A rule has a priority (silent, normal, urgent), may ask for read-aloud, and may have a schedule of weekdays and two times. |
| Rule notifications | A channel with at least one enabled rule notifies only when a rule matches; channels without rules keep Telegram's own notifications. A matching post notifies as Telegram notifies for the channel, named with its rule, at the highest matching priority. |
| Rule text | Post text and media captions only. Edited posts are not matched again. Rules match only posts newer than their creation. |
| Read aloud | The device's text-to-speech, with the post's language detected per post; a banner under the action bar stops it, and so do volume down and a headset's pause. |
| Pause | One bell-with-slash button in the chat list's action bar silences every rule and the speech until it is pressed again, across restarts. |
| Stories | A device-wide switch, off at first, hides every trace of stories: the stories bar, the archive's stories, profile rings and tabs, story notifications, reposting and sharing to stories, the stories camera, and the loading of stories at all. |
| Storage | Feeds and rules are stored locally per account in the app's own SQLite file, separate from Telegram's database. No sync. |
| Push | Telegram's push through the Flutter app's Firebase project. Telegram pushes only unmuted channels, so a channel with rules has to be unmuted in Telegram. |
| Interface language | The fork's own strings are in English and Ukrainian; Telegram's strings come in every language Telegram has. |
| Distribution | APKs on GitHub Releases, Google Play later. |
| Licence and money | GPL-2.0, the licence of the official client. No monetization. |
| API credentials | `api_id`, `api_hash` and the Firebase config are never committed; every build supplies its own from the gitignored `secrets/` directory, with the Firebase project's FCM credentials uploaded at my.telegram.org for its `api_id`. |

## 3. User stories

The primary user follows 20 to 200 Telegram channels (news, niche communities, alerts, deals) and is overwhelmed by the official app's flat chat list and all-or-nothing notifications.

### Stories

- In Settings, in a section "Unofficial Telegram Feed" before Chat Settings, I turn "Hide stories" on or off. It is off at first and holds for every account on the device.
- With it on, nothing of stories is left: no stories bar over the chat list or in the archive, no story rings around photos and no stories tabs in profiles, no story notifications, no "Repost as story", no sharing to a story in the forward sheet, no stories camera and no hint for it; a story mentioned or replied to in a chat is plain text. Stories are not loaded at all. Turning the switch applies at once, without a restart.

### Feeds and channels

- The "Feeds" tab lists my feeds: name, how many channels, how many of them have new posts, and the unread count in the accent colour. Its "New feed" button creates one, empty or with the channels of a Telegram folder (a one-time copy); a drag handle reorders; a row's menu edits the channels, renames, marks the feed read or deletes it, saying how many rules go with it.
- With no feeds yet, the tab says what a feed is and offers to create one.
- The tab counts the channels with unread posts once, however many feeds hold them. A tap on the tab that is already open scrolls its list to the top.
- A feed's editor shows its name with a pencil and its ordered channels, each removable with Undo. I add channels from the channels I have joined, with a search box; I tick as many as I want and add them with one press. A checkbox hides the channels that are already in a feed, and the sheet remembers it.
- Every channel row in the chat list tags the channel with the feeds it belongs to. The long-press menu of a channel row adds it to a feed, or creates the first one. A long press on a folder tab creates a feed from the folder's channels.
- The "Count unread posts" switch in Notifications and Sounds decides whether a feed counts posts or channels with unread posts. "Mark as read" on a feed marks every channel of it read.

### Reading a feed

- A feed shows the posts of all its channels in one chronological list, oldest on top and newest at the bottom, with the channel's name and photo on every post; a tap on them opens the channel. Albums stay together.
- Older posts load as I scroll up. New posts appear as they come; edits and deletions apply in place.
- A feed opens where I left it; otherwise at the first unread post under an "Unread posts" divider. Day labels sit between the posts and the day of the topmost post floats over the list. The button to the newest posts carries the unread count.
- Reading a feed marks each channel read up to the newest post I saw in it, so the official app agrees.
- A post's menu is the chat's: reactions, comments, copy, forward, share, save, report, and "Show in chat", which opens the channel at the post. A long press selects several posts. Pictures and videos open in the viewer, which pages through the whole feed.
- I set what a feed shows: all posts, only posts with media or only text; which media types; videos from a minimum length; text posts from a minimum length; and which words, as a condition built like a rule's. With "Show minimized" on, the posts the filter leaves out stay as one line each that a tap opens; with "Show the whole post" on, a post is shown whole when any part of it passes. Hidden and minimized posts are read with the posts around them and count for nothing.
- The magnifier in a feed searches all of its channels. The timeline goes to the newest post that has the words, with the found words marked, and a bar with arrows and "3 of 47" steps through the matches; "Show as list" lists the results under their channels' names. Chips pick media, links, files, music or voice.
- The feed's info screen lists the shared media of all its channels together under the tabs Media, Files, Links, Music, Voice and GIFs.

### Notification rules

- A rule belongs to a channel. I reach a channel's rules from its profile and from its notification settings ("Rules"), every rule from the "Unofficial Telegram Feed" settings section, and a feed's rules from the feed's menu; the list shows every rule under its channel, with the feed's name where the rule is scoped by one.
- A rule's condition is built from terms combined with AND / OR / NOT, in a visual builder or as text with its syntax sheet. A term is a word or phrase with options: whole word, case sensitive.
- A rule has a name and a switch, a channel, an optional feed that limits it to the posts the feed shows, a priority, read-aloud and a schedule of weekdays and two times. The editor explains what each priority does; picking "urgent" offers Android's Do Not Disturb setting. A dry run over the channel's recent posts says how many posts it checked and would have matched. Leaving with unsaved changes asks first.
- A channel with at least one enabled rule notifies only for posts a rule matches. A channel without rules notifies as the official app does.
- A matching post notifies with the rule's name, grouped per channel as Telegram groups a chat. Silent goes to the tray only, normal pops up, urgent breaks through Do Not Disturb where Android permits; each priority has its own notification channel. When several rules match, the highest priority wins, and read-aloud happens if any matching rule asks for it.
- An edited post is not matched again and does not sound again.
- Android has to allow notifications at all; the app asks for that when I save my first rule.
- Notifications and Sounds has a "Rules" block with the sound and vibration of normal and of urgent rule notifications, applied at once.
- A bell-with-slash button in the chat list's action bar pauses every rule and the speech until I press it again, also after the app or the phone restarts. While paused, the button is red and a banner under the action bar of every screen says so, with "Resume".

### While the app is closed

- Rules notify me while the app is closed: Telegram's push wakes the app, which looks at the new posts, notifies, reads aloud and goes back to sleep.
- Telegram pushes only channels that are not muted in Telegram. Saving a rule for a muted channel offers to unmute it, and otherwise says the rule notifies only while the app is open.
- Posts that came while the phone was off or offline are matched when the app connects again, unless I have read them by then. A rule notifies about posts newer than itself.
- The rules screens ask to be let off battery optimisation.

### Read aloud

- When a rule with read-aloud fires, the app speaks "New post in <channel>" followed by the post text, also with the screen off. The words the app adds are in the post's language when it is English or Ukrainian, otherwise in the interface language.
- The app detects the post's language and picks a voice for it. Posts are queued, never spoken over each other and never dropped. Other audio is ducked, and a phone call pauses speech.
- While a post is read, a banner under the action bar of every screen names its channel and how many posts wait, with "Stop" (this post; the next follows) and "Stop and clear queue".
- Volume down stops the post being read and clears the queue without lowering the volume, also with the screen off or locked; a headset's pause does the same. When nothing is read, the keys work as usual.
- Every rule notification carries "Listen", which reads the posts it lists that were not read aloud yet, and all of them when every one was; while one of its posts is read or waits, the action is "Stop" instead. Swiping the notification away and "Clear all" stop its posts.
- Settings, in the "Read aloud" screen of the fork's settings section: speed, pitch, maximum length, language when detection fails, a preview, and voices listed by language, each with a play button; "Add language" picks another from a searchable list, and every other language uses the phone's default voice.

## 4. Screens

1. **Chat list**: the official app's, with the "Feeds" tab in its tab bar, the pause button in its action bar, feed tags on channel rows and "Add to feed" in a channel row's long-press menu.
2. **Feeds tab**: the list of feeds with their counts, "New feed", drag handles and row menus; the empty state.
3. **Feed editor**: name with a pencil, the ordered channels with remove and Undo, the add-channel sheet, the filter settings.
4. **Feed timeline**: the posts of the feed's channels drawn with the official app's message cells in the group layout, the "Unread posts" divider, day labels and the floating day, the button to the newest posts, the post menu, selection, the viewer, the search bar with its list and chips.
5. **Feed info**: the feed's name and channels, its rules, and the shared media tabs over all its channels.
6. **Rules list** and **rule editor**: name and switch, channel, optional feed, the condition as a builder or as text, the dry run, priority, read-aloud, schedule.
7. **Settings**: the "Unofficial Telegram Feed" section before Chat Settings with "Hide stories", "Rules" and "Read aloud"; the "Rules" block and "Count unread posts" in Notifications and Sounds; "Rules" in a channel's profile and notification settings.

## 5. Out of scope

- Everything the official app does is left as it is: chats, groups, calls, folders, Premium, themes, settings.
- Syncing feeds and rules between devices, instant rules through a foreground service, AI semantic rules, and feeds or rules on the app icon's badge count.
- Feeds of anything but joined channels. The app never joins, leaves or searches for channels on a feed's behalf.
- Rules on groups, bots and private chats.
