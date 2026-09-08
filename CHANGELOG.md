# Changelog — 白い熊 メッセージ (fork) and Fossify Messages (upstream)

This file carries **both** histories. The fork's own releases come first, newest first; everything
from the second `# Changelog` heading downwards is Fossify's original changelog, kept byte-for-byte
as upstream writes it so that a rebase merges it cleanly instead of conflicting.

## 白い熊 メッセージ 1.9.1+019 — 2026-09-08

Built on **Fossify Messages 1.9.1** · app id `shiroikuma.messeji`, so it installs side-by-side with the official build.

**This release fixes silent data loss on restore.** Anyone restoring this app onto a new or wiped phone should take this build, and should not trust a "success" reported by any earlier one.

## 🚨 Fixed — a restore reported 4,259 messages into an empty phone

Restoring onto a new phone put back every category, reported `OK:7 categories restored` and `Messages (SMS · MMS): 4259`, and left the app opening on **"No stored conversations have been found"**. The backup was perfect; the messages simply never landed.

Android only lets the **default SMS app** write to the message store, and it does not enforce that with an error. It is enforced as a denied `WRITE_SMS` app-op, and a denied app-op makes the Telephony provider **discard the insert and still hand back a Uri** — no exception, no null return. The importer counted attempts rather than writes and threw the insert's result away, so 4,259 no-ops were counted as 4,259 restored messages and reported as a success.

The proof was in the app's own behaviour: two consecutive restores each reported the same `4259`. The writer checks for a duplicate before every insert, so had the first run written anything, the second would have counted far fewer.

- **Every message is read back after it is written**, and only a message the store confirms is counted. `writeMmsMessage` already performed that read-back internally to obtain the row id it needs before writing parts and addresses — it simply never reported it.
- **A refusal is detected from the writes themselves.** If the store accepts none of the first fifty, the restore stops there rather than spending thousands of round trips proving it. Fifty rather than one, because a single MMS whose addresses yield no usable thread id can legitimately fail on its own merits.
- **The reply is an error, not an `OK`.** The remaining categories are still applied — they work, and they are useful — but the job comes back as a failure, so a backup tool records the restore as incomplete instead of filing it as done. Re-running once the phone is ready is idempotent: the writer skips anything already present.

This is the caveat [`1.9.1+012`](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases/tag/1.9.1%2B012) documented in its `requires_permissions` note — that granting `READ_SMS` and `WRITE_SMS` is still not sufficient — now actually enforced instead of merely written down.

## 🧭 An error that says what to do, and never contradicts your screen

The first cut of this fix asked `Telephony.Sms.getDefaultSmsPackage()` before writing anything, and refused a phone that was holding the SMS role all along: Android Q moved that role into `RoleManager`, and on EMUI the legacy `Settings.Secure` value the old call reads is still `null` while `dumpsys role` reports the role held. The result was a restore refused with "not the default SMS app" while the Settings screen in front of you named 白い熊 メッセージ as the SMS app.

- **No role API gates anything.** The refusal is decided solely by writes not landing. The role check asks `RoleManager` on Android 10 and later — as the app's own startup path always did — and is used only to *explain* a refusal, never to cause one.
- **Three states, three instructions**, carried in the reply itself because that is where you actually read it — in the backup tool's operation log, not in a system log you have to go fishing for:
  - **not the SMS app** — set it in Settings → Apps → Default apps → SMS app, then restore again;
  - **shown as the SMS app, writes still refused** — the setting was recorded without the permission that goes with it, so pick a *different* app and then pick 白い熊 メッセージ again; re-applying it is what grants the permission;
  - **everything checks out and it still failed** — restart the phone and try once more.
- Each one ends by saying that **nothing has been lost and the messages are still in the backup**, because that is the first thing worth knowing.
- The distinction is drawn from the `WRITE_SMS` app-op, which is what the provider actually consults. If the app cannot read its own app-op it assumes it is allowed — it will never accuse your phone on a guess.

