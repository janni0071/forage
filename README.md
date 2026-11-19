# Forage

Local-first video recommendations you control. Forage builds a taste profile only from the videos
you choose, and recommends new ones only when you ask. It sits next to the account-free client you
already use (FreeTube, mpv, NewPipe) and doesn't replace it. See [docs/SPEC.md](docs/SPEC.md).

![Discover](docs/screenshots/2-discover.png)

## Install on Linux (Debian, Ubuntu, Pop!_OS)

Build the package (needs JDK 21 and `fakeroot`):

```bash
./gradlew :desktop:packageReleaseDeb
```

Then install it; `apt` also pulls in the one dependency (`xdg-utils`):

```bash
sudo apt install ./desktop/build/compose/binaries/main-release/deb/forage_0.4.0_amd64.deb
```

Forage then appears in the app menu. Its data lives in `~/.local/share/forage` (see Settings → Your
data). The package bundles its own Java runtime and is built for the machine it is built on.

## Run from source

```bash
./gradlew :desktop:run --args="--data $HOME/forage-data"
```

Without `--data`, it uses the same folder as the installed app.

## Command line

The `cli` module does everything the app does, from a terminal:

```bash
./gradlew :cli:installDist
```

```bash
cli/build/install/forage/bin/forage
```

## Licence

GPL-3.0, as required by NewPipeExtractor.

## Android

Needs the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`). Release builds are
signed with your own key when these are set; otherwise with the debug key:

```bash
FORAGE_KEYSTORE=/path/to/forage-release.jks FORAGE_KEYSTORE_PASSWORD=… ./gradlew :android:assembleRelease
```

The APK lands in `android/build/outputs/apk/release/`. Android 15 or newer, ARM64.

