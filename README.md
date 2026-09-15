# StayAwake

A tiny (~11 KB) Windows system-tray app that keeps your PC from going idle, so messaging apps like **Slack, Microsoft Teams, and Discord** keep showing you as **Active** instead of *Away* or *Offline*.

No installer. No dependencies. No background services. Just one small `.exe`.

## How it works

Slack, Teams, and similar apps decide you're "away" based on your **input idle time** — how long since your last keystroke or mouse move — *not* whether your computer is asleep.

StayAwake sends an invisible **F15 keypress** at a set interval (every minute by default). F15 is a key that doesn't exist on modern keyboards and does nothing in any program — it simply resets Windows' idle timer, which is exactly what those apps read. It also calls the Windows `SetThreadExecutionState` API to stop the PC and monitor from sleeping.

## Download & run

1. Grab `StayAwake.exe` from the [latest Release](../../releases/latest).
2. Double-click it. On first run, Windows may show **"Windows protected your PC"** (normal for any unsigned app):
   - Click **More info** → **Run anyway**.
3. A green circle appears in your system tray (click the `^` near the clock if hidden).
   **Green = active, Grey = paused.**

## Using it

**Right-click** the tray icon:

| Menu item | What it does |
|---|---|
| **Keep me active** | Toggle on/off (or just **double-click** the icon) |
| **Keep display on** | Also prevents the monitor from sleeping |
| **Activity interval** | 30s / 1 min / 2 min / 5 min |
| **Start with Windows** | Auto-launch at login |
| **Exit** | Quit and let the PC sleep normally again |

Tip: turn on **Start with Windows** once and forget about it.

## Is it safe?

Yes. The F15 keypress is harmless — it won't type anything, interrupt your work, affect games, or change any settings. The entire source is one readable C# file ([`StayAwake.cs`](StayAwake.cs)), and it makes exactly two Windows API calls (`SendInput` for F15, `SetThreadExecutionState` for sleep). No network access, no telemetry, no data collection.

## Build it yourself

No SDK or downloads required — every Windows PC ships with the .NET Framework C# compiler.

```bat
build.bat
```

Or manually:

```bat
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:winexe /optimize+ /out:StayAwake.exe /reference:System.Windows.Forms.dll /reference:System.Drawing.dll StayAwake.cs
```

## Note

This makes your desktop **show** as available — it doesn't send messages or do work for you. If someone is watching actual message activity, they'll still see when you're not really there. The Slack **mobile** app reports presence separately and isn't affected.

## StayAwake Auto (Android Auto app)

The [`android/`](android/) folder holds a separate project: a side-loaded Android Auto
app for a Galaxy S25 Ultra that puts YouTube, Netflix and Prime Video on the car screen
and only allows playback while the car is stopped. See [android/README.md](android/README.md)
for how it works, how to build it (GitHub Actions produces the APK) and how to enable it in
Android Auto.

## License

[MIT](LICENSE)
