# Mehak WhatsApp — Android app

Native Android shell (Kotlin) that hosts the WhatsApp-clone UI in a WebView and
bridges the browser speech APIs to Android's own engines:

- `window.SpeechRecognition` → `android.speech.SpeechRecognizer`
- `window.speechSynthesis` → `android.speech.tts.TextToSpeech`

The web UI itself lives in `app/src/main/assets/index.html` and is unchanged
from the browser build, so every feature (chats, status, calls, groups, voice
notes, photos, OpenRouter settings) behaves identically.

## Build

GitHub Actions builds a debug APK on every push to `main`:
`.github/workflows/build-apk.yml` runs the unit tests first, then
`assembleDebug`, then publishes the APK as a release asset.

Locally:

```
gradle testDebugUnitTest
gradle assembleDebug
```

## Speech error handling

`SpeechErrorPolicy` maps every `SpeechRecognizer` error code to a readable
message and a recovery action, with a 300/600/1200 ms retry backoff (max 3
tries). `MainActivity` creates the recognizer on the main thread, destroys it
after every session, cancels before retrying on `ERROR_RECOGNIZER_BUSY`, and
recreates it on `ERROR_CLIENT`.
