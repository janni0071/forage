# Forage

**YouTube without an account, and without the feed.** Forage keeps your history, likes, playlists
and subscriptions on your own devices, and recommends new videos only when you ask: from what you
like, with the reason for each pick. No account, no server, no tracking.

<p>
  <img src="docs/screenshots/discover.png" width="66%" alt="Discover: recommendations with the reason for each">
  <img src="docs/screenshots/phone-home.png" width="26%" alt="Home on a phone">
</p>

## What it does

- **Recommendations when you ask.** Discover suggests videos from what you like, grouped by the
  topics your likes fall into, each with why it was picked. Nothing plays by itself and there's no
  endless feed. The model behind it runs on your device.
- **Your library, no account.** Watch history, likes, playlists, a watch-later queue,
  subscriptions with their new uploads, and where you left off in each video.
- **Bring your YouTube along.** Import from Google Takeout, or from NewPipe, FreeTube or LibreTube.
- **Play it your way.** On Android, videos play right in Forage, without ads. On Linux, in mpv
  (Forage sets it up in one click), FreeTube or your browser.
- **Sync without a server.** Pair your phone, tablet and computers over your home Wi-Fi, encrypted;
  your library stays the same on all of them.
- **A private vault.** A second library, encrypted and hidden until you unlock it with a PIN, kept
  apart from everything else (in Forage Full, it can also use Pornhub).

<p>
  <img src="docs/screenshots/video-page.png" width="49%" alt="A video's page">
  <img src="docs/screenshots/watch-later.png" width="49%" alt="The watch-later queue">
</p>

## Install

Download the latest version from [Releases](https://github.com/janni0071/forage/releases). Forage
comes in two versions:

- **Forage** (Standard), for most people: everything above.
- **Forage Full** adds one thing: the private vault can also search, recommend and play from
  Pornhub. Standard doesn't contain any of that code.

Both are one app with one library: installing one over the other switches versions and keeps
everything.

| | Standard | Full |
| --- | --- | --- |
| Android (15 or newer, 64-bit ARM) | `forage-<version>.apk` | `forage-full-<version>.apk` |
| Linux with `apt` (Debian, Ubuntu, Pop!_OS…), 64-bit | `forage_<version>_amd64.deb` | `forage-full_<version>_amd64.deb` |

**Android:** download the APK on your phone and open it. Android asks once to allow installing apps
from your browser.

**Linux:** open the package with your software installer, or:

```bash
sudo apt install ./forage_<version>_amd64.deb
```

Forage then appears in your app menu. On first start it walks you through bringing your library
along and choosing a player.

## Updates

Forage can tell you when there's a new version: it offers this when you first start it, and
Settings, Backup & data, can switch it on or off. When allowed, it asks GitHub once a day and shows
a note with a download link; it never installs anything by itself.

On Android, [Obtainium](https://github.com/ImranR98/Obtainium) can also install and update Forage
straight from this repository's releases: add `https://github.com/janni0071/forage` in it.

## Privacy

Everything Forage knows lives on your devices. It has no account, no analytics and no server of
its own. It contacts only:

- **YouTube**, for what you ask for: searches, video details, your subscriptions' new uploads, and
  thumbnails. Playing in Forage on Android streams from YouTube too. No account is used.
- **Hugging Face**, once, to download the recommendation model (118 MB; on phones over Wi-Fi only,
  unless you say otherwise).
- **GitHub**, when you set up mpv (to download yt-dlp and Deno, and to keep yt-dlp up to date),
  and once a day if you allowed the update check.
- **Your own devices**, on your local network, when you pair them for sync.

In Forage Full, the private vault's site is contacted only while the vault is unlocked and you use it.

## Bring your library along

| From | How |
| --- | --- |
| Google Takeout | Forage's welcome walks you through it; import the zip Google emails you |
| NewPipe | Settings, Backup and restore, Export database; import the zip |
| FreeTube | Settings, Data settings: export subscriptions, history and playlists; import each file |
| LibreTube | Settings, Backup and restore, Backup; import the JSON file |
| Another device with Forage | Pair them (Settings, Sync) and the library comes over |
| A Forage backup | Settings, Backup & data, Restore from a backup |

## Build from source

Needs JDK 21. For the Linux package also `fakeroot`, for Android the Android SDK (`sdk.dir` in
`local.properties`, or `ANDROID_HOME`).

```bash
./gradlew test
```

```bash
./gradlew :desktop:packageReleaseDebFile :android:assembleFullRelease
```

That builds Forage Full; add `-Pedition=standard` for Standard's Linux package
(`:android:assembleStandardRelease` for its APK). The packages land in `desktop/build/release/`
(`:desktop:packageReleaseDebFile`), the APKs in `android/build/outputs/apk/<version>/release/`. Release APKs are signed with your own key when
`FORAGE_KEYSTORE` and `FORAGE_KEYSTORE_PASSWORD` are set, otherwise with the debug key. To run the
desktop app from source with its own data folder:

```bash
./gradlew :desktop:run --args="--data $HOME/forage-data"
```

The `cli` module does what the app does from a terminal (`./gradlew :cli:installDist`). The
design, and why it is the way it is, is in [docs/SPEC.md](docs/SPEC.md).

## Licence

GPL-3.0, as required by NewPipeExtractor.
