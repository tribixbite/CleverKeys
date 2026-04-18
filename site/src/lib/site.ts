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
  // Obtainium tracks GitHub releases directly, so users get updates the moment
  // the release pipeline publishes — no 24–48h F-Droid lag.
  obtainium:
    'https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22tribixbite.cleverkeys%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ftribixbite%2FCleverKeys%22%2C%22author%22%3A%22tribixbite%22%2C%22name%22%3A%22CleverKeys%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22dontSortReleasesList%5C%22%3Afalse%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22CleverKeys%5C%22%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22Neural%20network%20gesture%20keyboard%20for%20Android%5C%22%7D%22%7D',
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
