# CoinPush

Offline Android coin-pusher game optimized first for a Google Pixel 8 portrait layout.

## Build

This project intentionally uses the installed Android SDK directly, so it does not need Gradle or network access.

```sh
./build.sh
```

The signed debug APK is written to:

```text
CoinPush-debug.apk
```

## Requirements covered

- Fully offline, no ads, no monetization, no external API calls.
- Theme-less modern arcade coin-pusher presentation.
- Native Android Canvas game loop with saveable progression.
- Portrait-first layout tuned for Pixel 8 while scaling to other phone sizes.
