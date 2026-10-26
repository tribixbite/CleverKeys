# Large clipboard paste: chunk size vs Chrome's own cost (Saga, 2026-10-26)

Question (maintainer, "measure, then decide"): `LargeTextCommit` pasted big clipboard entries as
32 Ki-char chunks in one batch edit, and a 524,000-char entry took about 6.5 min to appear in a
Chrome textarea on the Saga. Is the time per-chunk IPC overhead that a larger chunk would cut, or
Chrome's own work that no chunk size changes? And is a char-count chunk safe for every script?

Answer: both, depending on the text. Chrome's cost is set by the text's shape and by how many
times it re-lays out the field. Line-broken text is slow at ANY chunk size; space-separated text
is ~2× faster with fewer, larger chunks. Shipped `cb6c111e`: chunks bounded by 192 KiB on the
wire, filled to that budget — Hebrew 54 → 29 s, emoji 0.7 → 0.3 s, line-broken ASCII 352 → 338 s,
no binder failure in any script. The cost: the first text appears later (ASCII 1.3 → 8.8 s).

## Setup

- Saga 192.168.0.202 (API 34), Chrome 156.0.8078.47, a `data:` page with the usual textarea
  (`width:95%; height:300px; font-size:28px`), portrait. Release builds only: before = the
  2026-10-25 build with `d5b825d3` (32 Ki chars), after = `cb6c111e` (APK sha256 `5d8d7481…`).
- Clips: ASCII 524,000 chars in 13,100 lines (`L000000 abcdefghijklmnopqrstuvwxyz01234\n`, the
  2026-10-25 entry), emoji 256,000 units / 512,000 bytes (no spaces, no newlines), Hebrew
  260,000 units / 468,000 bytes (`שלום עולם `, spaces, no newlines). Imported into CleverKeys'
  clipboard history from a JSON export via Settings → Backup & Restore → Import Clip; the OS
  clipboard was never read or written. Removed again afterwards.
- **Real-IME timing**: tap the entry's paste button in the clipboard pane → Chrome. Device clock
  `date +%s%3N` at the tap, page `Date.now()` on every `input` event, a double
  `requestAnimationFrame` after the last for "painted"; the final value is checksummed against the
  source text. Harness: CDP over `adb forward … localabstract:chrome_devtools_remote`.
