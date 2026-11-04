# Kit 18 data manifest

All of these are local and eval-only; none is committed or shipped. Hashes were measured on
2026-11-04.

| data | where | size | sha256 | licence |
|---|---|---|---|---|
| UD English-GUM `en_gum-ud-train.conllu` | `raw.githubusercontent.com/UniversalDependencies/UD_English-GUM/1fe635509c649e376dfb449d528424ab78f4eaee/…` → `~/.cache/cleverkeys-corpora/ud/` | 20,996,445 B | `88c410ca6a51ccc79f9536d75feb1633421691165cd191f8137f8b37569a0329` | CC BY-NC-SA 4.0 (parts CC BY), eval-only |
| `en_gum-ud-dev.conllu` | same | 2,970,960 B | `61d02ce27a143e9749118b1b9c314ac3da40f64ec17a6aaff5fc7b42704f7910` | same |
| `en_gum-ud-test.conllu` | same | 2,950,684 B | `cd96a285e7339f401f4803dd0f4f61109c51692e3ef6f9186a1f977544080b69` | same |
| Ubuntu Dialogue Corpus `ubuntu_dialogs.tgz` (members 125,001–150,000 used) | `~/.cache/cleverkeys-corpora/` (the archive rounds 1–2 used) | 552,499,372 B | `c4acbc55b7f83bc6a9a420960047360e352f50dfc76b3891f89b2ad0e2401354` | no stated licence; eval-only, as in round 2 |
| generated `round3/gum_en.txt` (Q1) | `~/.cache/cleverkeys-corpora/static-lm-eval/round3/` | 11,959 segments | `f007922b2ececee8fb890443cdbec028683b465e0c2bb2e11fc5cadfa9af81e8` | follows GUM |
| generated `round3/ubuntu_fresh_en.txt` (Q2) | same | 125,407 segments | `df6b2c62331ca535ca7ecdc729726c71617ac39adc767d28d38cee18e21bf4bf` | follows Ubuntu |
| static LM `en.cklm` | `src/main/assets/lm/` | 439,017 B | pinned by `StaticLmAssetDriftTest` | see NOTICE |

These leakage-exclusion inputs are read and never scored: the Leipzig and Tatoeba LM build
inputs, `static-lm-eval/{heldout,ood_dev,ood_test,ewt_train,ubuntu_eval}_en.txt`, Common Voice
`sentence-collector.en.txt`, and `static-lm-fresh/{ud,web}/ood_dev_en.txt`.