## 📡 Fixed — an import that went silent for its longest phase

An import spools the caller's archive, unzips it and parses the whole corpus before it can count a single message. The heartbeat stayed silent until that first count, so the longest stretch of the longest operation was the one stretch that emitted **nothing at all** — and a backup tool cannot tell a slow app from a dead one when both say the same nothing. On a batch restore, a メッセージ import was heard from zero times and failed as dead after ten minutes.

- **The channel now speaks the moment a job starts** and beats from there, carrying the contract's own "no count yet" value rather than inventing a number.
- **The spool counts bytes**, so the phase that reads the caller's descriptor — the read that can actually block — reports real progress instead of silence.
- **Every exit path replies.** The progress channel is built *inside* the guarded block (it used to sit above it, where anything it threw killed the worker with the reply address in scope and unused), and the worker catches `Throwable` rather than `Exception`, so an `OutOfMemoryError` — entirely possible when an import holds the archive and the parsed corpus in memory at once — can no longer exit in silence.

## 🔢 Fixed — counters that read as broken

- **The export's counting phase reported `0/1443`**, a numerator pinned at zero against a climbing denominator, which reads as an export stuck on its first message rather than one still working out how many there are. It now reports "no count yet" and puts the running subtotal in words: 「メッセージを数えています… 2026件」.
- **Progress lines were rendered twice** — `Messages 0/1443 0/1,443 Messages` — because this app sent its own pre-formatted line under the key the backup tool uses as a *label* to place before the counts it formats itself. The words now travel only on lines that carry no numbers to collide with.
- **The in-app Export/Import dialog no longer throttles uncounted lines into silence.** Its "show every twentieth" rule is meaningless without a count and used to drop every such line, blanking the panel for exactly the phase the line exists to describe.

## 白い熊 メッセージ 1.9.1+012 — 2026-09-05

Built on **Fossify Messages 1.9.1** · app id `shiroikuma.messeji`, so it installs side-by-side with the official build.

**This release fixes a live defect in [`1.9.1+011`](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases/tag/1.9.1%2B011).** Anyone on `+011` should take this build.

> **Note on testing:** the phone currently has `1.9.1+011` installed, so this release is *ahead* of the build that has been used. The fix below is verified by build and review, not yet by a cold-start restore on the device.

## 🩹 Fixed — a refused foreground start left the caller waiting forever

The data door starts a foreground service to do the actual export or import. On Android 12 and later that promotion can be **refused outright** when the app is cold, which is exactly the case this whole feature exists for: an unattended backup batch, or a restore onto a freshly wiped phone.

`+011` caught that refusal, closed the caller's file descriptor and stopped — but **never answered**. By that point the caller had already, and correctly, been handed `OK:<job id>`, because starting the service had succeeded; only the promotion inside it failed. So the backup tool sat waiting out its entire timeout on a job that was never going to report, and the result was indistinguishable from an app that had never implemented the contract at all.

The service now reads the reply address **before** the call that can fail, answers a refusal with a proper terminal error, releases the descriptor and stops. Catching the exception was never the fix; answering it is.

- **The refusal is classified, so the repair offered actually matches the fault.** A dedicated `no-foreground-start` error is reported only when the throwable is the not-allowed exception **and** the app is not already exempt from battery optimisation. If the exemption is already held and the start was still refused, the cause is something a battery-settings button cannot touch, and the error stays descriptive instead of sending you to a setting that is already correct.
- **That exception class is matched by name, never by type.** It arrived in Android 12 and this app supports Android 8 and up, so a direct reference would fail to load on an older device — in a `catch` block, the natural way to write it is the wrong way.
- **The provider's own start site was deliberately left alone.** It returns its refusal as the return value of the call and never hands out a job id for work that will not run, so adding a broadcast there would answer the caller twice. Two start sites, two different correct fixes.

