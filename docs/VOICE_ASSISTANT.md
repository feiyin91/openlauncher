# Voice Assistant Edition

This branch (`feat/gemini-voice-assistant`) is a superset of [`feat/dashboard-enhancements`](https://github.com/feiyin91/openlauncher/tree/feat/dashboard-enhancements) — same dashboard, themes, and widgets, plus a full Gemini-powered voice assistant with an offline wake word. It needs your own Gemini API key to build (see [Setup](#setup) below); everything else works identically to the plain dashboard branch out of the box.

If you don't want a voice assistant at all, use `feat/dashboard-enhancements` instead — it's the lighter, dependency-free branch this one is built on top of.

## What's different

### New: voice commands
Tap the mic on the control rail, or say the wake word, then speak naturally. A single Gemini call (`gemini-3.5-flash-lite`) turns the transcript into a structured action. Supported today:

- **Dashboard control** — theme, day/night mode, clock format (12/24h), navigate to a screen
- **Volume** — up/down/mute, with an amount ("turn it up by three")
- **Media** — play/search a song or artist, skip/previous track, play/pause
- **Navigation** — send a destination to Waze, including saved "home"/"work" addresses
- **Fuel log** — add an entry by odometer/volume/cost, spoken naturally
- **Apps** — open any installed app by name
- **Bluetooth** — connect/disconnect a specific paired device by name
- **Answers** — weather, time, location, fuel efficiency, today's driving distance, current speed/heading, sunrise/sunset — answered from live on-device data, not a web search
- Replies are spoken aloud via on-device TTS, with a selectable voice in Settings

### New: offline wake word ("Hey Sebastian")
Fully on-device, no network dependency — runs [openWakeWord](https://github.com/dscripka/openWakeWord)'s 3-stage ONNX pipeline (melspectrogram → embedding → a custom-trained classifier) via `onnxruntime-android`, in a foreground service so it keeps listening regardless of which app is in front.

**"Hey Sebastian" is our car's name — you'll want your own.** Retraining is fully self-contained: `docs/wakeword/hey_sebastian_retrain.ipynb` runs top to bottom in Google Colab (free T4 GPU) with every dependency-drift fix already applied — this notebook's upstream lineage is 2023-era and has drifted hard from what Colab ships today; expect a fresh run to just work rather than needing to redebug the pipeline. Includes a "listen before you commit" step so you can preview different spellings of your phrase before spending the ~45 min training run. Swap the resulting `.onnx`/`.onnx.data` files into `app/src/main/assets/wakeword/` and update the filename referenced in `WakeWordEngine.kt`.

*Why on-device instead of Gemini Live (cloud wake word)?* We tried Gemini's Live API (WebSocket) first. The protocol itself checked out fine in isolated testing, but it was unreliable specifically over this car's cellular network — reproduced across two different phone hotspots. Its code is still in the tree behind a `USE_GEMINI_LIVE = false` flag in `LauncherViewModel`, in case your network path works better.

### New: control rail
A reserved column beside the widget grid (not a floating overlay — that covers real widget content whenever the layout is fully packed) holding, bottom to top: mic, volume down/up, Bluetooth, WiFi. Flat-tile styling matches whatever theme/widget cards you're already using — same `background`/`border`/corner-radius tokens, not a generic Material button glued on top.

### New: curated Bluetooth + WiFi panels
- **Bluetooth**: in-app bottom sheet listing paired devices with Connect/Disconnect — Android has no themed system equivalent, so this is fully custom (reuses the same reflection-based A2DP proxy call the voice command uses).
- **WiFi**: themed status card (SSID, signal strength, connected/not). The actual "add or switch network" flow still hands off to Android's own system panel — apps haven't been able to drive a real WiFi connection themselves since Android 10, this isn't a gap more work closes.

### New: background trip tracking
A real foreground service (`TripTrackingService`) accumulates today's driving distance via GPS regardless of which app is in front — the dashboard's own trip widget only sees GPS updates while it's on screen otherwise.

### New permissions required
`RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` / `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`. The last one lets voice "go back to home screen" actually bring the app forward while another app (Spotify, Waze) has the screen — a background app can't normally start a new Activity on modern Android. If your device's Settings app doesn't handle the standard permission-request intent cleanly (this varies a lot across cheap aftermarket ROMs), grant it manually: **Settings → Apps → OpenLauncher → Display over other apps**.

## Known limitations (platform walls, not bugs)

Worth knowing before you file an issue about these — all confirmed dead ends, not unfinished work:

- **No split-screen with other apps.** `FLAG_ACTIVITY_LAUNCH_ADJACENT` doesn't do anything on most aftermarket head unit ROMs — they don't expose real multi-window to third-party apps. CarPlay/Android Auto dongles achieve their "split view" via video-stream projection inside their *own* single app, a fundamentally different mechanism not replicable for arbitrary apps like Waze or Spotify.
- **Can't start Spotify (or most streaming apps) headlessly.** Their `MediaBrowserService` whitelists callers — only Android-Auto-certified apps get through. No way to begin playback without the target app's own UI at least flashing up briefly.
- **Volume step size depends on your device's hardware range.** `AudioManager` volume is quantized to whatever `getStreamMaxVolume(STREAM_MUSIC)` returns (often as coarse as 15 steps on cheap units) — you may not be able to hit an exact percentage per tap.
- **Wake word won't fire reliably over loud music** on hardware with no acoustic echo cancellation (`AcousticEchoCanceler.isAvailable()` returns false) — common on budget aftermarket units. The mic button on the control rail is the reliable fallback in that case.

## Setup

### 1. Get a Gemini API key
Free tier available at [Google AI Studio](https://aistudio.google.com/) — sign in, create an API key.

### 2. Add it to `local.properties`
This file is gitignored — your key never ends up in source control, even accidentally. Create or edit `local.properties` in the repo root:

```properties
gemini.api.key=YOUR_KEY_HERE
```

If this key is missing or blank, the build still succeeds — voice commands just fail gracefully with a "not configured" message rather than crashing.

### 3. Build
Same as the dashboard branch:
```
JAVA_HOME="<path to a JDK 17+>" ./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`.

### 4. First launch
Grant microphone and Bluetooth permissions when prompted (bundled into one dialog). If you want voice "go home" to work while another app has the screen, also grant **Display over other apps** (Settings → Apps → OpenLauncher) — see the permissions note above if the in-app prompt for this doesn't fire correctly on your ROM.

### 5. (Optional) Train your own wake word
Ships with "Hey Sebastian" by default — a real, working wake word, just not likely to be *your* car's name. See [docs/wakeword/hey_sebastian_retrain.ipynb](wakeword/hey_sebastian_retrain.ipynb) — upload to Colab, set the runtime to a T4 GPU, and run top to bottom. The pronunciation-preview step lets you listen to synthesized samples of several spellings before committing to a full training run, since the same phrase can train very differently depending on how it's spelled.
