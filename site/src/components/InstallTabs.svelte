<script lang="ts">
  type Tab = 'obtainium' | 'fdroid' | 'github' | 'build'
  let active = $state<Tab>('obtainium')

  const tabs: { id: Tab; label: string; badge?: string }[] = [
    { id: 'obtainium', label: 'Obtainium', badge: 'Recommended' },
    { id: 'fdroid',    label: 'F-Droid' },
    { id: 'github',    label: 'GitHub APK' },
    { id: 'build',     label: 'Build from source' },
  ]

  let copied = $state(false)
  async function copy(s: string) {
    try {
      await navigator.clipboard.writeText(s)
      copied = true
      setTimeout(() => (copied = false), 1500)
    } catch {}
  }

  // Pre-built URL (kept outside the payload so it wraps cleanly in source)
  const OBTAINIUM_DEEPLINK =
    'https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22tribixbite.cleverkeys%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ftribixbite%2FCleverKeys%22%2C%22author%22%3A%22tribixbite%22%2C%22name%22%3A%22CleverKeys%22%7D'

  const snippets: Record<Tab, { label: string; cmd: string; note: string; cta?: { href: string; label: string } }> = {
    obtainium: {
      label: 'Install via Obtainium (recommended)',
      cmd: OBTAINIUM_DEEPLINK,
      note:
        'Obtainium auto-tracks GitHub releases so you get new versions the moment they ship — no F-Droid indexing lag. Tap to open in Obtainium on your phone, or share this link to your device.',
      cta: { href: OBTAINIUM_DEEPLINK, label: 'Open in Obtainium' },
    },
    fdroid: {
      label: 'One-tap install from F-Droid',
      cmd: 'https://f-droid.org/packages/tribixbite.cleverkeys',
      note: 'Signed reproducible builds. No Play Store account required. Updates typically land 24–48 h after a GitHub release.',
      cta: { href: 'https://f-droid.org/packages/tribixbite.cleverkeys', label: 'Open F-Droid listing' },
    },
    github: {
      label: 'Latest signed release',
      cmd: 'https://github.com/tribixbite/CleverKeys/releases/latest',
      note: 'Grab the arm64-v8a APK, install, then enable in Settings → Languages & input.',
      cta: { href: 'https://github.com/tribixbite/CleverKeys/releases/latest', label: 'Open release' },
    },
    build: {
      label: 'Clone and build locally',
      cmd: 'git clone https://github.com/tribixbite/CleverKeys && cd CleverKeys && ./gradlew assembleRelease',
      note: 'Gradle wrapper + Android SDK required. ONNX model is checked in.',
    },
  }
</script>

<div class="overflow-hidden rounded-2xl border border-white/10 bg-[color:var(--color-ink-700)]/60 backdrop-blur-sm">
  <div role="tablist" aria-label="Install methods" class="flex items-center gap-1 border-b border-white/5 p-1.5">
    {#each tabs as t}
      <button
        role="tab"
        aria-selected={active === t.id}
        type="button"
        onclick={() => (active = t.id)}
        class="flex-1 rounded-lg px-2 py-2 text-xs font-medium transition sm:text-sm"
        class:is-active={active === t.id}
      >
        <span class="inline-flex items-center gap-1.5">
          {t.label}
          {#if t.badge}
            <span class="rounded-full bg-[color:var(--color-teal)]/20 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wider text-[color:var(--color-teal)]">
              {t.badge}
            </span>
          {/if}
        </span>
      </button>
    {/each}
  </div>
  <div class="min-w-0 p-5">
    <div class="text-xs uppercase tracking-widest text-white/50">{snippets[active].label}</div>
    <div class="mt-2 flex min-w-0 items-stretch gap-2">
      <pre class="min-w-0 flex-1 overflow-x-auto whitespace-pre rounded-lg border border-white/10 bg-[color:var(--color-ink-900)]/80 px-3 py-2.5 font-mono text-xs leading-snug text-[color:var(--color-violet-100)]"><code>{snippets[active].cmd}</code></pre>
      <button
        type="button"
        onclick={() => copy(snippets[active].cmd)}
        class="shrink-0 rounded-lg border border-white/10 bg-white/5 px-3 py-2.5 text-xs font-semibold text-white/80 transition hover:border-white/20 hover:bg-white/10"
        aria-label="Copy command"
      >
        {copied ? '✓ Copied' : 'Copy'}
      </button>
    </div>
    <p class="mt-3 text-sm text-white/60">{snippets[active].note}</p>
    {#if snippets[active].cta}
      <div class="mt-4">
        <a
          href={snippets[active].cta!.href}
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-1.5 text-sm font-semibold text-[color:var(--color-violet-200)] hover:text-white"
        >
          {snippets[active].cta!.label}
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7 17 17 7"/><path d="M7 7h10v10"/></svg>
        </a>
      </div>
    {/if}
  </div>
</div>

<style>
  .is-active {
    background: linear-gradient(135deg, rgba(139,92,246,0.22), rgba(139,92,246,0.08));
    color: #fff;
    box-shadow: inset 0 0 0 1px rgba(178,139,255,0.24);
  }
  button[role="tab"]:not(.is-active) { color: rgba(229,220,255,0.65); }
  button[role="tab"]:not(.is-active):hover { background: rgba(255,255,255,0.04); color: #fff; }
</style>
