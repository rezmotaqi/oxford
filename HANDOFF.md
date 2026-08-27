# Hermes Client Android: Engineering Handoff

## Purpose

This project is a native Android client for an existing Hermes Agent server. Android provides connection setup, session browsing, chat history, streaming chat, and tool-status UI; it does not implement the Hermes agent, model orchestration, memory, tools, terminal, or reasoning loop.

The project is a single Android application module using Kotlin, Jetpack Compose/Material 3, MVVM, Hilt, Coroutines/Flow, Retrofit, OkHttp SSE, kotlinx.serialization, DataStore, and Android Keystore.

```text
Compose UI -> ViewModels -> domain repository interfaces
    -> Hermes repository implementations -> REST/SSE adapters -> Hermes API
```

Transport DTOs and raw SSE data remain in the data layer. ViewModels know only domain repositories and events. Hermes remains the source of truth; there is no Room database or production mock agent.

## Implemented V1

- First-launch connection and settings flow with URL validation, health/authentication checks, capability detection, secure persistence, and startup routing.
- User-facing handling for invalid URLs, unauthorized requests, unreachable servers, timeouts, server errors, and unsupported Hermes capabilities.
- Session list, refresh/retry, empty/loading/error states, session creation, and navigation to existing or newly created sessions.
- Session metadata and message-history loading from Hermes.
- Optimistic user-message insertion and one-at-a-time send protection.
- Cancellation-aware SSE streaming with progressive updates to one assistant message rather than one item per delta.
- Tool started/completed/failed activity represented as compact typed chat items.
- Reconciliation of streamed content with authoritative completion messages.
- Lifecycle-aware StateFlow collection, immutable UI state, keyboard/safe-area handling, and controlled auto-scroll that does not continually override a user reading older messages.
- Material 3 light/dark theme and simple Connection, Sessions, and Chat navigation.
- Session-scoped `/v1/runs` submission with explicit stop control and partial-response preservation.
- Typed approval request/response events with allow-once, session, permanent, and deny controls.

## Hermes API Contract

The implementation was based on the current Hermes Agent API server source at development time, not invented DTO fields.

| Method | Endpoint | Use |
|---|---|---|
| `GET` | `/health` | Reachability and Hermes identity |
| `GET` | `/v1/capabilities` | Detect session, stream, run, and approval support |
| `GET` | `/api/sessions` | List sessions |
| `POST` | `/api/sessions` | Create a session |
| `GET` | `/api/sessions/{id}` | Load session metadata |
| `GET` | `/api/sessions/{id}/messages` | Load message history |
| `POST` | `/v1/runs` | Start a session-scoped controllable run |
| `GET` | `/v1/runs/{id}/events` | Receive run SSE events |
| `POST` | `/v1/runs/{id}/approval` | Resolve a pending approval |
| `POST` | `/v1/runs/{id}/stop` | Stop a run |

Authenticated calls use `Authorization: Bearer <API_SERVER_KEY>`. Capability JSON is mapped to `HermesCapabilities`; UI code never inspects the raw capability payload.

Known run event names are carried in each data-only SSE JSON payload:

- `message.delta`
- `tool.started`
- `tool.completed`
- `tool.failed`
- `approval.request`
- `approval.responded`
- `run.completed`
- `run.cancelled`
- `run.failed`

The parser translates supported wire events into typed `ChatEvent` values. Unknown or malformed additions are ignored rather than leaked to UI. Coroutine cancellation cancels the underlying OkHttp event source.

## Important Boundaries and Files

- `app/src/main/java/com/example/hermesclient/app/`: application, startup routing, activity, navigation, and Hilt wiring.
- `app/src/main/java/com/example/hermesclient/app/di/HermesModule.kt`: dependency construction and interface bindings.
- `app/src/main/java/com/example/hermesclient/core/network/NetworkSupport.kt`: URL normalization, endpoint construction, bearer authentication, runtime config, and transport error mapping.
- `app/src/main/java/com/example/hermesclient/core/security/`: API-key storage contract and Android Keystore AES-GCM implementation.
- `app/src/main/java/com/example/hermesclient/data/preferences/`: DataStore connection metadata and connection repository.
- `app/src/main/java/com/example/hermesclient/data/remote/`: Retrofit API/DTOs, explicit DTO mappers, OkHttp SSE source, and event parser.
- `app/src/main/java/com/example/hermesclient/data/repository/`: Hermes backend adapter plus session/chat repository implementations.
- `app/src/main/java/com/example/hermesclient/domain/`: UI-independent models, errors, chat events, and repository interfaces.
- `app/src/main/java/com/example/hermesclient/feature/connection/`: connection ViewModel and Compose screen.
- `app/src/main/java/com/example/hermesclient/feature/sessions/`: session-list ViewModel and Compose screen.
- `app/src/main/java/com/example/hermesclient/feature/chat/`: chat UI models, streaming state machine, and Compose screen.
- `app/src/test/`: mapper, parser, error, URL, SSE network, and ChatViewModel tests.
- `app/src/androidTest/`: focused Connection screen Compose test.

