# Vendored Tailwind Play runtime

`tailwindcss-play-3.4.17.min.js` is the Tailwind CSS **Play CDN** runtime
(tailwindcss v3.4.17, MIT), fetched byte-for-byte from
`https://cdn.tailwindcss.com` on 2026-09-01 (ARC-046).

sha256: `176e894661aa9cdc9a5cba6c720044cbbf7b8bd80d1c9a142a7c24b1b6c50d15`

Why the Play runtime and not a prebuilt CSS: the demo page configures Tailwind
at runtime via an inline `tailwind.config` block (custom neon colors and
animations) and builds several utility class strings inside its inline JS, so
the JIT runtime is the faithful, low-risk vendoring — a compiled subset would
have to re-derive every dynamically assembled class list and silently degrade
on a miss.

This was the demo's LAST network dependency (the ONNX runtime and models are
already vendored, see `../ort/` and `../../models/PROVENANCE.md`); with it
local, the page loads with zero external requests, matching the app's
no-INTERNET-permission posture.
