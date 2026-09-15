# StayAwake Auto (Android)

A personal-use Android Auto app for a Galaxy S25 Ultra that shows **YouTube, Netflix and
Prime Video** on the car screen, and only lets video play **while the car is stopped**.

It is not a Play Store app and never will be: Android Auto does not allow video apps from
third parties, so this one is side-loaded and enabled through Android Auto's developer
settings. Use it in your own car, parked.

## How it works

* The app registers with Android Auto as a navigation-category car app. Navigation apps
  are the only ones that get a raw drawing surface on the car screen.
* That surface is wrapped in a `VirtualDisplay`, and a `Presentation` containing a
  `WebView` is shown on it. The WebView loads the mobile YouTube site, or the desktop
  Netflix / Prime Video sites (their players need a desktop browser and Widevine, which
  the WebView provides).
* Taps and scrolls from the car's touch screen are converted into touch events and fed
  to the WebView. The car's keyboard template is used for search and for typing into the
  page.
* A **parked gate** watches vehicle speed. It prefers the speed the head unit reports
  through Android Auto (`CAR_SPEED` permission) and falls back to the phone's GPS. The
  moment speed rises above the threshold (default 5 km/h) a full-screen overlay covers
  the page and every `<video>` element is paused. With no speed data at all the overlay
  also stays up.
* Logins are shared between the phone and the car because both use the same WebView
  cookie jar. Sign in once on the phone in the app's setup screen.

## Building

Every push to `android/**` builds debug and release APKs in GitHub Actions
(**Actions → Build Android Auto APK → Artifacts → StayAwake-Auto-apk**). The release APK is
signed with the debug key on purpose so it installs directly.

Locally: open the `android` folder in Android Studio, or run
`./gradlew assembleDebug` with JDK 17 and an Android SDK that has platform 35.

## Installing and enabling

1. Install the APK on the phone (allow installs from your browser or file manager).
2. Open **StayAwake Auto** on the phone and work through the four sections:
   grant Location and Car speed, sign in to the services you use, and pick options.
3. In the **Android Auto** settings on the phone: scroll to the bottom, tap **Version**
   about ten times to unlock developer settings, open the three-dot menu → **Developer
   settings** → enable **Unknown sources**. Then open **Customize launcher** and tick
   StayAwake Auto.
4. Connect to the car. StayAwake Auto appears in the Android Auto launcher.

On the car screen: the start page has three tiles. The buttons on the template are Home,
Search, Type (into the focused field), Back; the map buttons are Pan and Reload. If taps
on the page seem ignored, press the pan (hand) button once so the head unit forwards
touch gestures.

## Things to know

* **Parked only.** This is enforced, not optional. Grant the Car speed permission; it is
  the most reliable source and works in garages where GPS does not. The "confirm I am
  parked" option only appears when there is no speed data at all and is off by default.
* **Netflix / Prime quality.** Web playback in a WebView uses Widevine L3, so expect SD
  quality. That is a Netflix/Amazon policy, not something the app can change.
* **Google sign-in** inside an embedded browser is sometimes refused. If that happens,
  YouTube still works without an account, or sign in on the phone-side screen.
* **Audio** plays through whatever Android Auto is routing phone media to (USB or
  Bluetooth), like any other media on the phone.
* **Phone screen.** By default the app holds a dim wake lock while the car screen is
  active so rendering never stalls when the phone sleeps. Turn it off in Options if
  your car keeps the phone awake anyway.
