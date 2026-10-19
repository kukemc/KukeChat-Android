<div align="center">

<img src=".github/assets/icon.png" alt="KukeChat" width="120">

# KukeChat Android

**A native Android client, made for a creator community**

The Android edition of KukeChat, written from scratch in Kotlin and Jetpack Compose, talking to the same backend as every other client.

[![License](https://img.shields.io/badge/License-MIT-111111?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-111111?style=flat-square)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-111111?style=flat-square)](https://developer.android.com/jetpack/compose)
[![SDK](https://img.shields.io/badge/minSdk%20%2F%20targetSdk-26%20%2F%2035-111111?style=flat-square)](app/build.gradle.kts)

[简体中文](README.md) · **English**

[About](#about) · [Features](#features) · [Tech stack](#tech-stack) · [Building](#building) · [Project layout](#project-layout) · [Download](#download) · [Related repositories](#related-repositories) · [Contributing](#contributing)

</div>

---

## About

KukeChat is a chat app for Scratch / CCW creators. The community extension and the desktop app live in [KukeChat-Client](https://github.com/kukemc/KukeChat-Client); this repository is the phone.

It is a separate native app because a chat client on a phone has to do much more than draw screens: stay connected in the background, post system notifications, record voice while you hold a button, receive shares from other apps, jump into a conversation from a long-press on the launcher icon, and support predictive back. All of that is steadier, and lighter on the battery, when written natively.

It connects to the same [KukeChat-Server](https://github.com/kukemc/KukeChat-Server) as the other clients, so accounts, conversations and messages are fully shared. APIs and interactions follow the desktop client where possible, and the icon set is generated from the same lucide outlines.

> [!NOTE]
> No server address is written in the code. The backend address is read from `local.properties` at build time, and the build fails if it is missing. See [Building](#building).

## Features

Everything listed below is implemented in the code today.

| Area | What's included |
| --- | --- |
| Chat | Direct messages and groups; text, images (pick several at once, preview before sending), files; quoted replies (swipe to reply), forwarding, reactions (long-press to see who reacted), recall; messages appear instantly on send and can be retried on failure; CCW project and Bilibili video links render as cards; polls; in-conversation search, with the target message centered and highlighted on jump |
| Voice | Hold to talk; live playback progress with seeking; long-press to transcribe |
| Stickers | Favorite stickers, including animated GIF, animated WebP and APNG; a sticker manager with drag-to-reorder, batch delete and move-to-front; group stickers (admins can reorder, rename and add in bulk) |
| Mentions | A "mentioned you" pill that steps through messages that @ you or @ everyone; a hint that jumps to unclaimed red packets |
| Red packets and gifts | Regular, relay and exclusive red packets, with history; gifts and cosmetics as presents; a shop for bubble skins and avatar frames |
| Groups | Group profile and management; announcements with read confirmations; group files (folders, search, download progress, in-app video playback); featured messages, check-in and group tasks; Pro / Pro+ / MAX group upgrades |
| Favorites | Saved messages across all conversations, filterable by all / direct / group |
| Community | Feed (friends, square, topics, posting); community home; team-up center; bot square; account levels and leaderboards; friendship intimacy with badges and streaks; birthday celebrations |
| Jury | A desk for community jurors that shows only anonymized case snapshots provided by the server |
| System integration | A foreground service keeps the realtime connection alive, with WorkManager catching up if the system stops it; share text, images, video and files in from other apps, with direct-share shortcuts; invite deep links `kukechat://invite/<token>`; in-app updates that check automatically when the backend announces a release and verify the download's sha256; predictive back; customizable bottom bar and feature order |

## Tech stack

| Layer | Choice |
| --- | --- |
| Language | Kotlin 2.1.0, JVM 17 |
| UI | Jetpack Compose (BOM 2024.12.01), Material 3 |
| Navigation | Navigation Compose 2.8 with `@Serializable` type-safe routes |
| Networking | OkHttp 4.12 (REST and WebSocket) |
| Serialization | kotlinx.serialization 1.7 |
| Concurrency | Kotlin Coroutines 1.9 |
| Images | Coil 2.7 (with GIF decoding) |
| Media | Media3 ExoPlayer 1.5 (voice, group file videos) |
| Storage | DataStore Preferences, plus local caches for messages and screens |
| Background | Foreground service (`remoteMessaging`) + WorkManager 2.10 |
| Visuals | Haze 1.1 (frosted bottom bar) |
| Build | Android Gradle Plugin 8.7.3, Gradle version catalog |

Dependencies are wired through a hand-written `AppGraph` container rather than Hilt. The whole app holds a single WebSocket, owned by the background service; screens only subscribe to its events.

## Building

### Requirements

- JDK 17
- Android SDK 35

### Configuration

Create `local.properties` in the repository root (it is ignored by git). Each key can also be passed as a Gradle property, `-P<key>=<value>`.

```properties
sdk.dir=<path to Android SDK>
kukechat.apiBaseUrl=https://chat.example.com
kukechat.wsUrl=wss://chat.example.com/ws
# Optional: web address for invite links
kukechat.inviteBaseUrl=https://chat.example.com/invite
```

| Key | Required | Description |
| --- | :---: | --- |
| `sdk.dir` | Yes | Path to the Android SDK |
| `kukechat.apiBaseUrl` | Yes | Backend root URL, with or without `/api/v1` |
| `kukechat.wsUrl` | Yes | Realtime WebSocket URL |
| `kukechat.inviteBaseUrl` | No | Same as the server's `INVITE_BASE_URL`. When set, the app opens web invite links directly; otherwise it only recognizes `kukechat://invite/<token>` |

### Compile

```bash
./gradlew :app:assembleDebug        # development build
./gradlew :app:assembleRelease      # release build
./gradlew :app:testDebugUnitTest    # unit tests
```

Debug builds add a `.dev` suffix to the application ID and `-dev` to the version name, so they install alongside the release build. Use `-Pkukechat.appIdSuffix=<suffix>` for a different suffix.

### Release signing

Release builds read your own `keystore.properties` (with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`), passed via `-Pkukechat.signing=<path to keystore.properties>`. Without a signing config, the release APK is left unsigned and cannot be installed as is.

```bash
./gradlew :app:assembleRelease -Pkukechat.signing=/path/to/keystore.properties
```

> [!WARNING]
> Every version under the same application ID must be signed with the same key. If the key changes, Android refuses to install over the existing app, and installed users stop receiving in-app updates.

## Project layout

```text
app/src/main/java/ink/kuke/chat/
  MainActivity.kt      entry point; handles invite links and notification taps
  KukeApp.kt           Application
  core/
    AppGraph.kt        hand-written dependency container
    net/               HTTP client, client proof, device identity
    realtime/          WebSocket: heartbeat, reconnect, event dispatch
    data/              conversation repository, message and screen caches
    model/             data models
    session/           sign-in state
  service/             foreground service, boot receiver, catch-up worker, notifications
  feature/
    chat/              conversation list, chat screen, composer, voice, stickers, red packets, polls
    group/             group profile and management, group stickers
    groupfiles/        group files, downloads, video player
    groupannouncement/ group announcements
    groupplus/         group upgrades, featured messages
    feed/              feed
    jury/              jury
    share/             system share sheet and direct-share shortcuts
    settings/          settings, in-app updates
    …                  contacts, bots, shop, gifts, level, tasks, teamup and more
  ui/
    shell/             main shell, routes, bottom bar, predictive back
    components/        shared components
    icons/             icons (generated by tools/build-icons.mjs)
    theme/             theme
docs/release-notes/    user-facing release notes per version
tools/build-icons.mjs  generates Compose icons from the lucide outlines in KukeChat-Client
```

## Download

Official builds are available on the [KukeChat download page](https://chat-api.kuke.ink/download). Once installed, new versions are announced and installed from inside the app.

## Related repositories

| Repository | Description |
| --- | --- |
| [KukeChat-Server](https://github.com/kukemc/KukeChat-Server) | Backend: REST API, WebSocket, database |
| [KukeChat-Client](https://github.com/kukemc/KukeChat-Client) | Community extension, desktop app and H5 |
| KukeChat-Android | Native Android client (this repository) |

## Contributing

Issues and pull requests are welcome. Please follow the existing code style and package layout; for anything touching the API, KukeChat-Server's contracts are the source of truth.

Never hard-code a server address. Deployment settings always go through `local.properties` or Gradle properties.

## License

[MIT](LICENSE)

---

<div align="center">
<sub>MIT © 2026 kukemc</sub>
</div>
