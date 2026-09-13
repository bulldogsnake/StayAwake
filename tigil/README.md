# Tigil — gambling blocker for the Philippines

*Tigil* is Filipino for **stop**.

A Filipino teacher, police officer or high-school student with a phone is two
taps from a PAGCOR-licensed casino app and one search from a hundred unlicensed
ones. Tigil blocks them — every app, every browser, whole phone, with the app
closed.

> **The name, the package ID (`ph.tigil.blocker`) and the colours are all
> placeholders.** Change them before you publish; nothing else depends on them.

---

## What it does

- **Blocks ~346,000 gambling domains** across the entire phone, in every app and
  browser — not just one browser, and not just websites you visit deliberately.
- **Catches domains nobody has listed yet.** PH operators rotate domains weekly.
  Tigil matches the *shape* of an operator domain, so `wpc2031.live` and
  `jiliko99.net` are blocked before anyone reports them. This is the part that
  makes it worth building.
- **Blocks both the illegal and the legal.** Unlicensed offshore sites, banned
  e-sabong, *and* PAGCOR-licensed operators — because the criterion is the
  user's own decision, not the operator's licence status.
- **Runs closed.** One-time setup, then a foreground service. Survives reboot.
  One-tap Quick Settings toggle.
- **Commitment lock.** Lock protection on for 1, 7 or 30 days. While it runs,
  nothing can switch it off — not the app, not the notification, not the tile,
  not a shell command. The moment you most want to disable a gambling blocker
  is the moment it is doing its job.
- **No server, no account, no tracking.** Nothing leaves the device. There is
  no backend to pay for and no traffic we could log even if we wanted to.
- **Covers the whole network too.** The same list exports to Pi-hole, AdGuard
  Home, MikroTik, OpenWrt and BIND RPZ — so a household or a school can block
  every device at once.

## What it does *not* do

It blocks by name. It does not stop a raw IP address, another VPN installed on
top, Android's own Private DNS setting, or a different phone. **A determined
person can get around it.** It is a wall against impulse, which is usually the
wall that matters — but we say so on the app's main screen rather than selling
a guarantee that isn't real.

Read [`docs/LIMITS.md`](docs/LIMITS.md). It is the most important document here.

---

## Repository layout

```
blocklist/    the list, the heuristics, and the compiler that builds every format
android/      the Android app (Kotlin, Compose, VpnService)
docs/         architecture, limits, iOS plan, network blocking, store submission
```

## Build

```bash
# 1. compile the blocklist (needs only Python 3, no third-party packages)
python3 blocklist/build_blocklist.py

# 2. build and test the app
cd android
./gradlew test        # 18 pure-JVM core tests, no emulator needed
./gradlew assembleDebug
```

Gradle copies `blocklist/dist/` into the APK assets automatically.

## How it works, in one paragraph

Android gives an unprivileged app exactly one way to see other apps' traffic:
`VpnService`. Tigil establishes a tunnel but **does not route the device's
traffic through it** — only a link-local DNS address and a handful of public
resolvers apps commonly hardcode. So DNS queries come to us and everything else
(photos, video calls, downloads) never enters the process. Blocked lookups get
an NXDOMAIN; everything else is forwarded to the user's real resolver over a
`protect()`ed socket. Battery and throughput cost is close to nothing, and
there is no server in the path.

Full detail in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Status

| Piece | State |
|---|---|
| Blocklist compiler + 6 output formats | Done, runs, 345,655 domains |
| Heuristic anti-mirror engine | Done, tested against unlisted mirrors |
| Android DNS-filtering VPN service | Written; **not yet compiled against the Android SDK** (see below) |
| Android UI, tile, boot receiver, commitment lock | Written |
| Core logic test suite | **18/18 passing** on the JVM |
| iOS | Planned, not started — [`docs/IOS_PLAN.md`](docs/IOS_PLAN.md) |
| Web version | Not started |

**Honest caveat:** the environment this was built in had no Android SDK
(`dl.google.com` is blocked by network policy), so the app has not been through
`aapt`/AGP or run on a device. Every piece of logic that could be tested
without the SDK — hashing, the blocklist index, the rule engine, IPv4/UDP
checksums, DNS parsing — was extracted, compiled with the real Kotlin compiler,
and tested. The parts that remain unverified are the Android-framework-facing
ones: manifest merging, resource linking, Compose UI, and the service
lifecycle on a real device. Expect to spend a session shaking those out.

## Ethics

This will annoy people who make money from gambling. That is understood and
accepted.

It is still built to be fair: PAGCOR-licensed operators are lawful businesses
and appearing in the blocklist is not an accusation against them — it is a
user's own choice to opt out, the same way an alcohol blocker lists licensed
liquor shops. Every domain can be allowlisted by the user. The regulator's own
site, the banks, the e-wallets and the crisis hotlines are permanently
allowlisted, because a blocker that cuts someone off from their salary or from
a helpline has done net harm.

**If gambling is costing you money, sleep, or people you love:** NCMH Crisis
Hotline **1553** (toll-free, nationwide) or **0966-351-4518**.
