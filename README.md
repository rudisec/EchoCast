# 🎧 EchoCast - Multi-Audio Call Player

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/rudisec/EchoCast/main/docs/Logo-light.png">
  <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/rudisec/EchoCast/main/docs/Logo.png">
  <img src="https://raw.githubusercontent.com/rudisec/EchoCast/main/docs/Logo.png" alt="EchoCast" width="200">
</picture>

EchoCast is an Android application that plays multiple audio files to the other party during phone calls. It allows you to create a playlist/soundboard and play sounds, music, or voice recordings to the person you're talking to.

## ⚠️ Disclaimer

**This application is intended for legitimate, creative, and entertainment purposes only.** It is not designed, intended, or endorsed for use in deceptive, fraudulent, or malicious activities in real-life situations. Users are solely responsible for complying with applicable laws and regulations, and for obtaining any necessary consent when using this software. The developers assume no liability for misuse.

---

## ✨ Features

### Soundboard & Playback
* **Multiple Audio Support**: Add and manage multiple audio files in your soundboard
* **Play Modes**: 
  - **Manual**: Play individual audios on demand
  - **Loop**: Repeat a single audio continuously
  - **Shuffle**: Play audios in random order with Play/Pause control and Loop option
* **Edit & Delete**: Long-press any audio to rename it or remove it from the soundboard
* **Quick Settings Tile**: Easy access from the notification panel

### AI Voice (ElevenLabs & MiniMax)
* **ElevenLabs Integration**: Voice training and Text-to-Speech with custom voices
* **MiniMax Integration**: Voice cloning and Text-to-Speech with custom voices
* **Generate & Add**: Create audio from text and add it directly to your soundboard

> **Important**: You must use **your own API keys** from [ElevenLabs](https://elevenlabs.io) and/or [MiniMax](https://api.minimax.chat) respectively. EchoCast does not provide API keys—each user must obtain and configure their own credentials from these services.


## 📋 Requirements

* Android 9.0 (API 28) or higher
* Device with telephony audio output support (most modern Android phones)
* Root access (via Magisk) or custom ROM installation capabilities
* Your own ElevenLabs and/or MiniMax API keys (for AI features)

## 📱 Compatible Devices

This application has been tested and confirmed to work on the following devices:

| Device | Android Version | Status |
|--------|-----------------|--------|
| Samsung Galaxy A52s 5G | Android 13 | ✅ Works correctly |

Do you have EchoCast working on your device? Let us know to add it to this list!

## 📥 Installation

EchoCast requires system-level permissions to function properly, so it must be installed as a system app:

### 🔧 For Rooted Devices (Magisk)

**Option A – Download pre-built (recommended)**

1. Go to [Releases](https://github.com/rudisec/EchoCast/releases) and download the latest `EchoCast-*.zip`
2. Open Magisk Manager → Modules → Install from storage
3. Select the downloaded zip file
4. Reboot your device
5. Open EchoCast from your app drawer

**Option B – Build from source**

1. Clone the repo and run:
   ```bash
   ./gradlew zipRelease
   ```
2. The module ZIP will be in `app/build/distributions/release/` (optimized build for distribution)
3. Follow steps 2–5 above

   For a quick debug build instead: `./gradlew zipDebug` → output in `app/build/distributions/debug/`

## 📱 Usage

1. Open EchoCast
2. **Enable playback** by toggling the switch
3. Add audio files to your soundboard using the "Add Audio" button
4. Select a play mode (Manual, Loop, or Shuffle)
5. **Shuffle mode**: Use the Play/Resume button to start random playback during a call; enable Loop to repeat when finished
6. Long-press an audio to edit its name or delete it
7. For AI features, configure your ElevenLabs and/or MiniMax API keys in the AI tab
8. When you receive or make a call, play your soundboard or generated audio to the other party

## ⚙️ How It Works

EchoCast uses Android's InCallService API to detect active phone calls. When a call is detected, it uses the `MODIFY_PHONE_STATE` privileged system permission to create an `AudioTrack` instance for the telephony output device, allowing it to play audio directly to the call.

The application supports playing multiple audio files sequentially, with support for different play modes to suit your needs.

## 📜 License

EchoCast is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

## 👏 Acknowledgments

EchoCast is based on Basic Call Player (BCP) by chenxiaolong, which is licensed under GPL-3.0.
