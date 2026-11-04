# Kit 19 data manifest

Nothing here is committed or packaged into the APK. Hashes were measured on 2026-11-04.

| data | where | size | sha256 | licence |
|---|---|---|---|---|
| WM Keyboard `app/dictionaries-src/bn.txt` | `../wmkeyboard` (github.com/wasi-master/wmkeyboard), file last changed `a19072cf7d871986d2c7aa123f79367a756a817d` | 491,228 B | `145c1f751f8244ed9f87b41a3be815c4a3a188ca3fe5ba6f78742690b31d6df9` | seed + hand blocks MIT (c) 2026 Wasi Master; OpenSubtitles block = FrequencyWords adaptation, CC BY-SA 4.0; chat block excluded (filter corpus unstated) |
| FrequencyWords `content/2018/bn/bn_50k.txt` | `https://raw.githubusercontent.com/hermitdave/FrequencyWords/cdd980291e6cd9ab0e5b3b78b0fe1d1f64385c23/content/2018/bn/bn_50k.txt` | 1,119,693 B, 50,000 lines | `2fd6c08f29335f26af983c26821537e5ed5e42dfdf43275a0c646a15d18d636e` | content CC BY-SA 4.0 (code MIT); data from OPUS OpenSubtitles 2018 |
| generated `bn_wordlist.tsv` | scratch / `$OUT` | 20,627 rows | `71d73326ba416142884b60c497885b6cdcde03195710590e33b53d4673fbef89` | CC BY-SA 4.0 + MIT (as above) |
| generated `dictionary.bin` (`--keep-script-marks`) | scratch / `$OUT` | 20,627 words | `118f326e2bd4614543ccd067cf24174d23a0c1e57400b337a439721d1b5290e2` | same |
| generated `unigrams.txt` (top 5,000) | scratch / `$OUT` | 5,000 rows | `721173bdd7a1467aa0b714d33a590fe00ec38b086869dfeaa43aff278f48cfcb` | same |
| default-mode `dictionary.bin` (the defect, for comparison) | scratch | 20,627 words / 20,002 keys | `e9b4dbcc8e77c727569a2a843e2ca2cbe82daf3dde5598232674c8155380ae16` | same |
