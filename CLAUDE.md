# Unofficial Telegram Feed — instructions for Claude sessions

A fork of the official Telegram app for Android (DrKLO/Telegram) with three additions: feeds that group channels into one timeline, keyword rules per channel that decide which posts notify and are read aloud, and a switch that hides stories. Everything else stays as the official app has it. Open source, GPL-2.0 (Telegram's licence).

## Session start (mandatory)

1. Read the Kanban view (id 41) of the Vikunja project "Unofficial Telegram Feed" (project id 9) with the vikunja MCP, `fields` limited to bucket titles and the tasks' identifier, title, priority and labels. A task in "Doing" is in progress; its comments name the branch and what is left.
2. Run `git log --oneline -20` to see what the last sessions did.
3. Only then start work, on the task the user names or, if they did not name one, the task in "Doing", else the first task of "To-Do". Move the task to "Doing" when work on it starts.

## Session end (mandatory)

1. Update Vikunja. The board's buckets are Backlog → To-Do → Doing → User validation required → Done. A finished task goes to "User validation required" with a comment on what changed and how the user checks it; only the user moves a task to "Done". A task left unfinished stays in "Doing" with a comment naming its branch and what is left. New work found (bugs, follow-ups) becomes a task in "Backlog".
2. Commit with a conventional message (`feat:`, `fix:`, `docs:`, `chore:`, `spike:`), one commit per task. Include the Vikunja task identifier as the scope, e.g. `feat(UTF-7): feeds tab`. The type decides the release: `feat` and `fix` on `master` publish a new version by themselves (`docs/ARCHITECTURE.md`, Releases).
3. Never leave work uncommitted at the end of a session.

## Rules

- Tasks live in Vikunja, never in the repo: no plan files, TODO lists or task notes in markdown. The repo holds docs and stable information only. "To-Do" holds the work of the current release, "Backlog" the work after it. Labels: `release`, `upstream`, `feature`, `bug`, `decision`, `spike`.
- Decisions are asked, never deferred. If something is undecided, ask the user with the question tool right away. Do not write "open question", "TBD", or "decide later" anywhere.
- Emulator only. Never propose or attempt running on the user's personal phone. The `Pixel_10` AVD (Android 16, x86_64); the build recipe is in `docs/ARCHITECTURE.md`.
- Telegram login on the emulator uses a spare real account on the production DC, never the user's main account. The user types the phone number and SMS code into the emulator; Claude never enters them.
- Secrets are never committed. `api_id`, `api_hash` and the Firebase config live in the gitignored `secrets/` directory and reach the build through gradle (`docs/ARCHITECTURE.md`, Secrets). Never print their values.
- The fork's own code lives in the package `org.unofficial.telegramfeed` inside `TMessagesProj`. Every edit to a Telegram file carries a `TGFEED` comment on the changed lines (`// TGFEED`, `<!-- TGFEED -->`), so upstream merges find it.
- Every new UI string goes into `TMessagesProj/src/main/res/values/strings.xml` and `values-uk/strings.xml`, in English and Ukrainian, in the same commit.
- Spec and architecture live in `docs/SPEC.md` and `docs/ARCHITECTURE.md` and describe the app as it is now. When a decision changes, update SPEC.md's decision table and the relevant sections of both files in the same commit.
- Docs and READMEs hold clear factual statements only: no history, dates, task ids, verification narratives or reasoning trails. `git log` is the history.
- Spikes go on `spike/<name>` branches. Their lasting findings go into `docs/ARCHITECTURE.md`.
- Upstream (DrKLO/Telegram) is merged on every Telegram release, following the procedure in `docs/ARCHITECTURE.md`. The Huawei, HockeyApp and Standalone modules stay untouched for those merges.
- `tool/ci.sh` must pass before a task is committed.

## Keeping sessions small

Telegram's files are huge (`ChatActivity.java` has 47k lines), so one careless read costs more than a task's worth of edits, and a long session buries the details the next fix needs. These rules hold in every session.

- One Vikunja task per session. The session ends with the task's commit; the next task gets a new session. Two sessions never work in this checkout at once.
- No file is read whole. `grep -n` first, then ranges of at most 150 lines. A file is not re-read after an edit. CodeGraph queries name the symbols and cap files at 4. `docs/ARCHITECTURE.md` is read by section: its headings first, then the section.
- Builds go through `tool/build.sh` (`debug`, `release` or `ci`), which keeps gradle's output in a log and prints only the result, and they run in the background. `tool/ci.sh` is run as `tool/build.sh ci`.
- Logcat is read filtered and tailed (`adb logcat -d | grep -E 'AndroidRuntime|<tag>' | tail -40`), never whole.
- Layouts are measured with `tool/layout.sh`, which prints the screen's view tree with bounds in dp. A screenshot is taken only for what the tree cannot show (colours, drawing, a wrong glyph), one per check.
- Diffs are reviewed per file (`git diff --stat`, then `git diff -- <file>`), never a whole session's diff at once.
- Command output is capped (`| tail -40`, `| head -60`) unless the whole output is the point.
