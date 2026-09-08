<div align="center">

<img src="graphics/icon.webp" width="120" alt="白い熊 メッセージ icon" />

# 白い熊 メッセージ

**A black-and-pure-yellow SMS/MMS app with per-element theming, per-element fonts, and Sino-Japanese time & imperial-era dates.**

A fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) with **major additions**: a granular per-element Theme & Colors system (black `#000000` / pure yellow `#FFFF00` by default), per-element font family/weight/size, full category export/import (messages included), headless backup driven from 白い熊 自由作業盤, a verified data door that lets 白い熊 応用管理 restore this app *with its messages* onto a wiped phone, an alpha-capable color picker, Japanese kanji clock readings and 令和 imperial-era dates, a 設定 toolbar launcher, and a fully black/yellow chrome — menus, action bar, dialogs, and toasts included.

Installs **side-by-side** with Fossify Messages (app id `shiroikuma.messeji`).

**📥 Latest release: [`1.9.1+019`](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-messeji/releases)

</div>

---

## 🎨 Granular per-element theming, black & pure yellow

Every visible element — search bar, conversation list, message bubbles (received / sent / status & time), headers, menus, back arrows — has its own color slot on the **白い熊 メッセージ UI** page, with two-tier inheritance: foundation slots (background / primary / text) drive everything until you override a specific element. The app seeds a black background with pure-yellow (`#FFFF00`) text and accents on first launch, and the whole chrome follows: overflow menu, contextual action bar, popup menus, dialogs, and even toasts render black with a yellow frame.

---

## ✒️ Per-element fonts

Every themed text element can also pick its own **font family, weight, and size**, with a live sample line that redraws as you tweak. Import any font file via an “Add font…” picker and it becomes available to every element; the font picker previews each font in its own typeface.

---

## 📦 Full export/import — messages included

The UI page opens with an **Export / Import** section: pick an export directory once and the page shows your newest export at a glance. The panel exports **everything, by category** — the messages themselves (SMS · MMS, stock-backup-compatible JSON), theme & colours, fonts (including your imported font files), date & time formats, app settings, conversations, and blocked keywords — into a single ZIP, with a live done/total counter while thousands of messages stream through. Import merges back selectively and offers a one-tap restart.

---

## 🤖 Backed up in one run, from 自由作業盤

The app answers an intent from 白い熊 自由作業盤's 保存復元 project: it runs that same category export headlessly — no screen, no tapping — writes one ZIP wherever it was told to, and replies with the path and the real byte size. While it works it reports **real counts, never a percentage**: 「区分 3/7 — 設定」 as it walks the categories, 「メッセージ 1234/8942」 while the messages stream through — and it keeps reporting through a stalled write, so a slow destination reads as *working* rather than *dead*. It also **answers which items should start ticked**, so 自由作業盤's picker opens on the same selection as the app's own, and a running export can be **stopped from outside** — it unwinds at the next entry boundary and removes the half-written archive, leaving the backup directory exactly as it found it.

The switch now ships **on**, and the authorization token is **optional**: 「認証トークンを使用しますか？」 sits beneath it, off by default, and the token row appears only if you ask for one. A pasted secret cannot survive a wipe — which is exactly the situation the next section exists for.

---

## 🗄️ Restored with its messages, onto a wiped phone

A separate **data door** lets 白い熊 応用管理 back this app up *with its data* and put it back on a clean phone — the case no APK backup can cover, because an app's own storage is unreadable without root. It is a `ContentProvider` rather than another broadcast for one decisive reason: **a broadcast cannot tell you who sent it.** Every caller is checked three ways before a byte moves — the **exact package name** (never a prefix, which any sideloaded app could simply claim), the **uid the kernel reports**, and a **pinned signing certificate**. The archive travels through a **file descriptor the caller opened**, so this app never writes into someone else's backup directory and the permission expires the moment that descriptor closes.

**Restoring exists only here** — never as a broadcast. An import overwrites this app's data, and an unauthenticated door to that would let any app on the phone rewrite your message history.

**And the restore cannot lie about what it wrote.** Android only lets the *default SMS app* write to the message store, and it enforces that by quietly discarding every insert while still answering as though it worked — so a restore can report thousands of messages into an empty phone and be believed. Every message is now **read back** after it is written, and only a confirmed one is counted; if the store takes nothing, the restore **stops after fifty** rather than spending thousands of round trips proving it. What comes back is not a code but an instruction: whether the app is not the SMS app yet, or is *shown* as the SMS app while Android still refuses the writes — a real state, and a different fix — with the steps to put it right and the reassurance that the messages are still in the backup.

---

## 🕐 Japanese time & imperial-era dates

Today’s messages show a Sino-Japanese clock reading (午前八時); older ones an imperial-era date (令和八年五月十四日（木曜日）) — in the conversation list, in-thread date separators, and search results. Both are configurable under 日時の表示形式: kanji / system / 24-hour / 12-hour time, and an imperial-dates toggle.

---

## 設定 One-tap settings from the toolbar

A tightly-set 設定 pair lives in the main toolbar: tap 設 to open the 白い熊 メッセージ UI page, 定 for the regular Settings screen. Each glyph is its own themeable text element. Long-pressing the ⋮ overflow icon jumps straight to the UI page too.

---

## 🧑 人 avatar for pictureless contacts

Contacts without a photo get a brush-stroke yellow 人 glyph instead of the generated colored-letter avatar — everywhere: conversation list, thread, contacts, search, autocomplete.

---

## 🌈 Alpha-capable color picker with recents

The theme color picker adds an **alpha/transparency slider** (the stock one is opaque-only) and a shared recently-used-colors row, so translucent element colors are a first-class option.

---

## 🔓 No “fake version” nag

Built against a patched Fossify Commons that removes the anti-tamper/sideloading check entirely (it misfires on any renamed fork), and fixes Commons’ hard-coded `org.fossify.*` package assumptions — including reading the Contacts app’s shared private contacts.

## Built on Fossify Messages

A fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (app id `shiroikuma.messeji`, so it coexists with the official build). Fossify builds open-source, privacy-focused Android apps free of ads and unnecessary permissions; all credit for the messaging core goes to them. The code remains under the [GPL-3.0 license](LICENSE).

## Building

```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-messeji.git
cd shiroikuma-messeji
# Requires JDK 17+, the Android SDK, and the patched Commons published to mavenLocal
# (see CLAUDE.md — github.com/ShiroiKuma0/shiroikuma-commons, branch custom).
./gradlew buildFoss        # signed foss release APK → ~/tmp/, auto-bumps BUILD_NUMBER
```
