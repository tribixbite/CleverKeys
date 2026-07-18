---
title: Private Copy
description: Copy text into CleverKeys' clipboard history without it ever touching the Android system clipboard
category: Clipboard
difficulty: intermediate
related_spec: ../specs/clipboard/private-copy-spec.md
---

# Private Copy

Private copy lets you save selected text into CleverKeys' own clipboard history **without** the text ever reaching the Android system clipboard. A normal copy places text on the OS clipboard, where the foreground app, the system clipboard overlay, and any third-party clipboard managers can read it. A private copy skips all of that: the text goes straight into CleverKeys, and nothing else on the device sees a "clipboard changed" event.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Save text to CleverKeys' history without exposing it to the OS clipboard |
| **Access** | In-keyboard `copy_private` editing key, or an opt-in "Private copy" entry in other apps' selection menus |
| **Badge** | Private entries show a lock badge in the clipboard panel |
| **Backups** | Excluded from plaintext exports; included in encrypted exports |

## How It Works

When you copy text the normal way, the app you copied from hands the text to Android's clipboard service. That makes the text readable by whatever app is in the foreground, by the OS clipboard history (the Gboard-style overlay and OEM clipboard managers), and it can trigger Android's "clipboard accessed" toast.

Private copy takes a different route. The selected text is written directly into CleverKeys' clipboard database and marked private. CleverKeys never calls the Android clipboard service on this path, so:

- The foreground app never receives a "clipboard changed" event.
- Android's clipboard-access toasts never fire.
- The OS clipboard overlay and third-party clipboard managers never receive the text.

You then paste private entries the same way you paste anything else from the clipboard panel — panel paste already inserts text directly into the field without going through the OS clipboard.

## How to Use

There are two ways to make a private copy.

### Method 1: The In-Keyboard Private Copy Key

CleverKeys ships an editing action named `copy_private`, shown with a lock-and-copy glyph (`🔒⎘`). You can place it wherever you like using the customization framework, then use it whenever CleverKeys is your active keyboard.

To use it:

1. Bind `copy_private` to a key or gesture (see below).
2. Select text in any editable field.
3. Trigger the `copy_private` action.
4. A "Privately copied" message appears in the suggestion bar. The text is now in CleverKeys' history, marked private.

Where you can bind it:

- **Extra keys** — add `copy_private` to your extra-keys row, exactly like the standard `copy` key.
- **Short swipe / per-key customization** — go to **Settings > Activities > Per-Key Customization**, pick a key and a swipe direction, and assign the **Private Copy** command. A natural home is a short swipe on the existing copy key.
- **Command palette** — the **Private Copy** command lives in the Clipboard category.

If nothing is selected (or the field won't share its selection), you'll see a "No text selected" message instead.

### Method 2: "Private Copy" in Other Apps' Selection Menus

Android's text-selection toolbar (the pop-up with Copy / Share / Select all) can host actions from other apps. CleverKeys can add a **"Private copy"** entry there, so you can privately copy text from apps even when CleverKeys isn't your active keyboard — including read-only text like articles and chat bubbles.

This entry is **off by default** because it makes CleverKeys visible to other apps' selection menus. To turn it on:

1. Go to **Settings > Clipboard** section.
2. Enable **Private copy** (the "Show 'Private copy' in other apps' text-selection menus" toggle).

Once enabled:

1. Select text in another app.
2. Open the selection toolbar and tap **Private copy** (you may need the overflow menu).
3. A "Copied privately to CleverKeys" confirmation appears. The text is now in CleverKeys' history, marked private.

The text is never returned to the source app, so your selection is left untouched.

## The Lock Badge and Provenance

Private entries appear in the clipboard panel with a small lock badge, in every tab (History, Pinned, Todos). The badge travels with the entry when you pin it or add it to your todos, so a private entry stays private.

The expanded entry view also shows where a private copy came from — the app you copied it from, or "direct launch" if the entry was created programmatically rather than through a real selection toolbar.

## Copying a Private Entry Back to the System Clipboard

Long-pressing an entry in the panel normally copies it to the system clipboard so you can use it in another app. For a **private** entry, this would expose the text to exactly the channel private copy exists to avoid, so CleverKeys asks first with a confirmation dialog. Confirm only if you really want that text on the OS clipboard; otherwise cancel and paste it through the panel instead.

## Private Copy and Backups

- **Plaintext backups exclude private entries.** When you export an unencrypted backup, private entries are left out and the export reports how many were excluded. A plaintext backup file that ends up on a shared drive never contains your private clips.
- **Encrypted backups include private entries.** Because the file is encrypted, private entries can safely travel with it, and they restore as private on the other device.

If keeping private entries across a device migration matters to you, use an encrypted export.

## Tips and Tricks

> [!TIP]
> Bind `copy_private` to a short swipe on your existing copy key. A tap copies normally; a short swipe copies privately — no extra key needed.

> [!NOTE]
> The in-keyboard key only works while CleverKeys is the active keyboard and the field will share its selection. Some editors (certain web views, terminal apps) don't expose their selection; use the selection-toolbar entry there instead.

## Honest Scope — What Private Copy Does and Doesn't Protect

Private copy protects against **clipboard-channel** exposure. It is not a way to hide text from the app you are copying from.

- Text privately copied through either entry point is never placed on the OS clipboard by CleverKeys. The foreground app never sees a "clipboard changed" event; Android's clipboard-access toasts never fire; the OS clipboard overlay and OEM clipboard managers never receive it.
- The **source app** that holds the selected text obviously already has the text — it's rendering it on screen. Private copy protects against the clipboard channel, not against the app you're copying from.
- If you later **normally** copy the same text, or confirm the explicit "copy to system clipboard" dialog above, the OS sees it at that moment. Marking an entry private preserves its backup and clipboard-push policy going forward; it cannot un-expose text that a normal copy already placed on the OS clipboard.

## Related Features

- [Clipboard History](./clipboard-history.md) - The panel where private entries appear
- [Text Selection](./text-selection.md) - Selecting text to copy
- [Privacy](../settings/privacy.md) - Other clipboard privacy settings

## Technical Details

See [Private Copy Technical Specification](../specs/clipboard/private-copy-spec.md).
