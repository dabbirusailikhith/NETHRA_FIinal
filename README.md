# NETHRA

**Hands-free video, end to end, on one phone.**

NETHRA is an Android camera app for solo short-form creators. Writing the script,
reading it off a prompter, getting yourself in frame, recording, captioning and
publishing all happen behind one shutter button — controlled by voice, in English,
Hindi or Telugu.

It looks and swipes like the phone's own camera. Four modes sit in one carousel:

| Mode | What it does |
|---|---|
| **CAMERA** | Hands-free recording. "Nethra, start recording / pause / resume / stop", with a spoken countdown. |
| **TELEPROMPTER** | Speak a brief → a researched script → a prompter that scrolls at *your* pace. |
| **AI FRAMING** | Draw a target box, walk away, and be told out loud where to stand. |
| **TRANSCRIPT** | Word-for-word transcript, YouTube/Instagram publishing kit, direct upload. |

---

## Table of contents

- [Features](#features)
- [The hybrid model: on device vs cloud](#the-hybrid-model-on-device-vs-cloud)
- [Languages: English, Hindi, Telugu](#languages-english-hindi-telugu)

---

## Features

### Hands-free recording
- Wake word **"Nethra"** plus `start recording`, `pause`, `resume`, `stop`.
- A spoken countdown before recording starts, so you can get into position.
- The command words are cut out of the finished video automatically: `CommandCutPlanner`
  finds the exact boundaries in the recording's own loudness envelope, and a clean copy
  is exported with Media3 Transformer. The original is never modified.
- While recording, NETHRA captures the microphone itself and streams it to the speech
  recogniser through a pipe (`MicFeed`), because Android will not give the mic to two
  apps at once.

### Voice-paced teleprompter
- Speak a brief; NETHRA turns it into a structured brief you can edit, then writes the
  script with web research for anything factual.
- The prompter follows the words it actually hears (`ScriptTracker`) and estimates your
  speaking rate from syllable peaks on the phone (`SpeechRate`), so it scrolls at your
  pace, not a fixed WPM. A fixed-WPM mode is available in settings.
- The script panel can be dragged, resized and rotated; portrait and landscape each
  remember their own layout.

### AI framing coach
- ML Kit pose and face detection on every analysed frame; no video leaves the phone.
- Every frame produces a position error, a size ratio, an overlap with your target box
  and a confidence — visible live, logged once a second, and asserted in unit tests.
- Smoothing and hysteresis stop the coach nagging at the edge of the box.
- Three strictness presets: Relaxed, Standard, Precise.
- Guidance is locked once recording starts, so it does not talk over your take.

### Automatic captions
- After each take, a second copy is saved with word-by-word captions burned in
  (`…_captions.mp4`), plus an `.srt` file for YouTube.
- Model timings are approximate, so each segment is snapped onto the real speech found
  in the waveform (`CaptionBuilder.snapToSpeech`) — this is what makes the highlight land
  on the right word.
- Cards are placed clear of where Reels and Shorts put their own buttons.

### Publishing
- Transcript → YouTube title, description and tags, plus an Instagram caption and hashtags.
- Direct upload to YouTube via the Data API v3, in resumable 8 MB chunks.
- Handoff to Instagram and other apps for anything not uploaded directly.

---

## The hybrid model: on device vs cloud

The rule is simple: **anything instant or private runs on the phone; the cloud is used
only where world knowledge is genuinely needed; and the app never fakes it when offline.**

**On the phone, always**
- ML Kit pose and face detection, and all framing geometry
- Microphone capture at 16 kHz in 20 ms frames, with voice-activity detection
- Speaking rate from syllable peaks, tuned per language
- Wake word, command parsing, script tracking, pace following
- Hindi/Telugu transliteration and sound-key matching (`Translit`)
- Command cut points, caption rendering and re-encoding (Media3 Transformer)
- Optional Gemma (LiteRT-LM) as the offline transcriber and writer

**In the cloud, only when it earns it** (via [OpenRouter](https://openrouter.ai))
- `anthropic/claude-sonnet-5` writes scripts, with live web search for facts
- `google/gemini-3.8-flash` transcribes audio with timestamps, and writes the publishing kit
- YouTube Data API v3 for uploads

**What crosses the boundary:** extracted audio and text only. A video frame or the video
file itself is never uploaded. This is why framing works in airplane mode and why a face
is never sent anywhere.

### Offline behaviour

| Feature | Runs where | With no internet |
|---|---|---|
| Framing coach and spoken guidance | Phone | Works fully |
| Recording and voice commands | Phone | Works fully |
| Teleprompter pace following | Phone | Works fully — pace comes from your voice |
| Clean copy with commands cut out | Phone | Works fully |
| Transcript, captions, publishing kit | Both | On-device Gemma takes over |
| Script writing, YouTube upload | Cloud | Refused clearly; your brief and video are kept |

Offline transcription needs a Gemma `.litertlm` build that **includes the audio encoder**
(Gemma 3n E2B/E4B). A text-only file still writes the publishing kit; NETHRA says so
plainly rather than returning an empty transcript. See [`model/README.md`](model/README.md).

---

## Languages: English, Hindi, Telugu

Most Indian short-form creators speak Hindi or Telugu but read English letters faster —
so they write scripts in **Hinglish** and **Tinglish**. The speech recogniser, meanwhile,
answers in Devanagari or Telugu script. A naive prompter can never match the two.

NETHRA closes that gap on the phone:

```
heard  करेंगे  →  romanised  karenge  →  sound key  krenge
                                                      ≈           → match
script karege  ────────────────────→  sound key  krege
```

`Translit` romanises Devanagari and Telugu, then reduces any Latin spelling to a loose
sound key (long vowels shortened, aspirates dropped, doubled letters collapsed, the
short "a" removed). So `करेंगे`, `karenge`, `karege` and `kareinge` all compare equal.
It is pure Kotlin and unit tested with real Hindi and Telugu sentences.

**Two settings appear once Hindi or Telugu is selected:**

- **Teleprompter script** — *Hinglish/Tinglish* (default; new scripts are written that way)
  or *हिन्दी / తెలుగు* native script.
- **Captions** — *हिन्दी / తెలుగు* (as spoken, native script), *English* (translated, but
  timed to the original speech), or *Hinglish/Tinglish* (as spoken, English letters).

Pace is tuned per language, because Telugu is agglutinative and packs far more syllables
into a word than English (`ScriptLanguage`: 1.5 syllables/word and 150 WPM for English,
2.1 / 120 for Hindi, 2.9 / 105 for Telugu).

---
