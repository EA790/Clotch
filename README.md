# Clotch 🎬

**Did you clip that "CLOTCH"?** 

Clotch is a native Android screen recorder and professional video editor built for gamers, by gamers. No Electron, no web wrappers—just pure Kotlin and FFmpeg power.

## Why Clotch?
Ever hit an insane clip but your phone's default recorder lagged or made the game unplayable? I built Clotch to fix that. It's lightweight, records in up to 1440p at 60FPS, and has a built-in video editor that rivals desktop apps.

## Features
- **Screen Recording:** 1080p/60fps, 1440p, custom bitrates, and separate mic/game audio recording.
- **Pro Video Editor:** Timeline, trim, split, keyframes, text overlays, filters, and AI Chroma Key (Green Screen).
- **AI Auto Captions:** Hybrid offline + Gemini 1.5 Flash integration.
- **Discord Rich Presence:** Show your friends what you're playing (Powered by KizzyRPC).
- **Gallery Import:** Edit external videos right inside the app.
## 🚀 Roadmap
Here is what I'm currently working on and what's coming in future updates:

### ✅ Completed (v1.0)
- [x] Native Screen Recording (Up to 1440p @ 60FPS).
- [x] Separate Mic & Game Audio recording.
- [x] Pro Video Editor (Timeline, Trim, Split, Undo/Redo).
- [x] Text Overlays, Emojis, and Animations.
- [x] Advanced Chroma Key (Green Screen AI - Moving/Still modes).
- [x] Hybrid AI Auto Captions (Offline + Gemini API).
- [x] FFmpeg Video Export (MP4 & MKV).
- [x] Gallery Import.
- [x] Discord Rich Presence (KizzyRPC).
- [x] Crash Logger for easy debugging.

## Built With
- Kotlin & Jetpack Compose (UI)
- Media3 / ExoPlayer (Preview)
- FFmpeg-Kit (Rendering)
- ML Kit (AI Features)
- KizzyRPC (Discord)

## Disclaimer
Clotch is an independent project by **ShutBerry**. Discord Rich Presence uses third-party methods. Use at your own risk.

## How to Build
1. Clone the repo.
2. Open in Android Studio (or Android Code Studio).
3. Run `./gradlew assembleDebug`.
