export const site = {
  name: 'CleverKeys',
  tagline: 'Neural gesture typing. Open source. On-device.',
  description:
    'The only fully open-source neural network gesture keyboard for Android. On-device transformer decoding, unlimited clipboard, 208 swipe gestures, 11 languages.',
  url: 'https://cleverkeys.app',
  repo: 'https://github.com/tribixbite/CleverKeys',
  mlRepo: 'https://github.com/tribixbite/CleverKeys-ML',
  fdroid: 'https://f-droid.org/packages/tribixbite.cleverkeys',
  releases: 'https://github.com/tribixbite/CleverKeys/releases/latest',
  icon: 'https://raw.githubusercontent.com/tribixbite/CleverKeys/main/res/mipmap-xxxhdpi/ic_launcher.png',
  license: 'GPL-3.0',
  author: 'tribixbite',
  basedOn: 'Julow/Unexpected-Keyboard',
} as const

export type NavItem = { label: string; href: string; external?: boolean }

export const primaryNav: NavItem[] = [
  { label: 'Wiki', href: '/wiki/' },
  { label: 'Demo', href: '/demo/' },
  { label: 'Specs', href: '/specs/' },
  { label: 'GitHub', href: site.repo, external: true },
]

export type Feature = {
  title: string
  blurb: string
  glyph: 'brain' | 'lock' | 'clipboard' | 'gesture' | 'terminal' | 'source' | 'globe' | 'waveform'
  accent?: string
}

export const features: Feature[] = [
  {
    title: 'Neural gesture engine',
    blurb:
      'Transformer encoder-decoder with beam-search decoding, quantized ONNX + XNNPACK. Sub-200 ms on a Pixel 7.',
    glyph: 'brain',
  },
  {
    title: '100% on-device',
    blurb:
      'Zero network permissions. No analytics, telemetry, or cloud sync. It literally cannot phone home.',
    glyph: 'lock',
  },
  {
    title: 'Unlimited clipboard',
    blurb:
      'Persistent history with pin, todo, tag filters, drag-to-reorder, media support — survives reboots without root.',
    glyph: 'clipboard',
  },
  {
    title: '208 swipe gestures',
    blurb:
      '8 directions per key, per-layer. 204+ built-in commands plus custom macros, function keys, nav.',
    glyph: 'gesture',
  },
  {
    title: 'Works in Termux',
    blurb:
      'Only open-source keyboard with reliable swipe typing in terminal emulators. Others crash or corrupt input.',
    glyph: 'terminal',
  },
  {
    title: 'Fully open source',
    blurb:
      'App, model, training pipeline, datasets, and test harness all public. Reproduce every weight end-to-end.',
    glyph: 'source',
  },
]

export const bundledLanguages = [
  'English',
  'Spanish',
  'French',
  'Portuguese',
  'Italian',
  'German',
]
export const downloadableLanguages = [
  'Dutch',
  'Indonesian',
  'Malay',
  'Tagalog',
  'Swahili',
]

export const stats = [
  { label: 'Swipe gestures', value: '208' },
  { label: 'Languages', value: '11' },
  { label: 'Neural decode', value: '<200ms' },
  { label: 'Network perms', value: '0' },
]
