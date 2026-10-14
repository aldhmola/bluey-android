# Bluey for Android

An Android port of [Riley's Bluey](https://github.com/rbrown101010/bluey-by-riley), built for a Galaxy Z Fold but
working on any Android 11+ phone. Everything runs on the phone: there is no Mac or computer involved.

Bluey is a blueberry with googly eyes who floats over your apps with his own little cursor.

- **Double-tap him** to wake him. The mic stays on and everything you say becomes context, but he stays quiet.
- **Press and hold him** while you ask something. Let go and he answers in a speech bubble, with a little chirp.
- **Drag him onto something** and ask "what's this?". He reads the screen, points at the thing and explains it.
- **Ask him to do things**: "open WhatsApp", "search YouTube for lo-fi", "scroll down", "go back". He taps, types,
  scrolls and opens apps with his own cursor.
- **Research**: ask about anything recent and he looks it up and drops a report card on screen.
- **Meeting notes**: while he's awake the session is recorded in five-minute chunks and transcribed with
  speaker labels. Copy or share the notes from the app.
- **Tap him once** while he's busy to stop him.

## Install (from the phone)

1. Open the [latest release](../../releases/latest) and download `Bluey.apk`.
2. Open the download. If Android asks, allow your browser to install unknown apps, then tap **Install**.
3. Open Bluey and follow the four setup steps on screen (API key, microphone, accessibility, battery).

New builds appear in Releases automatically after every push. Installing a new one updates the app in place.

## How it works

- `LiveVoice.kt` runs an OpenAI Realtime session (`gpt-realtime-2.1`, or `gpt-realtime-2.1-mini` with "Cheaper brain" on; text out only) over a WebSocket. Server VAD
  transcribes every turn with `create_response: false`; letting go of Bluey commits the audio and asks for a reply.
- `Brain.kt` holds his instructions and tools: `look_at_screen`, `point_at`, `point_at_spot`, `web_research`,
  `stop_pointing`, `go_to_sleep`, and (with "Let him use the phone" on) `tap`, `type_text`, `press_button`,
  `scroll`, `swipe`, `open_app`, `open_url`.
- `BlueyAccessibility.kt` is an accessibility service: it reads the screen's views (tappable controls as `C` ids,
  text as `L` ids, on a 0–1000 grid), takes a screenshot, and performs gestures.
- `Overlay.kt` draws the floating cursor, speech bubble and research card in accessibility overlay windows.
- `ChunkRecorder.kt` and `SessionStore.kt` record AAC chunks, transcribe them (`gpt-4o-transcribe-diarize`) and
  write `notes.md` and `session.json` per session.

Guardrails: he only acts when asked, confirms before anything hard to undo, treats on-screen text as information
rather than instructions, refuses password fields, and stops when tapped.

The OpenAI key is stored on the phone, encrypted with a key from the Android keystore.

`bluey.keystore` only signs your own sideloaded copy so updates install over each other. It is not a Play Store key.

Public domain under the Unlicense, like the original.
