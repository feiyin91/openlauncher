<div align="center">
  <img width="256" height="256" alt="logoo" src="https://github.com/user-attachments/assets/4c5c4ddb-836d-4c59-8325-76b8c8d78bb3" />
  <h1>Open Launcher</h1>
  <p><strong>An open-source Android launcher built specifically for aftermarket car head units — mostly offline-capable, see below for exactly which parts need a connection.</strong></p>

  [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
  [![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](http://makeapullrequest.com)
</div>

> **Fork notice:** this is an independent fork of [dw2lam/openlauncher](https://github.com/dw2lam/openlauncher), the original project the sections below describe. Full credit to dw2lam for the foundation this builds on. Development here continues independently going forward rather than through upstream PRs, since the upstream repo hasn't seen maintainer activity in some time.
>
> **You're on the `feat/gemini-voice-assistant` branch** — dashboard-enhancements plus a full Gemini-powered voice assistant and an offline "Hey Sebastian" wake word. Needs your own free Gemini API key to build. See **[docs/VOICE_ASSISTANT.md](docs/VOICE_ASSISTANT.md)** for exactly what's different and a full setup guide. Don't want the voice assistant? The plain dashboard branch is [`feat/dashboard-enhancements`](https://github.com/feiyin91/openlauncher/tree/feat/dashboard-enhancements) — no extra permissions, no API key needed.

---

## 📖 Table of Contents
- [Why this exists](#-why-this-exists)
- [The Philosophy](#-the-philosophy-oem-aesthetics-honestly-labeled-offline-support)
- [Current Features](#-current-features)
- [Roadmap](#️-roadmap)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🛑 Why this exists

Three reasons the original project started, still true here:

1. **No premium paywalls** for basic dashboard functionality.
2. **Community-driven and open-source** — a foundation others can actually build on, fork, and improve.
3. **Clean aesthetics** — most Android launchers look like cheap video games or have half their features broken. This is built to look like it belongs in a car, not a phone screensaver.

## 🧠 The philosophy: OEM+ aesthetics, honestly labeled offline support

Most aftermarket head units rely on wireless CarPlay or Android Auto for navigation and media, so the launcher itself is what you're actually looking at most of the drive. The goal is for it to look like it belongs in the car's interior, not like an app running on top of one.

**On the offline claim specifically** — the original framing was "offline-first," which oversells it. Here's what's actually true:

- **Fully offline, always:** the widget grid itself, themes/personalization, Clock, Speedometer, Altimeter, Trip Meter, GPS Compass, Head Unit Vitals, Soundboard, Fuel Log, and GPS-based sunrise/sunset day-night switching. None of these make a network call.
- **Needs a data or WiFi connection:** the Weather widget (calls a weather API) and the Location widget's place-name display (reverse-geocodes your GPS coordinates via the free Nominatim API). Both degrade gracefully — Weather hides itself rather than showing stale/wrong data when offline, and Location just shows raw coordinates instead of a place name.
- The [voice assistant branch](https://github.com/feiyin91/openlauncher/tree/feat/gemini-voice-assistant) obviously needs a connection too, for the Gemini API calls — see that branch's own docs.

So: most of the dashboard works with zero signal, which is the common case for these head units. A couple of specific widgets don't, and now you know exactly which ones before you're relying on them.

---

## ✨ Current features

*(Real screenshots from an actual running build are pending — the placeholder images from the original project have been removed rather than left showing an interface that no longer matches what this fork actually looks like.)*

### 🎨 8-theme dashboard system
Eight built-in themes — Ignition, Amber, Cobalt, Verdigris, Plum, Blueprint, Circuit, Instrument — each with its own accent, background, and ink color defined for both day and night, auto-switching with sunrise/sunset. Beyond the presets, full manual control over accent color, background color/gradient, wallpaper (with adjustable dim), font weight, text scale, UI scale, and app font.

### 🧩 Modular widget grid
Drag-and-drop, resize-capable grid — every widget can be moved, scaled, and stacked freely. Add or remove widgets from the built-in library at any time. Two ready-made layout presets (default grid, and a media-dominant split panel) if you don't want to build one from scratch.

### 🎛️ Widgets
* **Now Playing** — track metadata, centered album art, playback controls; source badge shows which app is playing.
* **Fuel Log** — log fill-ups (odometer, volume, cost) and see computed efficiency over time.
* **Quick Toggles** — WiFi/Bluetooth/DND launchers from the home grid.
* **Location** — live GPS + reverse-geocoded place name, with a choosable detail level (neighborhood/city/region).
* **Weather** — current conditions plus an hourly forecast row, place name shown in preference to raw coordinates.
* **Digital/Analog Clock** — greeting + day/night icon, large time and date; optional sunrise/sunset row.
* **AM/FM Radio** — on szchoiceway-based units, talks to the MCU directly (seek, band switching, frequency presets with memory); on other units, mirrors and controls your vendor radio app through its media session.
* **Speedometer / Altimeter / GPS Compass** — standalone GPS-based readouts, placeable independently anywhere on the grid.
* **Trip Meter** — rolling odometer with distance and elapsed time, plus a hidden 0–100 km/h timer (tap the label to reveal it).
* **Head Unit Vitals** — CPU load, memory pressure, temperature.
* **Soundboard** — 6 assignable pads, built-in synth sounds or your own audio files.

### 🗂️ App Library
Pulls every installed app, including buried system-level CarPlay/Android Auto receiver apps that most launchers don't surface.

### 📌 Sidebar shortcuts
Drag to reorder, long-press to remap, position the bar Left/Right/Bottom to suit your driving hand.

### 🌗 Day/night modes
Forced Dark, Forced Light, System Sync (follows the head unit's own setting), or Sunset Mode (auto-switches at local sunrise/sunset via offline GPS-based calculation — no network call for this one).

### 🛰️ GPS with offline calibration
A calibration offset for devices whose GPS chips report inaccurate baselines, accessible from the trip meter settings.

### 📱 Picture-in-Picture overlay ⚠️ *Beta, inherited from upstream*
Launch any app as a floating freeform window. Requires the special `openlauncher-test-pip` build (relies on AOSP platform-level signing) — not in the standard APK. Expect rough edges; compatibility varies heavily by head unit ROM.

### 🔔 First-run onboarding
Explains key permissions (location, notification listener, draw-over-apps) before requesting them, with direct links to the relevant system settings screens.

---

## 🗺️ Roadmap

- [ ] **Advanced color engine** — per-element hex control for every UI surface, to precisely match a specific dashboard's ambient lighting.
- [ ] **Universal theming engine** — a standardized way to build, share, and install full visual themes.
- [ ] Real screenshots of the current build, replacing the placeholder note above.

---

## 🤝 Contributing

Whether you're a developer, a designer, or just testing it in your own car:

1. **Test on your hardware** — install the APK, break things, open an issue.
2. **Feature requests** — open a discussion.
3. **Pull requests** — check open issues first to avoid duplicate work, then fork and submit.

---

## 📄 License

MIT — see [LICENSE](LICENSE). Credit to [dw2lam](https://github.com/dw2lam/openlauncher) for the original project.
