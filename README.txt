================================================
  StayAwake  -  Stay "Active" in Slack & Teams
================================================

WHAT IT DOES
------------
StayAwake keeps your Windows PC from going idle so messaging apps
(Slack, Microsoft Teams, Discord, etc.) keep showing you as ACTIVE
instead of "Away" or "Offline".

It works by sending an invisible "F15" keypress every minute. F15 is
a key that does not exist on modern keyboards and does NOTHING in any
program -- it just quietly tells Windows "the user is still here," which
is exactly what Slack/Teams check to decide if you're away. It also
stops the PC and monitor from going to sleep.

It runs quietly in your system tray (the little icons by the clock).


HOW TO RUN IT
-------------
1. Double-click  StayAwake.exe

2. The FIRST time, Windows may show a blue box that says
   "Windows protected your PC."  This is normal for any app that
   isn't from the Microsoft Store. To run it:
        - Click  "More info"
        - Click  "Run anyway"

3. A green circle icon appears in your system tray (near the clock).
   You may need to click the small "^" arrow to see hidden icons.
   GREEN = keeping you active.   GREY = paused.


USING IT
--------
RIGHT-CLICK the tray icon for the menu:
   - Keep me active .... turn it on / off (or just DOUBLE-CLICK the icon)
   - Keep display on ... also stops the monitor from sleeping
   - Activity interval . how often it pings (30s / 1 min / 2 / 5 min)
   - Start with Windows  auto-launch every time you log in
   - Exit .............. quits and lets the PC sleep normally again

TIP: Turn on "Start with Windows" once and forget about it.


IS IT SAFE?
-----------
Yes. The F15 keypress is harmless -- it won't type anything, interrupt
your work, mess with games, or change any settings. The full source
code is included in this folder (StayAwake.cs) so anyone can read
exactly what it does. To rebuild it yourself, double-click build.bat
(works on any Windows PC -- no downloads or installs needed).


HEADS UP
--------
This makes your desktop SHOW as available. It does not send messages
or do work for you, so if someone is watching actual message activity,
they'll still see when you're not really there.

The Slack MOBILE app reports your presence separately and isn't
affected by this.


FILES IN THIS FOLDER
--------------------
   StayAwake.exe ... the app (this is the one you run)
   README.txt ...... this file
   StayAwake.cs .... the source code (for the curious / IT review)
   build.bat ....... rebuilds the .exe from source

================================================
