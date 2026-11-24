# YouTube courses and the lesson tutor

## Add an online course

Open the course library's overflow menu and choose **إضافة كورس من YouTube**. Paste an HTTPS playlist URL, preview the entries, then choose **Add / refresh course**. The same playlist refreshes its existing course rather than creating another copy. Use **Refresh** on a YouTube course to update its lesson list. Removed/unavailable entries retain their notes and progress.

Playlist metadata requires a YouTube Data API project/key. The developer can configure `ILEARN_YOUTUBE_API_KEY` in the build environment; a session-only key can also be entered on the import screen. This credential is independent of AI provider credentials. Enable YouTube Data API v3, apply API/application restrictions appropriate to the signed app, and verify their enforcement before distributing a build. Android identity headers do not replace quota monitoring or make a public-client key secret.

Playback uses the official embedded player. Internet access, YouTube availability, and permission to embed the individual video are required. The external YouTube action is available when an entry cannot play in the app. Offline downloads are outside this release's scope.

## Configure an AI provider

Open **AI providers • إعدادات المعلّم** from the library menu or **API settings** in the tutor. Add a profile with the API format, HTTPS base URL, model ID, and API key. Use a base URL, not a full request path such as `/chat/completions`.

| API format | Typical base URL | Request path supplied by iLearn |
| --- | --- | --- |
| OpenAI-compatible Chat Completions | `https://api.openai.com/v1` or your compatible service's base URL | `/chat/completions` |
| OpenAI Responses | `https://api.openai.com/v1` | `/responses` |
| Anthropic Messages | `https://api.anthropic.com/v1` | `/messages` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | Model-specific generate/stream endpoint |

Enter a model available to your API account. A chatbot subscription is not an API key. Custom services must implement the chosen format; protocols differ, so untested endpoints are not guaranteed compatible. Disable streaming for endpoints that do not support Server-Sent Events. For Chat models that require it, enable the `max_completion_tokens` option. Adjust input/output limits to the selected model; the character budget is an application estimate, not an exact token count.

**Test** sends a small request and can incur an API charge. **Save & use** selects the profile. Keys are session-only by default and must be entered again after app process restart. **Remember key encrypted on this device** stores an encrypted credential protected by Android Keystore. Direct mobile API use still exposes the credential to the app process during a request; review provider guidance before using valuable account credentials. No shared developer AI key is required.

Verify the endpoint before saving. Changing the destination clears the entered key; editing a saved profile requires key re-entry. Delete a profile to remove its stored credential. Keys and tutor history are excluded from normal course backups and Android backup/transfer through no-backup storage.

## Ask about a lesson

Open a lesson and choose **AI tutor • المعلّم**. Select English, Arabic, or bilingual answers and ask a question, request an explanation at the current time, or request a practice question. Questions, selected transcript passages, and recent conversation are sent directly to the chosen API endpoint. Provider charges and data policies apply.

Local VTT/SRT subtitle files provide lesson evidence. For any lesson, including a YouTube lesson, **Attach transcript** accepts UTF-8 TXT, SRT, or VTT up to 1 MB. YouTube's visible captions are not automatically available as app-readable lesson text. A TXT file provides passage evidence without timestamp links.

When sources are missing, lesson questions remain unavailable until a transcript is attached. **General subject help** is a separate explicit mode and does not claim to understand the lesson. No provider is silently substituted. Provider-native video analysis is not enabled without separate live verification.

Responses use source references where evidence is available. Tap a timed reference to seek to that passage. A reference resolving to a passage does not prove that the explanation is mathematically correct; verify important answers against the lesson. Cancellation stops the app request, but the provider may charge for work already performed. Retrying is explicit.

## Backups and testing

Version 2 JSON backups include YouTube course/lesson identities, progress, and notes; they contain no video files or API credentials. Version 1 local backups are still readable. Import the original local course archive before restoring its notes/progress on another installation. Tutor transcripts/history are private to this installation and are excluded from course backups.

From `android/`, with JDK 17 and Android SDK configured:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest -PvalidationBuild=true
```

The connected-test command needs an unlocked Android device/emulator and internet for the live YouTube case. `-PvalidationBuild=true` builds **iLearn Validation** with application ID `com.ilearn.app.validation`, preserving an installed `com.ilearn.app` library. Instrumentation database/profile fixtures are separately isolated too. Without that property, normal builds keep the original application ID.

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`; the preserved validation artifact is `artifacts/iLearn-validation.apk`. Debug signing is for testing; a public release requires verified signing, device/provider checks, current distribution requirements, and the remaining gates in [todo.md](todo.md). See [the evidence report](evidence/implementation.md) for what has actually been checked.
