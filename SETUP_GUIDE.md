# NETHRA: Setup, build, install and test guide

NETHRA is a Kotlin/Jetpack Compose Android app for the **iQOO 15 (Snapdragon SM8850)**,
built for arm64-v8a on Android 12+ (minSdk 31, target/compile SDK 36). It has three features:

1. **Framing coach.** Rear camera, a target box you draw, on-device person/face detection, and spoken guidance.
2. **Teleprompter.** Front camera, "Nethra" voice commands, cloud-written scripts,
   speech-following scroll, and recording. A clean second copy has the spoken commands cut out.
3. **Transcript & publish kit.** Pick a video, get a word-for-word transcript and YouTube/Instagram
   text, then hand it off to those apps.

> **New in this update:** iOS-style camera UI with a mode carousel (CAMERA · TELEPROMPTER · AI FRAMING ·
> TRANSCRIPT), direct YouTube upload with title/description/tags (section 12), measurable framing precision, voice recording commands in every camera mode, a front/rear
> flip in the teleprompter, and a teleprompter that scrolls at your own speaking pace. See **section 11**.

> **Honesty note.** Everything in this project has been **compiled** and the pure logic is
> **unit-tested on the PC** (41 JVM tests). **No feature has been run on a physical phone
> or emulator yet.** Section 10 lists what must be checked on the device.

---

## 1. Prerequisites and Android Studio setup

| Need | Version used / tested for building |
|---|---|
| Windows 10/11 PC | Windows 11 |
| Android Studio | Any current stable release (Narwhal or newer) with AGP 8.13 support |
| JDK | 17 or newer. Android Studio's bundled JBR works; the builds here used JDK 21 |
| Android SDK Platform | **API 36** (SDK Manager → SDK Platforms → Android 16) |
| SDK Platform-Tools | Latest (provides `adb`) |
| Disk space | **At least 5 GB free**. The Gradle caches plus a 135 MB debug APK and intermediates need room. A full C: drive makes Gradle fail with confusing I/O errors (it happened during this build). |
| Internet | For the first Gradle sync (dependencies) and for the cloud features |

Project toolchain (no action needed; the Gradle wrapper downloads it):
Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.4.20 with the Compose compiler plugin.
Libraries: CameraX 1.6.2, Media3 Transformer 1.11.1, ML Kit pose (accurate) and face detection,
OkHttp 4.12, LiteRT-LM 0.17.1 (optional Gemma), Compose BOM 2024.12.01.

In Android Studio:

1. **Settings → Languages & Frameworks → Android SDK**: install *Android 16 (API 36)* and *Android SDK Platform-Tools*.
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**: choose the bundled JBR or any JDK 17+.

## 2. Opening and building the project

**Android Studio:** *File → Open* → select `C:\Users\dabbi\Desktop\NETHRA PowerShell Build` → let Gradle sync
→ *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

**PowerShell** (from the project folder):

```powershell
cd "$env:USERPROFILE\Desktop\NETHRA PowerShell Build"
.\scripts\build-debug.ps1           # assembleDebug
.\scripts\build-debug.ps1 -Test     # also runs the unit tests
.\scripts\build-debug.ps1 -Clean -Test
```

