# WearTube

A standalone YouTube client for Wear OS. Runs entirely on the watch — no phone
app, no companion, no bridge.

Built for Galaxy Watch Ultra / Ultra 2, and sized to work on any round or square
Wear OS display.

## Features

- **Your real feed** — sign in with a TV-style device code (you type a short code
  on your phone; the watch never sees your password) and Home shows your actual
  YouTube recommendations and subscriptions. Signed out, it builds a feed from
  channels you follow locally.
- **Search** with the watch keyboard or voice, plus suggestions
- **Channels** — browse uploads, subscribe/unsubscribe (syncs to your account)
- **Playlists** — your account's playlists, plus Play All queueing
- **Player** — quality picker (144p–480p), speed control, ±10s, tap-to-seek,
  rotating bezel for volume, audio-only mode
- **Background audio** — keeps playing when you leave the app, with a chip on the
  watch face and full system media controls
- **Mini player** — persistent play/pause pill on every screen
- **Comments**, autoplay-next, Watch Later, history and resume positions

## Install

Wear OS doesn't have a sideload UI, so install over adb:

```bash
adb connect <watch-ip>:<port>
adb install WearTube.apk
```

Enable **Settings → Developer options → Wireless debugging** on the watch first
and read the IP/port from that screen. The port changes whenever the watch
sleeps.

## Building

```bash
gradle assembleRelease
```

Requires the Android command-line tools with `platforms;android-37.1`. A signing
keystore is generated automatically on first release build and is never
committed — back it up if you intend to publish updates.

## How it works

WearTube talks to YouTube's InnerTube endpoints directly and plays streams with
Media3/ExoPlayer. Two details do most of the heavy lifting:

- **Pinned player client.** Current client versions return SABR responses with no
  stream URLs at all. The app pins known-good versions and falls back across
  several clients.
- **Per-URL byte budgets.** A googlevideo URL serves roughly 0.5–2MB in total and
  then refuses everything, regardless of how small each range is. The app tracks
  a budget per URL, prefetches a replacement before it runs out, and swaps
  transparently underneath ExoPlayer, so a refusal never reaches the decoder.

## Notes

This is an unofficial client and is not affiliated with, endorsed by, or
supported by YouTube or Google. It is provided for personal use.

## License

MIT — see [LICENSE](LICENSE).
