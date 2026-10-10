# Unofficial Telegram Feed — Plan

Open work only. A task leaves this file in the commit that finishes it; `git log` records finished work.

Legend: `[ ]` not started, `[~]` in progress (name the branch).

**Current phase:** P5 — Release 1.0. **Next task:** P5-1.

The fork of the official Telegram Android client (DrKLO/Telegram, 12.10.6) that takes over three features of the Flutter app `telegram-feed`: feeds that group channels into one timeline, keyword rules per channel that decide which posts notify and are read aloud, and a switch that hides stories. Everything else stays as the official app has it. Each phase from P1 on ends with a tagged release.

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