The script finds a JDK, checks `local.properties` (it only reports *whether* a key is set, never the value),
runs Gradle and prints the APK path. The equivalent without the script is:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug testDebugUnitTest
```

Output: **`app\build\outputs\apk\debug\app-debug.apk`** (about 135 MB). The debug build is not minified.
Most of the size is the extended Material icon set, the LiteRT-LM runtime and ML Kit's native
detectors. A `release` build type with R8 minification exists but has no signing config; add your own
before using `assembleRelease`.

Unit test report: `app\build\reports\tests\testDebugUnitTest\index.html`.

## 3. Gemma model location and format (optional)

Gemma is **optional** and only used to write the publish kit when the cloud can't be reached.
It is never used for scripts (cloud-only by design) or transcription. **No model weights are in
the project or APK.**

- **Format:** a single LiteRT-LM **`.litertlm`** file, an instruction-tuned Gemma build for Android,
  e.g. from Hugging Face `litert-community` (E2B ≈ 3 GB, E4B ≈ 4–5 GB).
- **Location on the phone:** `/sdcard/Android/data/com.nethra.app/files/model/`
  (fallback: `/data/local/tmp/nethra/`). The largest `.litertlm` file found is used.
- **Install it:** in the app (*Import Gemma model*), or with
  `.\scripts\push-model.ps1 -ModelPath "C:\path\model.litertlm"`, or `adb push`.

Full details: [`model/README.md`](model/README.md).

## 4. The OpenRouter key in `local.properties`

Scripts, transcription and the cloud publish kit use [OpenRouter](https://openrouter.ai). Create a key
at openrouter.ai → *Keys*, and add credit to the account.

Open **`local.properties`** in the project root. The file is listed in `.gitignore`. It already contains a placeholder line.
Paste the key after the `=`:

```properties
sdk.dir=C\:\\Users\\dabbi\\AppData\\Local\\Android\\Sdk
OPENROUTER_API_KEY=sk-or-v1-...your key...
```

Then rebuild. In Android Studio, sync Gradle first. The home screen shows **"Cloud key set"** or **"No cloud key"**.

How the key is handled:

- It is read **only** from `local.properties` at build time (`app/build.gradle.kts`). It is not in source,
  assets, resources, `output-metadata.json` or logs. The app never logs it, and `build-debug.ps1` never prints it.
- It is XOR-masked with a random per-build mask before going into `BuildConfig`. It is unmasked in memory
  only when a request is sent. A test build with a dummy key was scanned: the key text did not appear
  anywhere in the APK or in the generated sources.
- **This is obfuscation, not security.** Anyone who has your APK can recover the key with some effort.
  **Never share an APK built with your key.** For a public app you would route requests through your own
  server. If you think a key has leaked, revoke it on openrouter.ai.
- If the key is missing, invalid, out of credit or rate-limited, the app shows a clear message.
  It keeps your brief, script, transcript and kit.

## 5. Where to change the model IDs

All model choices are in **one file**:
`app/src/main/java/com/nethra/app/config/AiConfig.kt`. There is deliberately no model picker in the UI.

| Constant | Current value | Used for |
|---|---|---|
| `SCRIPT_MODEL` | `anthropic/claude-sonnet-5` | Teleprompter scripts (cloud only) |
| `SCRIPT_WEB_RESEARCH` | `true` | Attaches OpenRouter web search so facts can be checked (billed extra) |
| `SPOKEN_WORDS_PER_MINUTE` / `DEFAULT_SCRIPT_SECONDS` | `150` / `60` | Word target (60 s ⇒ 150 words) |
| `TRANSCRIPTION_MODEL` | `google/gemini-3.8-flash` | Word-for-word transcription (must accept audio input) |
| `TRANSCRIPTION_CHUNK_SECONDS` | `120` | Chunk length for long videos |
| `PUBLISH_KIT_MODEL` | `google/gemini-3.8-flash` | YouTube/Instagram kit |
| `LOCAL_*` | `.litertlm`, GPU first | Optional Gemma |

Use exact OpenRouter model IDs from openrouter.ai/models. If an ID doesn't exist, the app reports
"model not found" and names the model. Edit the constant, rebuild and reinstall.

## 6. Install on the phone

**Enable USB debugging on the iQOO 15** (menu names vary by OriginOS/Funtouch version):
*Settings → About phone → Software information* → tap **Software version** 7 times →
*Settings → System management (or Additional settings) → Developer options* → turn on **USB debugging**
and, on vivo/iQOO phones, **Install via USB**. Connect by USB and accept the "Allow USB debugging" prompt.

**Android Studio:** select the phone in the device dropdown → **Run ▶**.

**PowerShell/ADB:**

```powershell
.\scripts\install-debug.ps1          # installs app-debug.apk with adb install -r and opens NETHRA
.\scripts\install-debug.ps1 -Build   # build first
```

The same by hand: `adb install -r app\build\outputs\apk\debug\app-debug.apk`

> **Already installed?** An app with the same package name (`com.nethra.app`) from a different build
> (version 1.0, minSdk 26) was found on the connected phone during this build. `adb install -r` will
> **replace** it. If Android refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (different signing key),
> run `adb uninstall com.nethra.app` first. That deletes the other build's data.

## 7. Permissions and testing all three features

Permissions are asked **only when a feature needs them**. Each request comes with an explanation screen first.

| Permission | Asked when | If denied |
|---|---|---|
| Camera | Opening *Framing coach* or *Teleprompter* | Explanation plus **Allow** / **Open Settings**; nothing else breaks |
| Microphone | Opening *Teleprompter* | Camera still works; voice off; the **Voice off: tap to allow the microphone** pill asks again |
| Storage | Never | Videos are saved with MediaStore (Android 10+ needs no permission); files are opened with the system picker |

Before testing, make sure a **text-to-speech engine** is installed (Settings → Accessibility/Language →
Text-to-speech) and that **Google speech services** are installed and up to date. The on-device
language pack for your language helps offline use.

### Feature 1: Framing coach (rear camera)

1. Home → **Framing coach** → allow the camera.
2. The preview is portrait 9:16. Drag on the preview to **draw a target box**, drag inside it to move it,
   and drag a corner to resize it. **Reset box** restores the default box.
3. Prop the phone up and have someone stand 2–3 m away. Expect a guidance pill and short spoken prompts:
   *Step to your left/right* (from the person's point of view), *Come closer*, *Step back*,
   *Tilt the camera up/down a little*, and *Perfect. Hold still.* once they fit.
4. With nobody visible: **No person detected**. Partly hidden, backlit or blurred:
   **Detection not confident (NN%): guidance paused**, and it says so instead of guessing.
5. Rotate the phone to landscape. The controls turn to stay upright, and the directions should still be correct (see section 10).
6. Check that the preview stays smooth while guidance updates. Analysis drops frames rather than blocking the preview.

### Feature 2: Teleprompter (front camera)

1. Home → **Teleprompter** → allow camera and microphone. It opens straight to a full-screen front-camera
   preview with a translucent script panel and glass controls. The voice pill should read
   **Listening for "Nethra"**.
2. **Scripts:**
   - Say *"Nethra, write a script about the benefits of cold showers for students, one minute, friendly tone"*.
   - Or say *"Nethra"*, wait for **Yes? Say your brief or a command**, then speak the brief.
   - Or tap ✎ and type it.
   - The brief sheet shows the parsed fields: topic, audience, platform, tone, duration, must include, leave out and call to action. It also shows the target
     ("about 150 words for 60 s"). Edit any field, then tap **Write script** or **Regenerate**.
   - Check the result's word count against the target, and the model, sources and notes. A warning appears if it is short or
     may be cut off. It is never silently shortened.
3. **Recording:**
   - Record, Pause/Resume, Stop and Exit are always on screen.
   - Try both the buttons and *"Nethra, start recording"*,
     *"Nethra pause"*, *"Nethra resume"* and *"Nethra stop"*.
4. **Speech following:**
   - Read the script aloud. The current line should advance with you and tolerate mis-heard words.
   - Drag the script, or use **Previous line** / **Next line** / **Back to start**, to correct it by hand.
   - The settings sheet has **Steady auto-scroll** for when speech can't be followed.
5. **After Stop:**
   - The original recording is saved as `Movies/NETHRA/NETHRA_yyyyMMdd_HHmmss.mp4`.
   - If you *spoke* pause or stop while recording, a progress card appears: **Clean copy (spoken commands removed)**.
   - When it finishes, a second file, `NETHRA_..._clean.mp4`, is saved beside the original, with the command phrases removed.
   - It keeps running if you leave the screen; the home screen shows its progress.
   - Check both files in the Gallery or Files app.
6. **Failure paths to try:**
   - Exit mid-recording: it saves first, and the button shows "Saving…".
   - Deny the microphone.
   - Airplane mode, then Write script: you should get an error, and your brief is kept.
   - A call arriving during recording.
7. **Settings sheet (⚙):**
   - **Voice commands while recording** switches the recorder's mic source so the recogniser has a chance to keep hearing. See section 10.
   - **Silence recogniser beeps**: see section 10.

### Feature 3: Transcript & publish kit

1. Home → **Transcript & publish kit** → **Pick video** (system file picker; no permission prompt).
2. **Transcribe:**
   - Audio is extracted on the phone, split into roughly 2-minute chunks at quiet points, and each chunk is transcribed
     by `google/gemini-3.8-flash` in the spoken language, word for word.
   - There is a progress bar and **Cancel**. Failed chunks are marked in [brackets] and can be retried with **Retry N failed**.
3. **Create kit:** YouTube title, description, tags and hashtags, and the Instagram caption and hashtags. Everything is editable,
   with character counts against the platform limits and copy buttons.
4. **Hand off to YouTube / Instagram:**
   - The text is copied to the clipboard and the video opens in that app's share screen, where *you* paste and post.
   - This is a **handoff, not an upload**. If the app isn't installed, the system share sheet opens.
5. Check that the original video file is unchanged (same size and date).
6. **Error cases to try:**
   - A non-video file: "unsupported file".
   - A video with no audio track: a clear "no audio" message.
   - A very long video: several chunks.
   - Airplane mode.

## 8. Airplane-mode testing: what needs the cloud

| Works fully offline | Needs internet (OpenRouter) |
|---|---|
| Framing coach: ML Kit models are bundled; TTS needs offline voice data | Writing/regenerating teleprompter scripts (**no offline fallback**, by design) |
| Teleprompter preview, recording, pause/resume/stop buttons | Transcription |
| Voice commands, **if** the phone's on-device speech recognition supports your language (NETHRA switches the recogniser to offline mode after a network error) | Cloud publish kit |
| Speech-following scroll (same condition) | |
| Clean-copy export (Media3, on the phone) | |
| Picking a video and extracting its audio | |
| Publish kit **with local Gemma**, if a model is installed | |
| Handoff to YouTube/Instagram (posting there needs internet) | |

Test: enable airplane mode → each cloud action should show an offline message. Your brief, script, transcript
and kit should still be there. Turn airplane mode off and retry. A draft is saved on disk, so it also survives an
app restart.

## 9. Troubleshooting

| Problem | What to do |
|---|---|
| **Build:** `SDK location not found` | Open the project once in Android Studio, or add `sdk.dir=` to `local.properties` |
| **Build:** `Unsupported class file major version` / JDK errors | Use JDK 17+ (Gradle JDK setting, or `$env:JAVA_HOME`) |
| **Build:** random I/O errors, "disk full", or corrupt caches | Free disk space (≥ 5 GB), then `.\gradlew.bat --stop` and `.\scripts\build-debug.ps1 -Clean` |
| **Build:** dependency download timeouts | Retry; `gradle.properties` already raises timeouts and retries |
| **Build:** "Failed to install the following SDK components: platforms;android-36" | Install API 36 in SDK Manager and accept the licences |
| **Install:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall com.nethra.app`, then install again |
| **Install:** `INSTALL_FAILED_USER_RESTRICTED` | Enable *Install via USB* in Developer options (vivo/iQOO), and accept the on-phone prompt |
| **Install:** device `unauthorized` | Unlock the phone, accept the USB debugging prompt, and re-plug |
| **Model:** home says *Not installed* after pushing | Check the file ends in `.litertlm` and is in `/sdcard/Android/data/com.nethra.app/files/model/`. Restart the app |
| **Model:** load fails / app killed during load | Close other apps. Try a smaller (E2B) file. If GPU fails it retries on CPU automatically. Make sure the file matches LiteRT-LM 0.17.x |
| **Camera:** black preview / "camera in use" | Close other camera apps, then leave and re-open the screen. Check the camera permission in App info |
| **Camera:** framing says "not confident" constantly | Improve lighting, show the whole upper body, avoid strong backlight |
| **Speech:** voice pill says off / never hears "Nethra" | Allow the microphone. Update the *Speech Services by Google* app and set it as the default recogniser. Download the offline language pack. Speak "Neh-thra" clearly |
| **Speech:** voice stops while recording | Expected on some phones (mic arbitration, section 10). Use the on-screen buttons, or enable *Voice commands while recording* |
| **Speech:** beeps each time listening restarts | Enable *Silence recogniser beeps* (see its side-effects in section 10) |
| **Script:** "No cloud key" / auth error | Check `OPENROUTER_API_KEY` in `local.properties`, then rebuild and reinstall |
| **Script:** "out of credit" / rate limited | Add credit on openrouter.ai / wait and retry |
| **Script / transcription:** "model not found" | The ID in `AiConfig.kt` isn't available on OpenRouter. Change it and rebuild |
| **Transcription:** "unsupported file" / "no audio" | The file isn't a video Android can decode, or it has no audio track |
| **Transcription:** some parts failed | Tap **Retry N failed**. Only the failed chunks are re-sent |
| **Export:** "Clean copy failed" | The original is always kept in Movies/NETHRA. Nothing is claimed removed unless the export succeeded. Retry with a shorter recording; report the message |
| **Export:** no clean copy was made | Only commands *spoken* while recording (pause/stop) are cut. Button presses need no cut. A recording that is almost all commands isn't exported |