**How it was found:** the sister-app contract this implements is corrected in place while apps are being built from it, and now carries a revision stamp. Checking that stamp showed the document had moved eight minutes after `+011` was built — and re-reading it turned up this defect in already-published code.

## 🔐 The restore now declares what it needs granted

`describe` gained a `requires_permissions` field, so a backup tool can ask for what a restore needs **before** streaming an archive rather than reporting a failure afterwards. A freshly installed app holds no runtime permissions at all, and the restore order is install → do not launch → import.

Derived from what the restore actually writes rather than from the kind of app this is: every settings-backed category needs nothing, while the **messages** category writes SMS and MMS rows, parts and addresses straight into the system Telephony provider. It therefore declares `READ_SMS` and `WRITE_SMS`.

**One caveat that the field cannot express**, stated here so it is not mistaken for a fault: granting both is still not sufficient, because the operative gate on that provider is being the **default SMS app** — a role you assign, and no permission prompt can hand it over. A non-empty declaration means "at least this", never "only this".

## Unchanged on purpose

The headless export triggered by the older broadcast still runs inside the broadcast itself rather than on a service of its own, which is a real limitation for a large message archive. It predates this work and changing it is a change of mechanism rather than a fix, so it is left as a recommendation instead of being altered in a release meant to correct one specific defect.

## 白い熊 メッセージ 1.9.1+011 — 2026-09-04

Built on **Fossify Messages 1.9.1** · app id `shiroikuma.messeji`, so it installs side-by-side with the official build.

A packaging release: the fork's build counter is now **zero-padded to three digits**, matching every sister app. No behaviour changes — the automation contract v2 work landed in [`1.9.1+10`](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases/tag/1.9.1%2B10).

## 📦 Packaging

- **The build counter is zero-padded to three digits** — `1.9.1+011` rather than `1.9.1+11`. Unpadded, a plain string sort puts `+10` before `+9`, so downloads, release pages and tag lists stopped reading in build order exactly when there were enough builds for it to matter.
- The padding is applied in **one place**, the single value both the `versionName` and the APK filename are built from, so the artefact, the tag, the release title and the version the app reports about itself cannot drift apart.
- **`versionCode` is unchanged in kind** — still `<upstream code> × 10000 + <counter>`, so `230011` follows `230010` and this installs over an existing build normally.
- **Earlier releases are not retagged.** `1.9.1+8` and `1.9.1+10` stay exactly as published; padding starts from this build, so padded tags will sort below the older ones for a while.

## 白い熊 メッセージ 1.9.1+10 — 2026-09-04

Built on **Fossify Messages 1.9.1** · app id `shiroikuma.messeji`, so it installs side-by-side with the official build.

This release lands **automation contract v2**: the sister-app backup surface stops depending on a secret that has to be pasted by hand, and gains a verified **data door** so 白い熊 応用管理 can back this app up *with its messages* and restore it onto a wiped phone. This is also the fork's first changelog entry, so everything built on top of stock is listed below — the v2 work is marked **new in +10**.

## 🆕 New in 1.9.1+10 — automation contract v2

