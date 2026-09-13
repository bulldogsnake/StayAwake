# Architecture

## The shape of the problem

Three constraints drove every decision:

1. **It must block everywhere on the phone**, not just in one browser. Gambling
   apps are native apps; a browser extension is useless against them.
2. **The user must not have to keep the app open.** People close apps.
3. **It must not need a server.** No server means no running cost, no user
   traffic we could log, and nothing to shut down.

On Android exactly one unprivileged mechanism satisfies all three:
`VpnService`. That is why every credible on-device content blocker on Android
is built this way.

## How it works

```
   any app on the phone
          │  DNS query for "jiliko7.net"
          ▼
   Android resolver  ──►  10.111.222.2   (the DNS server Tigil advertises)
                                │
                                │  routed into the tunnel
                                ▼
                     TigilVpnService.pump()
                                │
                     parse IPv4 / UDP / DNS
                                │
                     RuleEngine.evaluate("jiliko7.net")
                          │                    │
                    blocked │                  │ allowed
                          ▼                    ▼
                 synthesise NXDOMAIN     forward to the real
                 back into the tunnel    resolver over a
                                         protect()ed socket
```

### We do not tunnel the device's traffic

The VPN routes a single fake DNS address — plus a handful of public resolvers
apps commonly hardcode — and **leaves every other route alone**. Photos, video
calls and downloads never enter this process.

That is the difference between a blocker people keep and one they uninstall on
day two: no throughput penalty, negligible battery cost, no user traffic
passing through our code.

### Why NXDOMAIN rather than 0.0.0.0

`0.0.0.0` makes a browser hang and then show a confusing blank page. NXDOMAIN
fails immediately with "site can't be reached" — faster, and unambiguous.

## The blocklist: hashes, not strings

~346,000 domains as Java strings is roughly 30 MiB of heap. As a sorted array
of 64-bit FNV-1a hashes it is **2.6 MiB**, loads in one sequential read, and
answers a lookup with a binary search — which matters because every DNS query
on the device passes through it.

The costs, stated plainly:

- We can no longer enumerate what is blocked (only test membership).
- A hash collision would over-block one unrelated domain. At 346k entries in a
  64-bit space that is a ~3-in-10⁹ probability; the allowlist is the fix if it
  ever happens.

`Hashing.fnv1a64()` in Kotlin and `fnv1a64()` in `build_blocklist.py` **must**
stay byte-identical. If they drift, the app silently matches nothing. That is
pinned by `HashingTest.matchesThePythonBuilder`.

## The rule engine: why heuristics carry the product

A pure domain list loses to Philippine operators by design — `wpc2025` becomes
`wpc2026`, `jiliko1` becomes `jiliko7`, a fresh `.casino` domain appears every
week. So `RuleEngine` evaluates in this order:

1. user allowlist — the person's own override wins over everything
2. shipped allowlist — help lines, banks, the regulator, known false positives
3. user blocklist
4. the compiled list — ~346k known domains
5. **heuristics** — substring / regex / TLD rules against the *registrable*
   domain

Step 5 is the one that matters. `catchesUnlistedMirrorDomainsByShape` in the
test suite proves it: `wpc2031.live`, `jiliko99.net`, `77win.co`, `ph888.net`,
`betso99.org` are on no list anywhere and are all blocked.

The `bet` rules are deliberately shaped rather than naive — they catch `1xbet`,
`bet88`, `melbet`, `betso88` while leaving `diabetes.org`, `alphabet.com` and
`betterhelp.com` alone. `doesNotOverblockLookalikeWords` pins that.

A bounded LRU caches verdicts: a phone re-resolves the same few hundred
hostnames constantly, and without the cache every query would re-run 13
regexes.

## Bypass resistance

- **Commitment lock** — enforced in `TigilVpnService.onStartCommand`, not in
  the UI. The notification, the Quick Settings tile and a shell
  `am startservice` all refuse while it is active. The moment someone most
  wants to disable a gambling blocker is the moment it is working; the delay is
  there to outlast that moment.
- **Boot receiver** — re-arms after restart, which is the first thing anyone
  tries.
- **Strict mode** — drops DoH/DoT aimed at the public resolvers we route in, so
  a browser's "Secure DNS" cannot quietly route around us.
- **Private DNS detection** — the one hole we genuinely cannot close from an
  unprivileged app, so we detect it and say so in a red banner rather than
  appearing to work while blocking nothing.

See `LIMITS.md` for the full, honest list of what still gets through.

## Module map

```
blocklist/
  seed/ph-gambling.txt   curated PH operators (licensed + illegal + e-sabong)
  seed/keywords.txt      the anti-mirror heuristics
  seed/allowlist.txt     help lines, banks, regulator, false positives
  build_blocklist.py     merges upstream feeds + seeds -> every output format

android/app/src/main/java/ph/tigil/blocker/
  data/Hashing.kt              FNV-1a, pinned to the Python builder
  data/Blocklist.kt            sorted-hash index + binary search
  data/RuleEngine.kt           the five-step verdict, plus the LRU cache
  data/Prefs.kt                state; batched stat writes (no disk I/O per query)
  data/BlocklistRepository.kt  asset vs. downloaded list, process-wide singleton
  vpn/TigilVpnService.kt       the tunnel and the DNS loop
  vpn/UdpDatagram.kt           IPv4 + UDP parse / synthesise, with checksums
  vpn/DnsMessage.kt            question parsing, NXDOMAIN synthesis
  system/BootReceiver.kt       re-arm after reboot
  system/ProtectionTileService.kt  one-tap Quick Settings toggle
  ui/                          single Compose screen
```

## Testing

`app/src/test/java/ph/tigil/blocker/CoreTest.kt` — 18 pure-JVM tests, no
emulator. They cover the places where a silent bug would stop the product
blocking while it still looked like it worked: hash parity with the Python
builder, IPv4/UDP checksum correctness verified by independent recomputation,
DNS question parsing (including refusing compression pointers, which is what
stops a crafted packet spinning the parser), and the rule-engine precedence.

```bash
python3 blocklist/build_blocklist.py --offline
cd android && ./gradlew test
```
