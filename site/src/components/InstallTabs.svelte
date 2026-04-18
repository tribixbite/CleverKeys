<script lang="ts">
  type Tab = 'fdroid' | 'github' | 'build'
  let active = $state<Tab>('fdroid')

  const tabs: { id: Tab; label: string }[] = [
    { id: 'fdroid', label: 'F-Droid' },
    { id: 'github', label: 'GitHub APK' },
    { id: 'build',  label: 'Build from source' },
  ]

  let copied = $state(false)
  async function copy(s: string) {
    try {
      await navigator.clipboard.writeText(s)
      copied = true
      setTimeout(() => (copied = false), 1500)
    } catch {}
  }

  const snippets: Record<Tab, { label: string; cmd: string; note: string }> = {
    fdroid: {
      label: 'One-tap install from F-Droid',
      cmd: 'https://f-droid.org/packages/tribixbite.cleverkeys',
      note: 'Signed reproducible builds. No Play Store account required.',
    },
    github: {
      label: 'Latest signed release',
      cmd: 'https://github.com/tribixbite/CleverKeys/releases/latest',
      note: 'Grab the arm64-v8a APK, install, then enable in Settings → Languages & input.',
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
        class="flex-1 rounded-lg px-3 py-2 text-xs font-medium transition sm:text-sm"
        class:is-active={active === t.id}
      >
        {t.label}
      </button>
    {/each}
  </div>
  <div class="p-5">
    <div class="text-xs uppercase tracking-widest text-white/50">{snippets[active].label}</div>
    <div class="mt-2 flex items-center gap-2">
      <pre class="flex-1 overflow-x-auto rounded-lg border border-white/10 bg-[color:var(--color-ink-900)]/80 px-3 py-2.5 font-mono text-xs leading-snug text-[color:var(--color-violet-100)]"><code>{snippets[active].cmd}</code></pre>
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