## Security and Network Policy

- The API key is encrypted with AES-GCM using a key generated and retained by Android Keystore. Only ciphertext and IV are stored in app-private preferences.
- The base URL is normalized and stored separately in DataStore.
- Credentials are not hardcoded, logged, included in errors, or shown again after persistence.
- Bearer authentication is centralized in one interceptor.
- Release builds reject cleartext traffic and require HTTPS.
- Debug builds permit local/emulator HTTP targets such as `http://10.0.2.2:8642`; cleartext is not globally enabled for production.
- The local development API key is stored in ignored file `.hermes-api-key` with mode `600`; OpenAI OAuth tokens remain in Hermes/Codex credential stores outside the Android project.
- Production deployment should terminate HTTPS at Caddy/Nginx and keep the terminal-capable Hermes API private. Do not expose an unprotected Hermes instance directly to the Internet.

## Validation Results

Validated successfully:

- `testDebugUnitTest`: 27 JVM unit/network tests pass.
- `lintDebug`: zero findings.
- `assembleDebug`: pass.
- `assembleRelease`: pass; output is unsigned.
- `assembleDebugAndroidTest`: pass.

`connectedDebugAndroidTest` was attempted. One run lost the software-emulated device and another instrumentation process was killed by Android's startup watchdog before a test assertion. This is a no-KVM emulator limitation; the instrumentation APK builds successfully.

Live integration was verified against Hermes Agent `0.16.0` on `127.0.0.1:8642`: authenticated capabilities advertised all required run/stop/approval features, session and run creation succeeded, the data-only event stream delivered message deltas and completion, and the live run returned the requested output through Hermes and OpenAI Codex. Earlier Android rendering and secure restart checks also succeeded.

Hermes inference is configured with `openai-codex / gpt-5.6-sol` as primary and `ollama-launch / glm-5.2:cloud` as the fallback. The OpenAI subscription stream completed successfully. The Ollama fallback remains configured but is currently rate-limited by its weekly quota.

Debug APK:

```text
/home/rez/Projects/oxford/app/build/outputs/apk/debug/app-debug.apk
```

## Emulator

- AVD name: `hermes_client`
- Device profile: Pixel 8a
- System image: Android 16 / API 36, Google APIs, x86_64
- AVD storage: `/home/rez/Projects/oxford/.android-avd`
- Expected serial when running: `emulator-5554`
- Host `/dev/kvm` is unavailable, so the AVD must use slow software CPU acceleration (`-accel off`).

The AVD files and debug configuration remain in the workspace, but no `hermes-emulator.service` or `hermes-api.service` user unit is currently installed. The live validation used `.hermes-server-launch.sh` temporarily and stopped it afterward. When the emulator and gateway are running, use `adb reverse tcp:8642 tcp:8642`; the app stores `http://127.0.0.1:8642` for this debug-only connection. Expect long boot, input, screenshot, and instrumentation delays without KVM.

## Git Checkpoint

The previously empty, read-only `.git` placeholder was made writable and initialized after validation. Generated SDK, emulator, build, local-property, launcher, and credential files remain ignored.

## Required Product Work

1. Run `connectedDebugAndroidTest` on hardware with KVM acceleration or a physical Android device and investigate only if an assertion then fails.
2. Add repeatable live tests for unauthorized access, capability rejection, mid-stream disconnect, reconnect, and process recreation; the happy path is now verified manually.
3. Configure production application identity, release signing, endpoint/reverse-proxy deployment, and secure operational key rotation before distribution.
4. Verify behavior against the exact Hermes version selected for production and update DTO/parser fixtures if its API contract differs.

## Current Product Limitations

- One Hermes server/profile and one in-flight turn.
- Text-only messages; no images, files, voice, or text-to-speech.
- No run steering, reconnect-after-process-death, skills/model/tool configuration, memory editing, or session forking.
- No local session/message cache, offline sync, background work, push notifications, or multiple accounts.
- History uses the current server page limit and has no user-facing pagination/search.

## Optional Roadmap

1. Add run steering and event-stream reconnection across process recreation.
2. Add richer message content and attachment upload/rendering behind the isolated message renderer.
3. Add named Hermes profiles/servers by expanding the existing connection configuration boundary.
4. Add Room only when offline cache, search, drafts, or local metadata becomes a concrete requirement.
5. Add background runs, notifications, voice, and desktop/web clients as separate product increments.
