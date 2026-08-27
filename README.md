# Hermes Client for Android

Hermes Client is a native Android UI for an existing [Hermes Agent](https://github.com/NousResearch/hermes-agent) server. The app does not run an agent, model, memory system, terminal, browser, or tool loop on the device. Hermes remains the source of truth for conversations and messages.

## Architecture

The V1 project uses one Android application module with package boundaries that can be extracted later if the product grows.

```text
┌─────────────────┐
│ Compose UI      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ViewModels      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Domain          │
│ Repositories    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Data Layer      │
├─────────────────┤
│ REST            │
│ SSE             │
│ Event Parser    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Hermes API      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Hermes Harness  │
└─────────────────┘
```

Compose screens observe immutable `StateFlow` state from Hilt-injected ViewModels. ViewModels depend only on domain repository interfaces. The data layer adapts Retrofit responses and raw OkHttp SSE frames into domain models and typed `ChatEvent` values.

## Hermes Communication

The current implementation uses the contract in the Hermes Agent API server source:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/health` | Reachability and Hermes identity |
| `GET` | `/v1/capabilities` | V1 compatibility detection |
| `GET` | `/api/sessions` | Conversation list |
| `POST` | `/api/sessions` | Create a conversation |
| `GET` | `/api/sessions/{id}` | Conversation metadata |
| `GET` | `/api/sessions/{id}/messages` | Message history |
| `POST` | `/v1/runs` | Start a session-scoped, controllable agent run |
| `GET` | `/v1/runs/{id}/events` | Stream message, tool, approval, and lifecycle events |
| `POST` | `/v1/runs/{id}/approval` | Allow or deny a pending operation |
| `POST` | `/v1/runs/{id}/stop` | Stop an active run |

Authenticated requests use `Authorization: Bearer <API_SERVER_KEY>`. Chat starts a run with the current session ID and visible conversation history, then maps data-only run SSE frames such as `message.delta`, `tool.started`, `tool.completed`, `approval.request`, `approval.responded`, `run.completed`, `run.cancelled`, and `run.failed`. Unknown and malformed event types are ignored at the parser boundary so protocol additions do not crash the UI.

## Required Hermes Configuration

Install and configure Hermes Agent using its upstream documentation. Configure a strong `API_SERVER_KEY`, enable the `api_server` gateway platform, and start the gateway:

```bash
export API_SERVER_KEY="$(openssl rand -hex 32)"
hermes gateway start
```

The API server defaults to port `8642`; confirm your Hermes profile and gateway configuration rather than assuming the port. Verify locally before configuring Android:

```bash
curl http://127.0.0.1:8642/health
curl -H "Authorization: Bearer $API_SERVER_KEY" \
  http://127.0.0.1:8642/v1/capabilities
```

For production, terminate HTTPS at a reverse proxy and keep the terminal-capable Hermes service private:

```text
Android ──HTTPS──▶ Caddy/Nginx ──private loopback──▶ Hermes API :8642
```

Do not expose an unprotected Hermes API directly to the public Internet.

## Configure the App

On first launch, enter the HTTPS base URL and API server key, then select **Test Connection**. A successful test checks health, authenticates, loads capabilities, verifies session resources and controllable run support, and only then persists the configuration.

The base URL is normalized and stored with DataStore. The API key is encrypted with an AES-GCM key generated and held by Android Keystore; only ciphertext and its initialization vector are stored in app-private preferences. The key is never rendered again after a successful save.

Debug builds permit cleartext traffic for emulator/local addresses such as `http://10.0.2.2:8642`. Production builds reject cleartext traffic and require HTTPS.

## Development Setup

Requirements:

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer compatible tools
- Android Studio or the included Gradle wrapper

Create `local.properties` if Android Studio does not create it automatically:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Validation commands:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## Package Structure

```text
com.example.hermesclient
├── app                 Application, activity, navigation, Hilt modules
├── core
│   ├── network         URL, authentication, transport error policy
│   ├── security        Android Keystore-backed credential storage
│   └── ui              Shared theme
├── data
│   ├── preferences     Connection persistence
│   ├── remote          Retrofit DTOs, mappers, SSE transport/parser
│   └── repository      Hermes adapters and repository implementations
├── domain
│   ├── model           UI-independent Hermes concepts and errors
│   └── repository      Application capability boundaries
└── feature
    ├── connection      First-run and settings connection flow
    ├── sessions        Session list and creation
    └── chat            History, streaming turn state, tool activity UI
```

## Design Decisions

- **Repository and adapter boundaries:** UI code has no Retrofit, OkHttp, DTO, or raw SSE dependency.
- **Flow as observer:** OkHttp callbacks are converted to a cancellation-aware `Flow`; leaving chat cancels the owned event source.
- **Sealed state and events:** mutually exclusive loading/streaming/error states avoid conflicting booleans.
- **SSE parser strategy:** transport events are translated once into extensible domain events.
- **Server source of truth:** no Room database or mock production agent exists in V1.
- **Isolated rendering:** `ChatItem` supports messages, tool activity, and focused approval cards while leaving room for attachments later.

## Current Limitations

The client supports one Hermes server and one in-flight run. It is text-only and has no attachments, voice, run steering, skill/model configuration, local session cache, offline sync, background work, or push notifications. History loads up to the server endpoint's current page size. Local HTTP is debug-only.

## Roadmap

The next useful increment is run steering and richer run recovery, including reconnecting to a run-event stream after process recreation. Attachments and multiple named Hermes profiles can follow without replacing the current repository or event boundaries.