Logs: `adb logcat | findstr /i nethra`. The API key is never logged.

## 10. Limitations that need physical-device testing

Nothing below has been verified on a phone yet. Compilation and the PC unit tests do not prove these.

1. **Microphone arbitration while recording.**
   - Android normally gives the mic to one client. While CameraX records, the speech recogniser may go deaf.
   - With *Voice commands while recording* on, the recorder uses the `MIC` source instead of `CAMCORDER`
     to give the recogniser a chance. Whether both can share the mic depends on the iQOO 15 firmware.
   - If commands aren't heard after about 15 s, the app says so, and every button keeps working.
   - The `MIC` source may also sound slightly different from `CAMCORDER`.
2. **Precision of the clean cut.**
   - Command boundaries come from recogniser timing plus a search for the pause in the recording's own loudness.
     This is unit-tested with synthetic audio only.
   - In real rooms, noise or talking straight into "Nethra" may leave a fragment or trim a syllable before the command.
     A 150 ms silence margin is kept.
   - Cuts fall on the recording's timeline and are not frame-exact on video keyframes.
     Media3 re-encodes, so the export takes a while and the result's quality/bitrate should be checked.
3. **Recogniser beeps.** Android restarts recognition sessions repeatedly and many recognisers beep each time.
   *Silence recogniser beeps* mutes the notification, system **and media** streams while listening.
   That also silences other audio, and it restores volumes when you leave the screen.
   Whether it removes the beep depends on which stream your recogniser uses.