- **The automation switch ships ON, and the token is now optional.** A new 「認証トークンを使用しますか？」 row sits beneath the master switch, **off by default**; the token row itself appears only when you ask for one, so a 48-character secret is never left sitting under a switch that ignores it. The reason is concrete: a pasted secret cannot survive a wipe, and restoring a wiped phone is exactly when the backup surface has to work.
- **A token sent to the app while it does not require one is ignored, never refused.** Tokens outlive the setting they were pasted for, and a caller still sending one must be served rather than failing half a backup batch.
- **One gate, one place.** The master switch and the token check now live in a single function that every entry point calls, so "disabled" and "bad token" cannot drift apart between doors.
- **New: a data door for backup *with data*.** A `ContentProvider` answering `describe` / `export` / `import` / `cancel` — a provider rather than a broadcast because **a broadcast cannot tell you who sent it**. Every caller is verified three ways before a byte moves: **exact package name** (never a prefix, which any sideloaded app could claim), the **uid the kernel reports**, and a **pinned signing certificate**.
- **The archive travels through a file descriptor the caller opened** — not a path, not a URI. The app never writes into someone else's backup directory, and the permission expires when the descriptor closes.
- **Restoring exists only at that door.** An import overwrites this app's data, so it is deliberately absent from the unauthenticated broadcast surface, where it would let any app on the phone rewrite your message history.
- **Capability discovery without waking the app** — three manifest entries readable even while the app is frozen, so a backup tool can tell whether this app can be backed up without launching it.
- **Fixed: a restore could report success while its settings never reached disk.** The restoring caller force-stops the app the instant it hears success — deliberately, since a shutting-down process would write stale settings back over the restore — and that force-stop is a `SIGKILL`, which discards an asynchronous preference write still in flight. Preferences are now committed synchronously before success is reported. Every other write on the restore path was audited and was already durable: message rows go through the system provider, MMS attachment bytes through a stream closed before return, fonts through a closing file write.
- **Fixed: a long export could look dead while working perfectly.** Progress was throttled but not heartbeated, so an export that stopped reporting stopped broadcasting. Since the data door writes into a descriptor that may be a pipe, a single write blocks for as long as the caller is slow to drain it — and this app streams every MMS attachment through it. The last true progress line is now re-sent every 20 seconds while a write stalls; nothing is invented, it repeats the truth rather than fabricating movement.

## 📦 Export / Import — everything, by category

- A full **category export/import** on the 白い熊 メッセージ UI page: the messages themselves (SMS · MMS as stock-backup-compatible JSON), theme & colours, fonts *including imported font files*, date & time formats, app settings, conversations, and blocked keywords — all into **one ZIP**.
- Live **done/total counters** while thousands of messages stream through, and an import that **merges** per key rather than clearing, skipping categories the archive does not carry.
- Pick an export directory once; the page then shows the newest export at a glance.
- **Headless export** driven by 白い熊 自由作業盤's 保存復元 batch: the same export, no screen and no tapping, writing one ZIP wherever it is told to and replying with the path and real byte size.
- Progress as **real counts, never a percentage** — 「区分 3/7 — 設定」 walking the categories, 「メッセージ 1234/8942」 through the messages.
- The app **states which items should start ticked**, so the caller's picker opens on the same selection as the app's own.
- A running export can be **cancelled from outside**: it unwinds at the next entry boundary, deletes the half-written archive, and leaves the backup directory exactly as it found it.
- Archives are written to a temporary name and renamed only once complete, so a killed export never leaves something that looks like a backup.
- The automation switch, its token requirement and the token are all **excluded from the export**, so a restored archive can neither carry the secret nor silently re-gate the app.

## 🎨 UI & theming

- **Granular per-element Theme & Colors system** with two-tier inheritance: foundation slots (background / primary / text) drive everything until a specific element is overridden.
- **Black `#000000` + pure yellow `#FFFF00`** as the seeded default — pure yellow, never material `#FFEB3B`; previously persisted material yellow is migrated once.
- **Per-element fonts**: family, weight and size per themed text element, with a live sample that redraws as you tweak, and an "Add font…" import that makes any font file available everywhere.
- **Alpha/transparency slider** in the colour picker (stock is opaque-only) plus a shared recently-used-colours row.
- Black/yellow chrome throughout — overflow menu, contextual action bar, popup menus, dialogs, and **toasts**.
- A **設定 toolbar pair**: 設 opens the UI page, 定 the regular Settings screen; each glyph is its own themeable element. Long-pressing the ⋮ overflow icon also jumps to the UI page.
- A brush-stroke yellow **人 glyph** for contacts without a photo, replacing the generated coloured-letter avatar everywhere it appears.
- Restyled app icon: yellow line-art bubble on black.
- Reorganised and prominently indented UI settings page hierarchy.

