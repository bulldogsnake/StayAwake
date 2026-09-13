# What Tigil cannot do

This page exists because the alternative — letting people believe a blocker is
airtight — is worse than useless. Someone building a recovery plan on a
guarantee that isn't real gets hurt when it fails.

## It is a wall against impulse, not against determination

Tigil blocks gambling sites **by name**. That stops them loading in every app
and browser on the phone, which is what actually matters at 2am when someone
opens an app out of habit. It does not stop a person who sits down and
deliberately works around it.

## Concrete ways around it

| Bypass | Works? | What we do about it |
|---|---|---|
| Turn Tigil off | Yes, unless the commitment lock is running | The lock is enforced in the service, not just the UI — the notification, Quick Settings tile and a shell `am startservice` all refuse while it is active |
| Uninstall the app | Yes | Nothing in the MVP. A Device Admin uninstall-guard is the planned fix (see [ROADMAP](ROADMAP.md)) |
| **Android Private DNS set to a hostname** | **Yes, completely** | Detected and shown as a red banner on the main screen. We cannot change the setting for the user — only an MDM/work profile can |
| Browser "Secure DNS" / DNS-over-HTTPS | Mostly no | Strict mode drops DoH/DoT to the well-known public resolvers we route in |
| DoH to a resolver we don't know about | Yes | Unfixable without a full-tunnel VPN with SNI inspection. Out of scope for an MVP, and a much bigger privacy and battery cost |
| Install another VPN | Yes — Android allows only one VPN at a time, and the new one wins | Detected via `onRevoke()`; the app asks to re-arm on next launch |
| Type the raw IP address | Yes | DNS blocking is name-based by definition. IP blocking needs full-tunnel packet filtering |
| Reboot the phone | No | `BootReceiver` re-arms automatically |
| Use a different phone, or a PC | Yes | This is why the network-level blocking in `docs/NETWORK_BLOCKING.md` matters — it covers every device on the home or office Wi-Fi |
| Mobile data instead of Wi-Fi | No (on-device blocking is per-device, not per-network) | — |

## Reboot and update gaps

- Between boot and `BOOT_COMPLETED` there is a short unprotected window.
- If VPN consent has been revoked, the boot receiver cannot re-arm; the user
  must open the app once.

## The blocklist is always slightly behind

Philippine operators rotate domains constantly. The heuristics in
`blocklist/seed/keywords.txt` are what keep this from being a losing race —
they match the *shape* of an operator domain rather than a specific one — but a
genuinely novel brand on a neutral domain will get through until it is added.

## What it is not

Tigil is not treatment. It buys time and removes the easy path. If gambling is
costing someone money, sleep, or relationships, that needs a person, not an app.

**Philippines:** NCMH Crisis Hotline **1553** (toll-free, nationwide) or
**0966-351-4518**.