4. **Speech following** needs real-world tuning: accents, speaking speed, and partial-result behaviour of the iQOO's recogniser.
5. **Landscape framing.**
   - The app is portrait-locked; controls counter-rotate.
   - Directions are remapped for the phone's rotation, and the mapping is unit-tested. The sensor/preview orientation on the actual device
     still needs checking in all four rotations, with both the default and a custom box.
6. **Detection quality and speed.** ML Kit pose (accurate model) plus face detection. Frame rate, heat and
   accuracy at distance and in low light need measuring on the SM8850.
7. **Local Gemma.**
   - Loading a specific `.litertlm` file on the Adreno GPU (it falls back to CPU) and memory use have not been tested.
   - The optional OpenCL native libraries are declared `required=false`.
8. **Cloud models.**
   - The model IDs in `AiConfig` must exist on your OpenRouter account.
   - The quality of the web research, word-count accuracy and non-English transcription need real requests. None were made during this build (no key was set).
9. **Handoff.**
   - The YouTube and Instagram apps decide which share targets they accept.
   - The text is on the clipboard, not pre-filled. Instagram's share flow may differ between app versions.
10. **Interruptions.** Incoming calls, the screen turning off mid-recording, low storage, and battery-saver camera limits
    are handled in code, but were not tested.
11. **Key protection.** The masked key in a debug APK is recoverable (section 4).

