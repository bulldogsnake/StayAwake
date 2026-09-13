# Roadmap

Ordered by how much each item moves the needle, not by how easy it is.

## Before a public release

1. **Verify the seed list.** Every `[?]` entry in `blocklist/seed/ph-gambling.txt`
   is pattern-derived and unverified. Check them against the PAGCOR Guarantee
   portal and any obtainable CICC/NTC list (see `blocklist/SOURCES.md`).
   Shipping an unverified domain in a published list is a claim we have not
   checked.
2. **Build and run it on a real device.** The Android-framework-facing code
   (manifest merge, resource linking, Compose, service lifecycle) has never
   been compiled against the SDK. Budget a session.
3. **Field-test the heuristics.** Run the rule engine over a week of real DNS
   logs from a volunteer device and count false positives. The `bet` and
   `lotto` rules are the ones most likely to over-block.
4. **Play Console VPN declaration.** See `STORE_SUBMISSION.md`. Get this right
   the first time; a rejection costs a week.

## High value, not yet built

- **Uninstall protection (Device Admin).** Today the commitment lock can be
  defeated by uninstalling the app. A Device Admin receiver makes uninstall
  require the lock to expire first. This is the single biggest gap in the
  bypass table in `LIMITS.md`.
- **Accountability partner.** Optional: a trusted person gets a notification
  when protection is turned off or a lock is ended. Behaviourally this is the
  strongest feature in the category and it needs no server if it goes over SMS
  or a share intent.
- **Blocklist auto-update.** `BlocklistRepository` already prefers a downloaded
  list over the bundled one; the WorkManager job that fetches it is not written.
  Ship list updates without shipping an APK.
- **Per-app blocking.** DNS blocking cannot stop a native gambling app that
  talks to a hardcoded IP. Detecting installed gambling apps by package name
  and warning the user is cheap and closes a real hole.

## Later

- **iOS** — `docs/IOS_PLAN.md`. File the `FamilyControls` entitlement request
  now; it is the long pole.
- **Web version** — a browser extension is a much weaker guarantee than the
  phone app (it protects one browser on one machine), but it is the right
  surface for a desktop-heavy office or school lab. Reuse `rules.json` directly.
- **Barangay / school deployment kit** — `docs/NETWORK_BLOCKING.md` already
  produces the RPZ and Pi-hole artifacts. A short Tagalog setup guide would
  make it usable by people who are not network engineers, and that is where the
  scale is.
- **Tagalog / Bisaya localisation.** The audience is Filipino; English-only is
  a real barrier for part of it.
