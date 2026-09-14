# VoiceGuard

Voice chat moderation for [Simple Voice Chat](https://modrepo.de/minecraft/voicechat) on Paper 1.21.x.
VoiceGuard listens to players' microphone audio via the **real, documented** Simple Voice
Chat Plugin API, transcribes it **fully offline** with [Vosk](https://alphacephei.com/vosk/),
checks the recognized text against a blocked-word list, and voice-mutes offenders with an
escalating punishment system.

> **Honesty note (please read):** this README calls out two places where the officially
> documented API doesn't give you what a first glance suggests it would, and explains exactly
> what VoiceGuard does instead. See **"How this actually works under the hood"** below.

---

## 1. Compatible versions

| Component | Version |
|---|---|
| Minecraft / Paper | 1.21.x |
| Java | 21 |
| Simple Voice Chat | 2.5.x (Bukkit/Paper build). Adjust `voicechat.api.version` in `pom.xml` to match your server's installed Simple Voice Chat version — see [available API versions](https://modrepo.de/minecraft/voicechat/api/getting_started). |

VoiceGuard needs **only**:

```
plugins/
├── voicechat-<version>.jar   (Simple Voice Chat)
└── VoiceGuard-1.0.0.jar
```

No Vault, no LuckPerms, no ProtocolLib, no PlaceholderAPI, no Skript, no other moderation
plugin. LuckPerms can be installed for other reasons without conflict, but VoiceGuard does not
require or depend on it.

---

## 2. How this actually works under the hood

VoiceGuard was built strictly against the **real** Simple Voice Chat Plugin API
(`de.maxhenkel.voicechat.api`) — no invented events, no invented methods. Two details of that
real API matter enough that you should know about them upfront:

### a) `MicrophonePacketEvent` is real and is what VoiceGuard uses

```java
registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
...
byte[] opus = event.getPacket().getOpusEncodedData();
UUID player = event.getSenderConnection().getPlayer().getUuid();
if (muted) event.cancel(); // actually blocks the packet from reaching other players
```

This is exactly what the spec asked for, and it is confirmed, documented API.

### b) The Bukkit/Paper build of Simple Voice Chat does **not** ship Opus natives

The official javadoc for `VoicechatApi#createDecoder()` explicitly states:
*"NOTE: The bukkit based version of the voice chat does not include Opus natives."* In
practice, calling `VoicechatApi.createDecoder()` on a Paper server returns `null` — you cannot
decode Opus through the plugin API itself on Bukkit/Paper.

Rather than "pretend" this works, VoiceGuard ships its **own** Opus decoder using
[`opus4j`](https://github.com/henkelmax/opus4j) — the same native Opus wrapper Simple Voice
Chat itself is built on — as a shaded dependency inside `VoiceGuard.jar`. This is a real,
working native Opus decoder; it is just a separate library call rather than going through the
(non-functional-on-Bukkit) API convenience method. Each player gets their own
`de.maxhenkel.opus4j.OpusDecoder` instance (Opus decoding is stateful per-stream, so decoders
are never shared between players).

### c) Voice muting: the guaranteed mechanism vs. the bonus mechanism

- **Guaranteed:** VoiceGuard tracks muted players itself and cancels their
  `MicrophonePacketEvent` before it can be relayed to anyone else
  (`event.cancel()`). This works on every server, regardless of what permission
  plugin (if any) is installed, and **never touches Minecraft's text chat** — muted players
  can still type in chat and hear everyone else.
- **Bonus, best-effort:** VoiceGuard also revokes the `voicechat.speak` permission via a plain
  Bukkit `PermissionAttachment` (core Bukkit API — this does **not** require LuckPerms or any
  other permission plugin). On servers where Simple Voice Chat itself checks that permission
  node (via a Vault-compatible permissions setup) this additionally drives the client-side
  "you are muted" microphone icon. If nothing on your server checks that permission, the
  packet-cancellation above still fully and reliably enforces the mute — you'd just be missing
  the client icon, not the actual enforcement.

---

## 3. Installing Simple Voice Chat

1. Download the Bukkit/Paper build of Simple Voice Chat for your Minecraft version from
   [Modrinth](https://modrinth.com/plugin/simple-voice-chat) or
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/simple-voice-chat).
2. Drop the `.jar` into `plugins/`.
3. Start the server once so Simple Voice Chat generates its config, then stop it.

## 4. Installing VoiceGuard

1. Build the plugin (see below) or use a pre-built `VoiceGuard-1.0.0.jar`.
2. Drop `VoiceGuard-1.0.0.jar` into `plugins/`.
3. Start the server once. VoiceGuard creates `plugins/VoiceGuard/config.yml` and exits STT
   loading with an error until you install a Czech model (next section) — this is expected on
   first boot.

### Building from source

```bash
mvn clean package
```

The shaded jar (including opus4j, Vosk's Java bindings + JNA, and sqlite-jdbc) is produced at
`target/VoiceGuard-1.0.0.jar`.

> If your installed Simple Voice Chat version differs from the one this project targets, update
> `<voicechat.api.version>` in `pom.xml` first (the plugin API is versioned separately from the
> mod/plugin itself and is generally backward compatible within a major version).

## 5. Installing the Czech Vosk model

VoiceGuard does **not** bundle a speech model (they're tens to hundreds of MB and licensed
separately). Download one from **https://alphacephei.com/vosk/models**:

- `vosk-model-small-cs-0.4` — small (~50 MB), fast, lower accuracy. Good default for a
  moderation filter on modest hardware.
- `vosk-model-cs-0.4-rhasspy` — larger, more accurate, more CPU/RAM per recognition.

Unpack it so the folder structure looks like:

```
plugins/VoiceGuard/models/vosk-model-small-cs-0.4/
├── am/
├── conf/
├── graph/
└── ...
```

The path must match `stt.model-path` in `config.yml` (relative to `plugins/VoiceGuard/`).
Restart the server or run `/voiceguard reload` — check `/voiceguard status` to confirm
`STT: READY`. Model loading happens on a background thread and does not block server startup.

## 6. Configuration (`config.yml`)

See the shipped `config.yml` for the full, commented file. Key sections:

- **`stt`** — language tag, model path, worker thread count, target sample rate (16000, what
  Vosk models expect — VoiceGuard resamples down from Simple Voice Chat's 48kHz automatically).
- **`audio`** — how packets are grouped into "one sentence": `silence-timeout-ms` (gap that
  ends a segment), `max-segment-ms` (hard cap so nonstop talking doesn't blow up memory),
  `min-segment-ms` (discard segments too short to be real speech).
- **`blocked-words`** — plain list, case-insensitive, whole-word matching.
- **`text-normalization`** — lowercasing, punctuation stripping, whitespace collapsing, and
  conservative collapsing of `s.l.o.v.o` / `s-l-o-v-o` style filter-dodging.
- **`punishment`** — base duration plus an optional escalation ladder by violation count.
- **`privacy`** — whether to persist recognized text; raw audio is **never** written to disk
  regardless of this setting (see Privacy section below).
- **`messages`** — every player-facing string, colour-coded with `&` codes.

## 7. Commands

| Command | Description |
|---|---|
| `/voiceguard reload` | Reloads `config.yml` |
| `/voiceguard status` | Shows plugin/API/STT connection status and live stats |
| `/voiceguard mute <player> [duration]` | Manually voice-mutes a player (default duration from config if omitted) |
| `/voiceguard unmute <player>` | Removes an active voice mute |
| `/voiceguard history <player>` | Shows the player's last 10 logged violations |
| `/voiceguard test` | Runs the built-in self-test (see below) |

Alias: `/vg`.

## 8. Permissions

| Permission | Default | Description |
|---|---|---|
| `voiceguard.admin` | op | All admin commands |
| `voiceguard.reload` | op | `/voiceguard reload` |
| `voiceguard.mute` | op | `/voiceguard mute` |
| `voiceguard.unmute` | op | `/voiceguard unmute` |
| `voiceguard.history` | op | `/voiceguard history` |
| `voiceguard.bypass` | false | Player is never scanned or auto-muted |

## 9. Setting up blocked words

Edit `blocked-words` in `config.yml`:

```yaml
blocked-words:
  - slovo1
  - slovo2
  - slovo3
```

Matching is whole-word and case-insensitive after normalization, so `SLOVO1`, `Slovo1` and
`slovo1` are all treated the same. With `collapse-letter-spacing: true`, deliberately spaced-out
attempts like `s.l.o.v.o` or `s-l-o-v-o` are also caught, without enabling aggressive fuzzy
matching that would risk false positives on normal sentences.

Recognition accuracy depends entirely on the Vosk model and audio quality — a small model in a
noisy voice channel will occasionally mis-hear words. There is no way around this with any
offline STT engine; if false positives become a problem, consider the larger Czech model or
tightening `min-segment-ms`.

## 10. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `/voiceguard status` shows `Simple Voice Chat: NOT CONNECTED` | Simple Voice Chat isn't installed/enabled, or loaded after VoiceGuard. Confirm `depend: [voicechat]` took effect and check the SVC plugin is actually running. |
| `STT: FAILED` | Model path in `config.yml` doesn't point at a valid, fully-unpacked Vosk model directory. Check the exact error with `/voiceguard status`. |
| No mutes ever trigger despite swearing | Confirm blocked words are lowercase-friendly and `punishment.enabled: true`; check `plugins/VoiceGuard/logs/` for whether transcription is happening at all; a small model may simply be mis-hearing the word. |
| Server lag while people talk | Increase `stt.worker-threads` cautiously (each is a real CPU cost), or switch to the small model; also check `min-segment-ms` isn't near 0 (too many tiny segments = more STT calls). |
| Muted players still audible to others | Confirm you're on the version of VoiceGuard where `MicrophonePacketEvent#cancel()` is called for muted senders (this is the core enforcement, independent of any permission plugin) — check `/voiceguard test`. |

## 11. Privacy

```yaml
privacy:
  store-audio: false
  store-transcriptions: true
```

- Audio flows **RAM → Opus decode → resample → Vosk → text → audio discarded**. VoiceGuard never
  writes raw or decoded audio to disk, regardless of configuration — `store-audio` exists for
  forward-compatibility but there is currently no code path that persists audio at all.
- `store-transcriptions: false` stops recognized text from being written to the SQLite database
  or the log files; only the detected blocked word, punishment and timestamp are kept.
- Bypass players (`voiceguard.bypass`) are never fed into the STT pipeline at all — their audio
  is neither decoded nor buffered by VoiceGuard.

## 12. Performance notes

- Speech-to-text never runs on the main thread. The flow is: main-thread-free packet decode
  (cheap) → async silence-timeout check → async Vosk transcription on a small fixed worker pool
  → main-thread hop only for the final filter/mute/DB step (`/voiceguard status` shows queue
  size implicitly via "Players monitored").
- Each currently-speaking player gets one lightweight per-player buffer and Opus decoder;
  buffers are freed on disconnect.
- Vosk's `Model` is loaded once and shared read-only across all worker threads; each worker
  thread owns its own `Recognizer` (Vosk recognizers are not thread-safe to share).
- SQLite access is funneled through a single dedicated background thread — no disk I/O ever
  touches the main thread or the STT workers.
- Realistic cost driver is CPU, not Minecraft server tick time: STT is genuinely expensive per
  second of audio. On a small/medium server, `vosk-model-small-cs-0.4` with 2 worker threads is
  a reasonable starting point; scale `stt.worker-threads` to your CPU headroom, not to your
  player count.

---

## Known limitations / honest caveats

- The resampler (48kHz → 16kHz) is a simple linear interpolation, not a band-limited/windowed
  resampler. It's cheap and accurate enough for speech recognition on short segments, but if you
  need maximum fidelity, swap `AudioResampler` for a proper DSP library.
- Speech-segment boundaries are detected purely by "gap between packets", which matches how
  Simple Voice Chat's voice-activation/push-to-talk client behaves (it only sends packets while
  you're actually talking) but is not true VAD (voice activity detection) on the decoded audio
  itself.
- This project was written and reviewed against the documented Simple Voice Chat Plugin API
  (javadoc for API version 2.1.x/2.5.x) but was **not** compiled in this environment (no network
  access to Maven Central / PaperMC / the Simple Voice Chat maven repo from here). Run
  `mvn clean package` yourself and fix any version-drift compile errors — Simple Voice Chat's
  API does evolve between releases, so double-check `voicechat.api.version` against your
  server's installed version first.