## 11. iOS camera UI, precision, voice commands and adaptive teleprompter

> The pure logic below is unit-tested on the PC (framing precision, voice-command parsing, pace
> following). The Compose UI was written against the project's existing libraries but **was not compiled
> in the environment that made it** — build it in Android Studio first and report any compile error.

### Camera modes (iOS-style)

- The app now opens straight into **CAMERA** (like iOS). Swipe left/right on the bottom bar (or the
  preview, except in AI framing where the preview draws the box) or tap the strip:
  **CAMERA → TELEPROMPTER → AI FRAMING → TRANSCRIPT**. The selected mode is yellow and centred.
- Black top/bottom bars, white-ring shutter (red disc → rounded square while recording), red
  `00:00:12` time-code, Pause/Resume on the left while recording.
- **CAMERA**: plain video, rear by default, **flip** bottom-right, *Videos* bottom-left opens the gallery.
- **TELEPROMPTER**: **flip** bottom-right switches front ↔ rear between takes.
- The top-left grid button opens the old home screen (cloud key, Gemma model status).

### Voice commands (all camera modes)

*"Nethra, start recording"* → spoken **3-2-1** countdown, then records. *"Nethra, pause"*,
*"Nethra, resume"*, *"Nethra, stop"*. Stop/pause/resume act on the recogniser's partial result
(about a second faster). Replies ("Paused.", "Resuming.", "Saved.") are only spoken while the camera
isn't capturing, so NETHRA's voice stays out of your takes. Turn replies off with the speaker icon
(camera/framing) or *Spoken replies* (teleprompter settings).

