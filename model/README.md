# Optional on-device Gemma model

This folder holds **instructions only**. No model weights are part of the
project or the APK, and nothing in this folder is packaged into the app.

## What it is used for

Local Gemma is optional. NETHRA only offers it in one place: writing the
**publishing kit** (YouTube title/description/tags, Instagram caption/hashtags)
when the cloud model (`AiConfig.PUBLISH_KIT_MODEL`) cannot be reached. It is
**never** used for:

- teleprompter scripts. These are cloud-only by design, with no silent fallback.
- transcription. That needs an audio model; see `AiConfig.TRANSCRIPTION_MODEL`.

Everything else in the app works without it.

## Format

- One **LiteRT-LM** file with the extension **`.litertlm`**. The extension is set by
  `AiConfig.LOCAL_MODEL_EXTENSION`.
- It must be an instruction-tuned Gemma build made for Android/LiteRT-LM that
  runs on the `com.google.ai.edge.litertlm:litertlm-android` version in
  `gradle/libs.versions.toml` (0.17.1 at the time of writing).
- Get one from the Hugging Face **`litert-community`** organisation, for example a
  Gemma E2B / E4B "-it" `.litertlm` file. You may need to accept the Gemma licence
  on Hugging Face first.
- Size guide: E2B-class files are about 3 GB, E4B-class about 4–5 GB. The iQOO 15
  has enough RAM for either, but close other apps before the first load.
- `.task`, `.bin`, `.gguf` or `.tflite` files are **not** accepted.

If several `.litertlm` files are present, the largest one is used.

## Where the app looks, in order

1. `/sdcard/Android/data/com.nethra.app/files/model/`. This is the app's own folder,
   used by both the in-app import and `adb push`.
2. `/data/local/tmp/nethra/`. This is a developer fallback. Some Android versions do not
   let apps read this folder, so prefer option 1.

## Three ways to install it

**A. In the app (easiest).** Copy the `.litertlm` file to the phone's Downloads, then
in NETHRA go to Transcript & publish kit → Create kit. When the cloud is unreachable,
the "Use on-device Gemma instead?" panel offers **Import Gemma model**. Pick the file.
NETHRA copies it into its own folder with a progress bar. This takes a few minutes for a
multi-GB file and needs that much free space on the phone.

**B. PowerShell + ADB.** From the project root:

```powershell
.\scripts\push-model.ps1 -ModelPath "C:\path\to\gemma-model.litertlm"
```

**C. ADB by hand.** Open the app once first so its folder exists, then:

```powershell
adb push "C:\path\to\gemma-model.litertlm" /sdcard/Android/data/com.nethra.app/files/model/
```

## Loading

The model loads the first time it is needed, not at app start. NETHRA tries the
GPU backend first (`AiConfig.LOCAL_PREFER_GPU`) and falls back to CPU. The home
screen shows the state: Not installed, Installed, Loading, Ready on GPU/CPU, or the
failure reason. A failed load never affects the camera, teleprompter or
transcription features.

Uninstalling the app deletes `/sdcard/Android/data/com.nethra.app/`, including the
model.
