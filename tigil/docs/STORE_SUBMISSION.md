# Store submission notes

Both stores will scrutinise this app. Neither should reject it, but both need
the paperwork done correctly and it is worth knowing where the friction is
*before* the first submission.

## Google Play

### The VpnService declaration is the main gate

Play permits `VpnService` only for apps whose **core functionality** is a VPN,
or that need it for parental control, device security, app usage tracking,
network tools, browsers, or carrier services. Local on-device traffic filtering
is explicitly in scope — but it must be declared.

In Play Console → App content → **VPN policy declaration**, state plainly:

- The app uses `VpnService` **only** to filter DNS locally on the device.
- **No traffic is routed to any remote server.** Only a link-local DNS address
  and a small set of public-resolver addresses are routed into the tunnel;
  everything else is untouched.
- No personal or sensitive data is collected, transmitted, or monetised.

The rule this must not trip: `VpnService` must never be used to collect
personal or sensitive data without prominent disclosure, and redirecting user
traffic for monetisation is prohibited. Tigil does neither, and the codebase
should stay that way — if a future version ever adds a remote resolver, this
declaration must change with it.

### Foreground service type

Android 14 requires a declared type. There is no VPN type, so the manifest uses
`specialUse` with a subtype string justifying continuous operation. Play
reviews `specialUse` declarations individually — the justification is already
in `AndroidManifest.xml`; keep the Console answer identical to it.

### Data safety

- Data collected: **none**.
- Data shared: **none**.
- The blocked-attempt counter never leaves the device; cloud backup and device
  transfer are both disabled in `data_extraction_rules.xml`.

### Gambling policy

Play's real-money gambling policy restricts apps that *offer* gambling. A
blocker is the opposite and is not covered by it. Say so in the review notes
pre-emptively, because an automated pass may flag the keyword density in the
listing and the blocklist asset.

### Listing wording

Do **not** claim the app makes gambling impossible. Besides being false (see
`LIMITS.md`), an unverifiable efficacy claim is exactly what gets a
health-adjacent listing pulled. "Blocks gambling sites across your whole phone"
is accurate. "Stops gambling addiction" is not.

## Apple App Store

See `IOS_PLAN.md` for the mechanism choice. Submission-specific notes:

- **`FamilyControls` needs an entitlement request approved before TestFlight**,
  not just before release. File it early.
- A `NEPacketTunnelProvider` build needs the Network Extension entitlement and
  a clear review note that the tunnel is local-only.
- Apple requires a privacy policy URL even when nothing is collected. Say
  "nothing is collected" explicitly rather than leaving it implied.

## Both stores

Include the help-line information in the listing, not only in the app. It is
the right thing to do and it signals to a reviewer what kind of app this is.
