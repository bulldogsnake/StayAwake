# Compose and AndroidX ship their own consumer rules; nothing custom is needed
# for the MVP. Keep the VpnService subclass name stable for the system binder.
-keep class ph.tigil.blocker.vpn.TigilVpnService { *; }
-keep class ph.tigil.blocker.system.** { *; }