### Microphone sharing while recording (Android 13+)

Android lets only one app use the microphone at a time, so while NETHRA records, Google's recogniser used to
be refused the mic ("Speech Recognition and Synthesis from Google cannot record now…"): voice commands and
the adaptive teleprompter went deaf mid-take. NETHRA now captures the microphone itself and streams that audio
to the recogniser (`speech/MicFeed.kt`, `RecognizerIntent.EXTRA_AUDIO_SOURCE`), trying the on-device recogniser
first. It also runs its own voice-activity detector on that audio, so the script keeps gliding at your pace
while you talk even if the recogniser lags. If a phone can't do this, NETHRA falls back to the old way and
**pauses listening during takes** instead of triggering that warning repeatedly (the buttons always work).
Check `adb logcat -s SpeechListener MicFeed` to see which mode is in use.

### AI framing: locked during recording

With **Lock framing while recording** (on by default, framing settings), the moment recording starts NETHRA
stops every position prompt — nothing is spoken and no directions are shown — and pauses detection until the
take ends ("FRAMING LOCKED"). Spoken prompts never play during a take in any case.

### AI framing precision — test and evaluate

All thresholds live in `framing/FramingCoach.kt` → `FramingTolerance` (presets `RELAXED`, `STANDARD`,
`PRECISE`; choose in the framing settings ⚙). Every frame produces `FramingMetrics`:

| Value | Meaning |
|---|---|
| `score` | 0–100 precision (60% position, 40% size). 100 = perfectly centred and sized |
| `iou` | overlap of person and target box, 0–1 |
| `dx`, `dy` | centre / top error in frame units; `dxRel`, `dyRel` = error ÷ tolerance (≤ 1 is inside) |
| `scale` | person size ÷ box size |
| `coverage` | fraction of the person inside the box |

- **On the phone:** framing settings → *Show precision numbers* (live HUD), *This session* stats and
  *Copy report*. Every second a line is logged: `adb logcat -s NethraFraming`.
- **On the PC:** `.\gradlew.bat testDebugUnitTest --tests "com.nethra.app.framing.*"`. `FramingPrecisionTest`
  runs a labelled grid (must be 100%) and a noisy walk-in simulation, and prints a report (accuracy,
  mean score, IoU, flips/min, prompts/min, time to "in frame"). Use `FramingEvaluator` with your own
  labelled detections to evaluate changes.
- Stability: box smoothing (EMA), 3-frame confirmation and hysteresis (the "in frame" zone widens ×1.35
  once reached). In the edge-of-box jitter test this cuts guidance flips from ~74/min to ~8/min.
- Speech: directions scale with distance ("Just a tiny bit to your right", "Two steps to your left"),
  repeats are re-worded, and it uses the best installed offline TTS voice with audio ducking.

### Teleprompter behaviour (update)

- **Scrolls only while recording.** Before you tap record (or say "Nethra, start recording") the script stays
  where it is, whatever is said; drag it or use ▲ ▼ to choose where to start.
- **Pace from your voice.** It follows the words the recogniser places; between/without them it glides at your
  speaking rate measured from the rhythm of your voice (`speech/SpeechRate.kt`, syllables → words per minute),
  and stops about half a second after you stop talking.
- **"Nethra" wake word:** NETHRA now ends each listening session itself when you pause (so a bare "Nethra" gets
  its final result), and if the recogniser returns nothing while the mic clearly hears you, it falls back to the
  classic mic automatically. Settings → Voice shows the **Voice engine** in use, and **Share NETHRA's mic with the
  recogniser** can be switched off by hand.
- **Floating script panel:** drag ✥ to move it anywhere on the preview, drag ◢ to resize; the text turns with the
  phone in landscape; portrait and landscape remember separate layouts; text size is in settings.

### Adaptive teleprompter

Settings ⚙ → **Scrolling**:

- **Follow my pace** (default): the script glides continuously at your measured speaking speed
  (the yellow WPM chip on the script), eases onto each recognised word and stops within ~1 s of silence.
