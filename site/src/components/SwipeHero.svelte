<script lang="ts">
  import { onMount, onDestroy } from 'svelte'

  type Props = { words?: string[] }
  const { words = ['hello', 'swype', 'keyboard', 'neural', 'typing', 'private'] }: Props = $props()

  // Phone mockup constants (matches viewBox 360x520)
  const KEY_W = 34
  const KEY_H = 42
  const ROWS: string[][] = [
    ['q','w','e','r','t','y','u','i','o','p'],
    ['a','s','d','f','g','h','j','k','l'],
    ['z','x','c','v','b','n','m'],
  ]

  // Row insets to center rows
  const ROW_X_OFFSET = (row: number): number => {
    if (row === 0) return 12
    if (row === 1) return 12 + KEY_W / 2
    return 12 + KEY_W * 1.5
  }
  const ROW_Y = (row: number) => 300 + row * (KEY_H + 6)

  function keyCenter(ch: string): [number, number] | null {
    for (let r = 0; r < ROWS.length; r++) {
      const row = ROWS[r]
      if (!row) continue
      const idx = row.indexOf(ch.toLowerCase())
      if (idx !== -1) {
        const x = ROW_X_OFFSET(r) + idx * KEY_W + KEY_W / 2
        const y = ROW_Y(r) + KEY_H / 2
        return [x, y]
      }
    }
    return null
  }

  // Word → smooth quadratic path
  function pathFor(word: string): string {
    const pts: [number, number][] = []
    for (const ch of word) {
      const c = keyCenter(ch)
      if (c) pts.push(c)
    }
    if (pts.length === 0) return ''
    const first = pts[0]
    if (!first) return ''
    let d = `M ${first[0]} ${first[1]}`
    for (let i = 1; i < pts.length; i++) {
      const cur = pts[i]
      const prev = pts[i - 1]
      if (!cur || !prev) continue
      const mx = (prev[0] + cur[0]) / 2
      const my = (prev[1] + cur[1]) / 2
      d += ` Q ${prev[0]} ${prev[1]} ${mx} ${my}`
      if (i === pts.length - 1) d += ` T ${cur[0]} ${cur[1]}`
    }
    return d
  }

  let idx = $state(0)
  let typed = $state('')
  let playing = $state(true)
  let interval: ReturnType<typeof setInterval> | null = null

  const cycle = () => {
    idx = (idx + 1) % words.length
    typed = ''
    const word = words[idx] ?? ''
    let j = 0
    const charTick = setInterval(() => {
      typed = word.slice(0, j + 1)
      j++
      if (j >= word.length) clearInterval(charTick)
    }, 120)
  }

  onMount(() => {
    if (typeof document !== 'undefined' && document.documentElement.classList.contains('no-motion')) {
      playing = false
      typed = words[0] ?? ''
      return
    }
    typed = ''
    const first = words[0] ?? ''
    let j = 0
    const tick = setInterval(() => {
      typed = first.slice(0, j + 1)
      j++
      if (j >= first.length) clearInterval(tick)
    }, 120)
    interval = setInterval(cycle, 3400)
  })

  onDestroy(() => {
    if (interval) clearInterval(interval)
  })

  const currentWord = $derived(words[idx] ?? '')
  const d = $derived(pathFor(currentWord))
</script>

