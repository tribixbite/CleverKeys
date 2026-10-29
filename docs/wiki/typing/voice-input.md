---
title: Voice Input
description: Offline dictation inside the keyboard with an imported voice pack — no other app, no network, no stored audio
category: Typing
difficulty: beginner
---

# Voice Input

CleverKeys can type what you say. Recognition runs **on your phone**, with a speech model you
import once as a **voice pack**. The keyboard keeps no network permission: audio is used only
while you dictate, is never stored and never leaves the device.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Dictate into any text field without switching to another keyboard |
| **Access** | Settings → Voice input (off by default); the **Voice input** key or the `voice_input` command |
| **Model** | A voice pack ZIP from the [`langpacks` release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks), imported with the file picker |
| **First pack** | English — Moonshine v2 tiny (streaming), about 45 MB, MIT license |
| **Permission** | Microphone, asked for only when you turn voice input on or import a pack |
| **Privacy** | On device; nothing recorded to storage; no internet permission |

## Setting it up

1. Download `voicepack-moonshine-v2-tiny-en.zip` from the `langpacks` release (Settings → Voice
   input → **Get voice packs** opens the page in your browser).
2. Settings → Voice input → **Import voice pack** and pick the ZIP. CleverKeys checks the size and
   SHA-256 of every model file before installing; a damaged download is refused and an older pack
   is kept until the new one is fully in place.
3. Allow microphone access when Android asks. This is the only time it is requested — turning the
   **Enable voice input** switch on asks too, if you have not allowed it yet. If you deny it, voice
   input stays off; if you chose "Don't allow" twice, the section offers **Open app settings**.
4. Add the key: Settings → Extra Keys → **Voice input (on device)**, or bind the `voice_input`
   command to a short swipe or open it from the command palette.

The pack row shows its name, size, engine, languages and license; **License and notice** shows the
pack's LICENSE and NOTICE text, and **Remove** deletes it.

## Dictating

- Tap the **Voice input** key. The suggestion bar shows **Loading voice model…**, then
  **Listening…** with the words heard so far.
- Words appear in the field as you speak. The newest words are underlined (they may still change);
  words the model has confirmed are typed for good.
- **Tap the bar or the key again** to stop: the rest is typed. **✕** cancels: the underlined words
  are dropped, the confirmed ones stay.
- Listening stops by itself after about 30 seconds with the English Moonshine pack (the model
  loses track of longer stretches; tap the key again to go on), and whenever the keyboard closes
  or you move to another field. If you change fields while words are still underlined, the app
  you were typing in may keep them as typed text.
- Spacing and capitals follow what is already in the field: a space is added after a word, and
  the first word is capitalised only at the start of a sentence.

## Where it types

| Field | What happens |
|-------|--------------|
| Ordinary text fields | Underlined partial text, confirmed words committed, auto-space after the final text if "auto space after suggestion" is on |
| Terminals (Termux and your custom terminal list) and remote-desktop fields (RustDesk, any blind field) | Only confirmed words are sent, appended at the end — nothing is ever rewritten or deleted there |
| The keyboard's own search and edit fields (clipboard, emoji, GIF) | Confirmed words go into that field, never to the app behind the panel |
| Password fields | Voice input is refused |

## Accessibility

TalkBack announces when listening starts, stops or is cancelled. The words themselves are read by
TalkBack as the field changes; partial results are not announced.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Voice input is off" | Turn on Settings → Voice input → Enable voice input. Tapping the key a second time opens the section. |
| "No voice pack installed" | Import a pack (above). |
| "Microphone access is blocked" | Allow the microphone for CleverKeys in Android's app settings. |
| "This voice pack needs a newer version of CleverKeys" | The pack was built for a newer engine or app; update CleverKeys. |
| "The microphone is not available right now" | Another app (a call, a recorder) holds the microphone. |

## For developers

The engine is replaceable: `stt/` defines an engine-neutral `SpeechEngine` interface and a
registry keyed by the pack manifest's `engine.id`; only `stt/moonshine/` knows Moonshine. See
`docs/specs/voice-input.md` for the pack format (manifest format 1), the import rules and the
modularity contract, and `scripts/build_voice_pack.py` for how the pack is built and pinned.