- **Fixed WPM**: steady speed from the slider (60–260 WPM; presets 110/150/190) while recording.
- The WPM and mode are remembered. Drag the script or use ▲ ▼ to correct it in either mode.

## 12. Direct YouTube upload (title, description and tags filled in)

The YouTube Android app ignores titles, descriptions and tags passed from other apps, so
**Transcript → YouTube → Upload to YouTube** uploads the video itself through the official
YouTube Data API v3, sending the kit's title, description (+ hashtags) and tags. The old
"Open in the YouTube app" handoff is still there as a fallback.

### One-time Google Cloud setup (about 10 minutes)

1. Go to <https://console.cloud.google.com/> → create a project (e.g. *NETHRA*).
2. **APIs & Services → Library** → enable **YouTube Data API v3**.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform): User type **External**,
   app name *NETHRA*, your email. Under **Data access / Scopes** add
   `https://www.googleapis.com/auth/youtube.upload`. Under **Audience → Test users** add the Google
   account that owns your YouTube channel. Leave it in **Testing**.
4. Get the SHA-1 of the key that signs your build. For debug builds (PowerShell):
   ```powershell
   keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```
   (Android Studio's bundled `jbr\bin\keytool.exe` works if `keytool` isn't on PATH.)
5. **APIs & Services → Credentials → Create credentials → OAuth client ID** → type **Android**,
   package name `com.nethra.app`, paste the SHA-1. Nothing needs to be copied into the app —
   Google matches the app by package name + signature. A release build signed with a different
   key needs its own Android OAuth client.

### Using it

Pick a video → Transcribe → Create kit → edit the text → choose **Private / Unlisted / Public** →
**Upload to YouTube**. The first time, Google asks you to allow NETHRA to *upload videos*; only that
permission is requested (NETHRA can't read, edit or delete anything on the channel). Progress is shown
in MB; **Pause upload** stops it and **Continue upload** resumes from where it stopped. When it's
done you get the link and an **Open in YouTube Studio** button.

### Limits you should know

- **Private until audited.** YouTube locks videos uploaded through API projects that haven't passed
  YouTube's API compliance audit to **Private**, whatever privacy you choose. Switch them to Public in
  YouTube Studio, or request an audit for the project (YouTube API Services audit form) to lift it.
- **Quota.** Each upload uses a large share of the project's free daily YouTube API quota (10,000 units),
  so only a handful of uploads per day are possible on the default quota. The app says so when it's used up.
- The Google account must already have a YouTube channel.
- The upload runs while the app is open; leaving NETHRA for a long time may pause it (tap Continue).
- Errors are explained in the app: *sign-in isn't set up* = SHA-1/package mismatch in step 5;
  *API isn't enabled* = step 2; *not a test user* = step 3.

## 13. Voice accuracy and automatic captions

### Voice commands and briefs
- **Commands** are matched against every alternative the recogniser offers, tolerate near-misses
  ("resumed", "recordin"), and the unambiguous phrases **"start / stop / pause / resume recording"** work even
  when "Nethra" itself is misheard.
- **Cloud dictation for briefs:** say **"Nethra"** (or "Nethra, write a script") → NETHRA answers "Yes?" →
  speak your brief (or a command) and pause. The clip is recorded with NETHRA's own mic and transcribed by the
  cloud transcription model (`ai/Dictation.kt`), which is far more accurate than the phone recogniser. A level
  meter shows it hears you; ✓ finishes early, ✕ cancels. Offline, the old on-phone path is used.

### Automatic captions (`captions/`)
After every recording (all modes), when **Auto captions** is on (CC button in Camera / AI Framing, or
Teleprompter settings) and there is internet:
1. the audio is extracted on the phone and transcribed in ~40 s parts **with timestamps**;
2. timings are snapped to the real speech in the audio, split into 1–4-word cards with per-word timing
   (`CaptionBuilder`, unit-tested);
3. captions are burned in with Media3 (`CaptionOverlay`: bold white on a dark pill, the spoken word in yellow,
   lower-middle of the frame) → `Movies/NETHRA/NETHRA_…_captions.mp4`, plus `Documents/NETHRA/…_captions.srt`.
Teleprompter takes with spoken commands are captioned from the clean copy. The original is never modified.
Progress shows as a card on the camera screen; recordings are processed one at a time.
