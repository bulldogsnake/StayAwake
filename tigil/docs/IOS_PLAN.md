# iOS plan

**Not in the MVP, and that is a deliberate scheduling decision rather than an
oversight.** iOS cannot ship on the same timeline as Android because two of the
three viable approaches are gated behind an Apple approval that takes weeks and
can be refused.

## The three options, and which one to take

### 1. Screen Time API — `FamilyControls` + `ManagedSettings` (recommended)

The right long-term answer. `ManagedSettings` can shield apps and filter web
domains at OS level, and — uniquely — it can block **native gambling apps**,
not just websites. Combined with `DeviceActivity` it also gives a real
commitment lock.

The catch: **`FamilyControls` is a privileged entitlement.** You must request it
from Apple before you can ship to TestFlight *or* the App Store. Approval is
discretionary and takes weeks. A gambling-harm-reduction tool is a strong case,
but it is not automatic.

Known rough edges (well documented by developers who have shipped on it): the
web-content filter is Safari-centric, `DeviceActivity` scheduling is
unreliable, and debugging is painful because much of it runs in extensions.

**Action: file the entitlement request now, in parallel with Android
development.** It is the long pole.

### 2. `NEDNSSettingsManager` (DNS settings)

Since iOS 14 an app can install a system-wide DNS configuration without MDM.
This is the closest analogue to the Android design and would let us reuse the
blocklist directly — but it points the device at a **DNS server**, which means
we would have to *run one*. That breaks the "no server, no user traffic"
property that makes the Android version cheap and private.

Viable as a fallback if the Screen Time entitlement is refused. It would need a
hosted filtering resolver, a privacy policy that honestly describes it, and a
running cost.

### 3. `NEPacketTunnelProvider` (local VPN)

The direct port of the Android design: a local VPN doing on-device DNS
filtering, no server. Allowed on the App Store with justification, and several
content blockers ship this way. Heavier than option 1 and blocks websites only,
not native gambling apps.

## Recommended sequence

1. **Now** — request the `FamilyControls` entitlement. Nothing else is blocked
   on it, and it is the slowest step.
2. **Meanwhile** — build the iOS app against option 3
   (`NEPacketTunnelProvider`), which needs no special approval and reuses
   `tigil-blocklist.bin` and `rules.json` unchanged. Port `Blocklist`,
   `RuleEngine`, `DnsMessage` and `UdpDatagram` to Swift; they are pure logic
   with no Android dependency, and the JVM test suite doubles as the spec.
3. **On approval** — layer Screen Time shielding on top for native-app blocking
   and a much stronger commitment lock.

## Shared assets

`blocklist/dist/tigil-blocklist.bin` and `rules.json` are platform-neutral. The
binary format is documented in `android/.../data/Blocklist.kt` and is trivial
to read in Swift: 24-byte header, then a sorted `Int64` array.

The FNV-1a implementation must match the Python builder byte for byte — port
`HashingTest` to Swift first and make it pass before anything else.