- **Renderer-only model**: CDP `Input.insertText`, which reaches the renderer through the same
  `ImeCommitText` path as an IME commit, but without the keyboard, binder or batch edit. N inserts
  model an N-chunk paste; one insert is the lower bound for the IME path (a real system paste
  would have meant replacing the user's clip).
- Logcat checked for `FAILED BINDER TRANSACTION` over every run: none.

## Results (seconds, 3 runs each unless noted)

Renderer-only model (CDP `Input.insertText`):

| Clip | single insert | 32 Ki chars | 96 KiB | 192 KiB | 384 KiB |
|---|---|---|---|---|---|
| ASCII 524K, 13,100 lines | 222.7 / 222.7 / 218.0 (+220.3) | 244.1 / 234.6 / 240.2 (+231.6) | 232.3 / 238.5 / 233.5 | 238.7 / 244.3 / 224.3 | 221.0 / 216.8 / 220.1 |
| Hebrew 260K, spaces | 11.2 / 10.8 / 10.6 | 33.5 / 32.4 / 33.3 | 24.2 / 23.6 / 24.5 | 19.4 / 20.1 / 19.8 | 18.3 / 18.4 / 18.6 |
| emoji 256K | 0.2 / 0.2 / 0.1 | 1.4 / 1.4 / 1.3 | — | — | — |

(Byte columns count UTF-8 bytes in this model; ASCII 96 KiB = 6 chunks, 192 KiB = 3, 384 KiB = 2.)

Real IME paste from the clipboard pane, tap → full length:

| Clip | before: 32 Ki chars | after: 192 KiB wire budget |
|---|---|---|
| ASCII 524K, 13,100 lines | 351.9 / 352.1 / 351.1 (16 chunks, 26,212 input events) | 336.3 / 338.4 / 340.6 (3 chunks, 26,204 input events) |
| Hebrew 260K, spaces | 54.2 / 53.7 / 53.8 (8 chunks) | 28.7 / 28.7 / 28.8 (3 chunks) |
| emoji 256K | 0.7 / 0.7 / 0.7 (8 chunks) | 0.3 / 0.3 / 0.3 (3 chunks) |

All runs: exact checksum and no `FAILED BINDER TRANSACTION`. Time to the FIRST text in the field
rises with the larger first chunk: ASCII 1.3 → 8.8 s, Hebrew 0.4 → 2.6 s, emoji 0.1 → 0.1 s (the
keyboard is not blocked either way; Chrome works through the first chunk before its first
`input` event).

## Where the time goes

1. **Newlines.** Chrome turns every `\n` of a commit into its own edit: one 80,000-char CDP insert
   of 2,000 lines fired 4,000 `input` events and took 4.4 s; the same text with spaces instead of
   newlines fired 1 event in 0.13 s. The 524K entry is 26,212 events whatever the chunking, each
   on a textarea that keeps growing — ~220 s even as one insert. The real IME path takes ~350 s
   with 16 chunks and ~338 s with 3 (−4 %), so the extra ~130 s over the model does not depend
   on the chunk count either; it was not attributed further (Chrome's IME-side handling of each
   edit is the likely place). No chunk size fixes this; only Chrome can.
2. **Re-layout per commit.** Without newlines each commit re-lays out the whole field, so the k-th
   chunk costs more than the one before (Hebrew, 8 chunks: 1.3, 2.3, 4.1, 6.7, 9.6, 13.1, 16.7 s
   between consecutive `input` events). Fewer chunks means fewer re-layouts: Hebrew 54 → 29 s,
   emoji 0.7 → 0.3 s on the real IME.
3. **Per-chunk IPC** is negligible: emoji's 8 chunks took 0.7 s in all.

## Safety

The binder limit is in bytes: a oneway call must fit the receiver's async space, half of its
~1 MiB buffer, shared with every other oneway call queued for that process; API 34 carries the
text as UTF-8 (the failed 524,000-char parcel was 524,156 bytes), older releases as UTF-16. A char
count is 1 to 3 bytes per char, so 32 Ki chars was safe only because it was small. The new budget
counts each code point on the larger of the two encodings (BMP 2–3 bytes, a surrogate pair 4, a
lone surrogate 3) and stops before a pair that does not fit: 192 KiB per chunk, two of which fit
in the async space at once while the next is queued. Pinned by `LargeTextCommitTest`
(`theDefaultChunkFitsTwiceInTheAsyncBinderSpaceForEveryScript` covers ASCII, Hebrew, CJK and emoji
at the 512 KB cap) and `ClipboardLargePasteTest`.

384 KiB was not chosen: it gains ~1 s over 192 KiB on Hebrew in the model, but a second 384 KiB
chunk cannot be queued while the first is still held, so every paste would lean on the retry path.

## Side findings (not changed here)

- **Clipboard pane with huge entries**: opening the pane with these three entries blocked the
  keyboard's main thread for 46–58 s. Each row's one-line, ellipsized `TextView` still line-breaks
  the entry's FULL text (`ClipboardHistoryView` row binding → `TextView.setText` →
  `StaticLayout`/`LineBreaker.computeLineBreaks`). A tap during that window is an input-dispatch
  ANR and the system kills the keyboard (5 ANRs, `dumpsys activity exit-info`, 01:44–01:48 and
  03:18; entering Select mode re-binds the rows and costs the same ~50 s;
  trace in `build/oct26-saga202-chunk/anr-dropbox.txt`). Fix candidate: bind a bounded preview
  (first few hundred chars) to collapsed rows. Separate item, not in this change.
- Chrome's renderer ran out of memory (`V8 process OOM (Oilpan: Large allocation)`, "Aw, Snap!")
  once after ~10 back-to-back 524K inserts in the same tab; a reload recovered it. Not reproduced
  in a fresh tab.

Evidence: `build/oct26-saga202-chunk/` (screenshots, logs; not in git). Harness scripts were
scratch (`cdp.ts`, `ime.ts`, `lines.ts`).
