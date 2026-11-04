# RTX kits

Each directory packages one item of `memory/todo.md` "Open now" so it can be run on the GPU
machine (the Linux/WSL box with the NVIDIA RTX; the ML repo there is CleverKeys-ML) or, where
the measurement needs no GPU, on any machine. Every kit has the same parts:

* a README: what it does, prerequisites, commands in order, runtime, the pre-registered
  pass/fail gate and what to send back;
* its scripts (typed Python that imports the existing `scripts/` and CleverKeys-ML code rather
  than copying it);
* `DATA_MANIFEST.md`: every input with its location, size, sha256 and licence.

A kit never trains, uploads or publishes on its own. Shipping anything it produces follows the
normal rules: the model, preset and golden fixture move together, and langpacks are released
only with maintainer approval.

| kit | item | runs on | state (2026-11-04) |
|---|---|---|---|
| `17-short-word-ctc/` | short-word CTC word-balanced fine-tune on the raw-path contract | RTX | ready to run; gate data identified and hash-pinned |
| `18-apostrophe-context/` | its/it's listed-evidence chooser, round 3 | CPU (the phone is fine) | ready to run; populations built and pinned |
| `19-bangla-pack/` | Bangla spelling-preserving dictionary → geometry → model/pack | CPU now, RTX later | dictionary stage ready; model stage blocked on a layout decision |