## 🕐 Japanese time & imperial-era dates

- Today's messages show a **Sino-Japanese clock reading** (午前八時); older ones an **imperial-era date** (令和八年五月十四日（木曜日）) — in the conversation list, in-thread date separators, and search results.
- Configurable under 日時の表示形式: kanji / system / 24-hour / 12-hour, plus an imperial-dates toggle.

## 🔓 Packaging & platform

- Built against **patched Fossify Commons**, which removes the anti-tamper "fake version" check that misfires on any renamed fork, and fixes Commons' hard-coded `org.fossify.*` assumptions — including reading the Contacts app's shared private contacts. No in-app workaround is carried as a result.
- App id **`shiroikuma.messeji`** with the code namespace deliberately left as `org.fossify.messages`, so only the installed package differs.
- Launcher label 白い熊 メッセージ in both English and Japanese resources.
- Fork versioning: `versionName` `<upstream>+<N>`, `versionCode` `<upstream code> * 10000 + N`.
- De-branded issue templates pointing at this fork.

---

# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.9.1] - 2026-07-19
### Changed
- Updated translations

### Fixed
- Fixed missing private contact names in message list and threads ([#829])

## [1.9.0] - 2026-07-12
### Added
- Added a group message format choice on the first group send ([#52])

### Changed
- Updated translations

### Fixed
- Partially fixed issue with sending MMS images ([#45])
- Fixed slow loading of the conversation list ([#234])

## [1.8.1] - 2026-07-09
### Changed
- Updated translations

### Fixed
- Fixed messages being sent to the wrong contact ([#615])
- Fixed incomplete message exports ([#713])
- Fixed crash when viewing older messages
- Other stability improvements

## [1.8.0] - 2026-01-30
### Added
- Added support for custom fonts
- Added "Copy number to clipboard" option inside chat overflow menu ([#651])

### Changed
- Improved multi-message copy formatting with timestamps and sender names
- Updated translations

### Fixed
- Fixed missing notifications in some cases ([#159])
- Fixed incorrect blocking of MMS messages in some rare cases ([#644])
- Fixed issue with importing alphanumeric blocked numbers ([#282])
- Fixed issue where scheduled messages were not sent after a reboot or app updates ([#641])

## [1.7.0] - 2025-12-16
### Added
- Ability to select and copy multiple text messages at once ([#600])

### Changed
- Updated translations

### Deprecated
- Deprecated the recycle bin feature ([#290])

### Fixed
- Fixed new conversation shortcut ([#416])
- Fixed blocking MMS messages from unknown numbers ([#610])

## [1.6.0] - 2025-10-29
### Changed
- Compatibility updates for Android 15 & 16
- Calling now works directly without launching dialpad ([#562])
- Search bar is now pinned to the top when scrolling
- Updated translations

### Fixed
- Fixed freezing when sending messages ([#574])

## [1.5.0] - 2025-10-18
### Added
- Unread badge count for conversations ([#177])

### Changed
- Optimized loading messages in conversations ([#234])
- Updated conversation item design to be more compact ([#376])
- Pin/unpin actions now always show as action buttons in the menu ([#561])
- Updated translations

### Fixed
- Fixed position reset when opening attachments in conversations ([#82])
- Fixed automatic scroll to searched message in conversations ([#350])
- Fixed non-standard text and avatar sizes in list items
- Fixed "Mark as read" not working in some cases ([#264])

## [1.4.0] - 2025-10-12
### Added
- Ability to save multiple attachments ([#75])
- Ability to select numbers that aren't starred when starting a new conversation ([#153])

### Changed
- Reordered menu options throughout the app
- Updated translations

### Fixed
- Fixed keyword blocking for MMS messages ([#99])
- Fixed contact number selection when adding members to a group ([#456])
- Fixed a glitch in pattern lock after incorrect attempts
- Fixed disabled send button when sending images without text ([#165])

## [1.3.0] - 2025-09-09
### Added
- Option to keep conversations archived ([#334])

### Changed
- Updated translations

## [1.2.3] - 2025-08-21
### Changed
- Updated translations

### Fixed
- Fixed stale/missing notification badge on some devices

## [1.2.2] - 2025-08-01
### Changed
- Updated translations

### Fixed
- Fixed inability to view messages when there is no SIM card ([#461])

## [1.2.1] - 2025-06-17
### Changed
- Preference category labels now use sentence case
- Updated translations

## [1.2.0] - 2025-06-04
### Added
- Conversation shortcuts ([#209])

### Changed
- Updated translations

## [1.1.7] - 2025-04-01
### Changed
- Added more translations

### Fixed
- Fixed incorrect cursor position when reopening the app ([#349])
- Fixed scrolling issue on conversation details screen ([#359])

## [1.1.6] - 2025-03-24
### Changed
- Other minor fixes and improvements
- Added more translations

### Removed
- Removed storage permission requirement ([#309])

### Fixed
- Fixed crash when viewing messages
- Fixes incorrect author name in group messages ([#180])

## [1.1.5] - 2025-02-02
### Changed
- Added more translations

### Fixed
- Fixed issue with third party intents ([#294])
- Fixed toast error when receiving MMS messages ([#287])
- Fixed RTL layout issue in threads ([#279])

## [1.1.4] - 2025-01-23
### Changed
- Added more translations

### Fixed
- Fixed issue with forwarding messages ([#288])

## [1.1.3] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.2] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.1] - 2025-01-04
### Changed
- Improved third party SMS/MMS intent parsing ([#217], [#243])
- Modified short code check to exclude emails ([#115])
- Other minor bug fixes and improvements
- Added more translations

### Fixed
- Fixed issue with messages draft deletion ([#13])
- Fixed multiple toast errors for MMS messages ([#70], [#262])
- Fixed some layout issues in message thread ([#135])

## [1.1.0] - 2024-12-27
### Changed
- Replaced checkboxes with switches
- Improved app lock logic and interface
- Other minor bug fixes and improvements
- Added more translations

### Removed
- Removed support for Android 7 and older versions

### Fixed
- Fixed various issues related to importing/exporting messages
- Fixed keyword blocking for MMS messages
- Fixed issue with messages draft deletion

## [1.0.1] - 2024-02-09
### Changed
- Minor bug fixes and improvements
- Added some translations

## [1.0.0] - 2024-01-24
### Added
- Initial release

[#13]: https://github.com/FossifyOrg/Messages/issues/13
[#45]: https://github.com/FossifyOrg/Messages/issues/45
[#52]: https://github.com/FossifyOrg/Messages/issues/52
[#70]: https://github.com/FossifyOrg/Messages/issues/70
[#75]: https://github.com/FossifyOrg/Messages/issues/75
[#82]: https://github.com/FossifyOrg/Messages/issues/82
[#99]: https://github.com/FossifyOrg/Messages/issues/99
[#115]: https://github.com/FossifyOrg/Messages/issues/115
[#135]: https://github.com/FossifyOrg/Messages/issues/135
[#153]: https://github.com/FossifyOrg/Messages/issues/153
[#159]: https://github.com/FossifyOrg/Messages/issues/159
[#165]: https://github.com/FossifyOrg/Messages/issues/165
[#177]: https://github.com/FossifyOrg/Messages/issues/177
[#180]: https://github.com/FossifyOrg/Messages/issues/180
[#209]: https://github.com/FossifyOrg/Messages/issues/209
[#217]: https://github.com/FossifyOrg/Messages/issues/217
[#225]: https://github.com/FossifyOrg/Messages/issues/225
[#234]: https://github.com/FossifyOrg/Messages/issues/234
[#243]: https://github.com/FossifyOrg/Messages/issues/243
[#262]: https://github.com/FossifyOrg/Messages/issues/262
[#264]: https://github.com/FossifyOrg/Messages/issues/264
[#274]: https://github.com/FossifyOrg/Messages/issues/274
[#279]: https://github.com/FossifyOrg/Messages/issues/279
[#282]: https://github.com/FossifyOrg/Messages/issues/282
[#287]: https://github.com/FossifyOrg/Messages/issues/287
[#288]: https://github.com/FossifyOrg/Messages/issues/288
[#290]: https://github.com/FossifyOrg/Messages/issues/290
[#294]: https://github.com/FossifyOrg/Messages/issues/294
[#309]: https://github.com/FossifyOrg/Messages/issues/309
[#334]: https://github.com/FossifyOrg/Messages/issues/334
[#349]: https://github.com/FossifyOrg/Messages/issues/349
[#350]: https://github.com/FossifyOrg/Messages/issues/350
[#359]: https://github.com/FossifyOrg/Messages/issues/359
[#376]: https://github.com/FossifyOrg/Messages/issues/376
[#416]: https://github.com/FossifyOrg/Messages/issues/416
[#456]: https://github.com/FossifyOrg/Messages/issues/456
[#461]: https://github.com/FossifyOrg/Messages/issues/461
[#561]: https://github.com/FossifyOrg/Messages/issues/561
[#562]: https://github.com/FossifyOrg/Messages/issues/562
[#574]: https://github.com/FossifyOrg/Messages/issues/574
[#600]: https://github.com/FossifyOrg/Messages/issues/600
[#610]: https://github.com/FossifyOrg/Messages/issues/610
[#615]: https://github.com/FossifyOrg/Messages/issues/615
[#641]: https://github.com/FossifyOrg/Messages/issues/641
[#644]: https://github.com/FossifyOrg/Messages/issues/644
[#651]: https://github.com/FossifyOrg/Messages/issues/651
[#713]: https://github.com/FossifyOrg/Messages/issues/713
[#829]: https://github.com/FossifyOrg/Messages/issues/829

[Unreleased]: https://github.com/FossifyOrg/Messages/compare/1.9.1...HEAD
[1.9.1]: https://github.com/FossifyOrg/Messages/compare/1.9.0...1.9.1
[1.9.0]: https://github.com/FossifyOrg/Messages/compare/1.8.1...1.9.0
[1.8.1]: https://github.com/FossifyOrg/Messages/compare/1.8.0...1.8.1
[1.8.0]: https://github.com/FossifyOrg/Messages/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/FossifyOrg/Messages/compare/1.6.0...1.7.0
[1.6.0]: https://github.com/FossifyOrg/Messages/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/FossifyOrg/Messages/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/FossifyOrg/Messages/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/FossifyOrg/Messages/compare/1.2.3...1.3.0
[1.2.3]: https://github.com/FossifyOrg/Messages/compare/1.2.2...1.2.3
[1.2.2]: https://github.com/FossifyOrg/Messages/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/FossifyOrg/Messages/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/FossifyOrg/Messages/compare/1.1.7...1.2.0
[1.1.7]: https://github.com/FossifyOrg/Messages/compare/1.1.6...1.1.7
[1.1.6]: https://github.com/FossifyOrg/Messages/compare/1.1.5...1.1.6
[1.1.5]: https://github.com/FossifyOrg/Messages/compare/1.1.4...1.1.5
[1.1.4]: https://github.com/FossifyOrg/Messages/compare/1.1.3...1.1.4
[1.1.3]: https://github.com/FossifyOrg/Messages/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/FossifyOrg/Messages/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/FossifyOrg/Messages/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/FossifyOrg/Messages/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/FossifyOrg/Messages/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/FossifyOrg/Messages/releases/tag/1.0.0