<div class="relative mx-auto w-full max-w-[420px]">
  <!-- Phone bezel -->
  <div class="relative rounded-[40px] border border-white/10 bg-gradient-to-br from-white/10 to-white/0 p-2 shadow-2xl shadow-violet-900/40 glow-violet">
    <div class="rounded-[32px] border border-white/10 bg-[color:var(--color-ink-800)]/85 p-1">
      <svg
        viewBox="0 0 360 520"
        role="img"
        aria-label="Animated swipe-path keyboard demo"
        class="block h-auto w-full rounded-[28px]"
      >
        <defs>
          <linearGradient id="trail" x1="0" x2="1" y1="0" y2="1">
            <stop offset="0%" stop-color="#b28bff" stop-opacity="0" />
            <stop offset="50%" stop-color="#b28bff" stop-opacity="0.9" />
            <stop offset="100%" stop-color="#2dd4bf" stop-opacity="0.95" />
          </linearGradient>
          <filter id="glow"><feGaussianBlur stdDeviation="2.5" /></filter>
          <linearGradient id="scr" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#1a1333" />
            <stop offset="100%" stop-color="#0d0a1d" />
          </linearGradient>
        </defs>

        <!-- Screen -->
        <rect x="0" y="0" width="360" height="520" rx="28" fill="url(#scr)" />

        <!-- Status -->
        <g fill="rgba(255,255,255,0.38)" font-family="Inter, system-ui" font-size="11">
          <text x="18" y="22">9:41</text>
          <text x="318" y="22">97%</text>
        </g>

        <!-- Text field -->
        <g>
          <rect x="14" y="44" width="332" height="44" rx="10" fill="rgba(255,255,255,0.04)" stroke="rgba(178,139,255,0.22)" />
          <text x="28" y="72" fill="#e8e0ff" font-family="Inter, system-ui" font-size="15">{typed}
            <tspan fill="#b28bff">|</tspan>
          </text>
        </g>

        <!-- Suggestion bar -->
        <g>
          <rect x="0" y="262" width="360" height="30" fill="rgba(255,255,255,0.02)" />
          <g font-family="Inter, system-ui" font-size="12">
            <text x="60" y="282" fill="rgba(255,255,255,0.6)" text-anchor="middle">{words[(idx + 1) % words.length]}</text>
            <text x="180" y="282" fill="#fff" text-anchor="middle" font-weight="600">{currentWord}</text>
            <text x="300" y="282" fill="rgba(255,255,255,0.6)" text-anchor="middle">{words[(idx + 2) % words.length]}</text>
          </g>
        </g>

        <!-- Keys -->
        {#each ROWS as row, r}
          {#each row as ch, i}
            {#key ch + r + i}
              <g>
                <rect
                  x={ROW_X_OFFSET(r) + i * KEY_W}
                  y={ROW_Y(r)}
                  width={KEY_W - 3}
                  height={KEY_H - 3}
                  rx="7"
                  fill="rgba(255,255,255,0.04)"
                  stroke="rgba(255,255,255,0.06)"
                />
                <text
                  x={ROW_X_OFFSET(r) + i * KEY_W + (KEY_W - 3) / 2}
                  y={ROW_Y(r) + (KEY_H - 3) / 2 + 5}
                  fill="rgba(255,255,255,0.75)"
                  text-anchor="middle"
                  font-family="Inter, system-ui"
                  font-size="14"
                >{ch}</text>
              </g>
            {/key}
          {/each}
        {/each}

        <!-- Space bar -->
        <rect x="60" y={ROW_Y(3) - 2} width="240" height="34" rx="8" fill="rgba(255,255,255,0.05)" />

        <!-- Swipe trail -->
        {#key currentWord}
          <path d={d} fill="none" stroke="url(#trail)" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round" opacity="0.95" filter="url(#glow)">
            <animate attributeName="stroke-dasharray" from="0 2000" to="2000 0" dur="2.5s" fill="freeze" />
          </path>
          <path d={d} fill="none" stroke="#ffffff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" opacity="0.85">
            <animate attributeName="stroke-dasharray" from="0 2000" to="2000 0" dur="2.5s" fill="freeze" />
          </path>
        {/key}
      </svg>
    </div>

    <!-- Floating chip: neural prediction -->
    <div class="pointer-events-none absolute -right-3 top-20 hidden rounded-xl border border-white/10 bg-[color:var(--color-ink-700)]/95 px-3 py-2 text-xs shadow-lg backdrop-blur-sm sm:block float-slow">
      <div class="flex items-center gap-2">
        <span class="relative inline-flex h-2 w-2">
          <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-violet-400 opacity-75"></span>
          <span class="relative inline-flex h-2 w-2 rounded-full bg-violet-400"></span>
        </span>
        <span class="text-white/80">decoded in <span class="font-mono font-semibold text-white">142ms</span></span>
      </div>
    </div>

    <!-- Floating chip: on-device -->
    <div class="pointer-events-none absolute -left-3 bottom-24 hidden rounded-xl border border-white/10 bg-[color:var(--color-ink-700)]/95 px-3 py-2 text-xs shadow-lg backdrop-blur-sm sm:block" style="animation: floatSlow 7s ease-in-out infinite 1.5s">
      <div class="flex items-center gap-2">
        <svg class="h-3.5 w-3.5 text-teal-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="10.5" width="17" height="11" rx="2"/><path d="M7 10.5V7a5 5 0 1 1 10 0v3.5"/></svg>
        <span class="text-white/80">0 network calls</span>
      </div>
    </div>
  </div>
</div>
