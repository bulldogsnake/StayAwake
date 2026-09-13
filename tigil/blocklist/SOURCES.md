# Blocklist sources and provenance

## Upstream feeds

| Feed | Domains | Licence | Notes |
|---|---:|---|---|
| [StevenBlack/hosts — gambling-only](https://github.com/StevenBlack/hosts/blob/master/alternates/gambling-only/hosts) | ~6,600 | MIT | Conservative, well-curated, low false-positive rate. |
| [blocklistproject/Lists — gambling](https://github.com/blocklistproject/Lists/blob/master/gambling.txt) | ~343,000 | Unlicense | Very broad. Carries the bulk of the coverage and most of the false-positive risk. |

Both are global. Neither covers the Philippine market well — which is why
`seed/ph-gambling.txt` and `seed/keywords.txt` exist.

## Authoritative Philippine sources (to verify the seed list against)

These should be checked before each release. None of them publishes a
machine-readable feed today, so this is manual work:

- **PAGCOR Guarantee verification portal** (launched September 2025) — the
  official check for whether an operator is licensed. Use it to keep the
  "licensed" section of `seed/ph-gambling.txt` accurate.
- **PAGCOR list of accredited service providers and online gaming platforms** —
  published periodically as a PDF.
- **CICC (Cybercrime Investigation and Coordinating Center)** — has identified
  1,000+ unlicensed gambling websites and 146 linked to illegal e-sabong. If
  that list can be obtained, it is the highest-value single input to this
  project.
- **NTC blocking orders** — the National Telecommunications Commission
  periodically orders ISPs to block named domains. Those orders are public and
  are exactly the domains we want.

## Why the seed list includes legal operators

PAGCOR-licensed PIGO operators are lawful businesses. They are in the list
because the *user* has chosen to block gambling, not because of any allegation
against them — the same way an alcohol blocker lists licensed liquor shops.
Users can allowlist any domain from the app's settings screen.

## Verification status

Entries in `seed/ph-gambling.txt` marked `[v]` were seen in public reporting or
search results during research (September 2026). Entries marked `[?]` are
pattern-derived and **should be verified against the sources above before a
production release**. They are safe to ship in the sense that the heuristics in
`keywords.txt` would catch most of them anyway, but an unverified domain in a
published list is a claim we have not checked.

## Rebuilding

```bash
python3 build_blocklist.py            # fetch upstream, rebuild everything
python3 build_blocklist.py --offline  # rebuild from .cache/ and seeds only
```

Outputs land in `dist/`. Only `tigil-blocklist.bin`, `rules.json` and
`manifest.json` are committed (the app needs them); the ~70 MiB of router
formats are regenerated on demand and published as release assets.
