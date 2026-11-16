# Forage — Project Spec

Nov 16, 2026 · @Jannis

Forage is a local-first companion app that builds a YouTube taste profile only from videos you choose, and recommends new videos only when you ask. It sits next to whatever account-free client you already use (NewPipe, LibreTube, FreeTube) and does not replace it.

## Vision and principles

- **You own the data.** The profile, history and subscriptions live on your devices in open formats you can read, edit, export and delete. No account, no server, no telemetry.
- **Curated, not tracked.** Watching something does not add it to your profile. Only videos you include count, so autoplay and accidental clicks never shape the profile.
- **Pull, not push.** There's no recommendation feed on the start screen. Recommendations live on a Discover page you open on purpose.
- **Explainable.** Every recommendation shows why it was picked ("similar to 3 videos in your profile", "new channel near your woodworking cluster").
- **Works with any player.** The app finds and organizes videos. Playback happens in the client you already use.

## v1 feature set

v1 is the smallest version that answers the key question: are the recommendations good enough to use?

| Area | v1 scope | Later |
| --- | --- | --- |
| Import | Google Takeout (history, subscriptions, playlists); NewPipe and FreeTube exports | LibreTube, Invidious, Piped |
| Library | Every known video with a state: *watched*, *in profile*, *excluded*. Bulk include by channel, watch count or date range | Auto-suggest likely favourites from rewatches |
| Manual add | Paste a URL, search inside the app, Android share target | Browser extension |
| Taste profile | Toggle videos in or out; view the profile as topic clusters; pin or mute a cluster | Several profiles ("learning", "evening") |
| Discover | On-demand page: generate a batch, see the reason for each pick, then *more like this*, *not for me* or *save* | Mood or topic slider, "surprise me" mode |
| Subscriptions | Channel RSS feeds, a new-uploads inbox, optional notifications; Shorts hidden by default | Per-channel filters (minimum length, keywords) |
| Watch later | One queue across devices and players | Smart ordering by length |
| Notes and tags | Free-text notes and tags per video; tags also feed the profile | Search across notes |
| Playback hand-off | Open in NewPipe/LibreTube (Android intent), FreeTube, mpv or browser (desktop) | Embedded preview player |
| Sync | Encrypted per-device change logs, synced with Syncthing or any folder sync | Direct device-to-device sync |
| Export | Full data as JSON/SQLite; subscriptions back out to NewPipe/FreeTube formats | One-click client migration |

## How recommendations work

The profile is a set of video embeddings, not one average, so separate interests stay separate. Each Discover visit runs the pipeline below, using cached metadata where it can.

&#91;embedded content: recommendation pipeline · 6 stages, 1 feedback loop\]

- **Profile.** Each included video becomes a vector built from its title, description, tags, channel name and your notes. Clustering (k-means or HDBSCAN) groups the vectors into topics you can see, pin or mute. Videos you mark "not for me" become negative examples.
- **Candidates.** For a sample of profile videos from each cluster: related videos via the extractor, channels those creators feature or collaborate with, and searches built from each cluster's top keywords. Aim for 200 to 500 candidates per visit, cached for a day.
- **Ranking.** Score = similarity to the nearest profile videos, minus similarity to negative examples, plus a bonus for channels you don't follow and a small freshness bonus. Drop watched and excluded videos, then re-rank for diversity (MMR) so one cluster can't fill every slot. Keep 2 or 3 of 20 slots for exploration.
- **Feedback.** "More like this" adds the video to the profile at reduced weight, "not for me" adds a negative example, and "save" puts it in watch later.

## Tech stack

Kotlin with Compose Multiplatform lets one codebase target Android and desktop. NewPipeExtractor, the most battle-tested account-free YouTube library, is plain Java, so it runs on both without a server.

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Compose Multiplatform (Android + desktop JVM) | One UI codebase; native feel on Android |
| Shared core | Kotlin Multiplatform module | Import, profile, ranking and sync logic written once |
| YouTube data | NewPipeExtractor behind an `ExtractorSource` interface | Metadata, related videos, search, channel tabs; no API key |
| Fallback sources | Channel RSS feeds; optional Invidious/Piped instance; yt-dlp on desktop | Keeps working when one source breaks |
| Embeddings | Small multilingual sentence model (e.g. paraphrase-multilingual-MiniLM-L12-v2) via ONNX Runtime | Runs offline on phone and desktop; handles German, English, French titles |
| Storage | SQLite via SQLDelight | Shared schema on both platforms, easy to export |
| Vector search | Brute-force cosine similarity in memory | Fast enough for tens of thousands of videos; no extra dependency |
| Sync | Encrypted per-device change logs (age or libsodium) in a user-chosen folder | Works with Syncthing, Nextcloud or a USB stick; no server to run |
| Background work | WorkManager (Android), coroutine scheduler (desktop) | RSS checks and candidate prefetch |

Alternative considered: Flutter or Tauri. Both would mean reimplementing the extractor or running it as a sidecar process, which loses the main advantage.

## Data import formats

Every import lands videos as *watched, not in profile*. The user then curates. Nothing goes into the profile automatically.

| Source | File | What we take | Notes |
| --- | --- | --- | --- |
| Google Takeout | `YouTube and YouTube Music/history/watch-history.json` | Video ID, title, channel, timestamp | User must switch history format from HTML to JSON in Takeout; skip ad entries and removed videos with no URL |
| Google Takeout | `subscriptions/subscriptions.csv`, `playlists/*.csv` | Channel IDs; playlist video IDs | Playlists can seed the profile directly ("my liked videos") |
| NewPipe | Exported `.zip` containing `newpipe.db` (SQLite) | Watch history, subscriptions, local playlists | Schema changes between versions; read defensively |
| FreeTube | `history.db`, `profiles.db`, `playlists.db` (one JSON object per line) | History, subscriptions, playlists | Also accepts its own export files |
| LibreTube | JSON backup | History, subscriptions, playlists | Later milestone |
| Manual | URL, in-app search, share intent | Single videos | Fastest path for "I know I watched this" |

All formats above should be checked against current app versions before writing parsers. Keep a folder of real sample exports as test fixtures.

## Build order

Test recommendation quality first, with no UI. Everything after that is packaging.

1. **M0: Recommendation spike (desktop CLI).** Parse your own Takeout file, hand-pick 50 to 100 profile videos, fetch metadata, embed, generate candidates, print 20 ranked picks with reasons.
   - Exit: at least 5 of 20 picks are ones you'd actually watch. If not, tune candidates and scoring before building anything else.
2. **M1: Desktop app, core loop.** Library with watched / in profile / excluded states, bulk curation, Discover page, feedback buttons, open in FreeTube or mpv.
   - Exit: you use it instead of the CLI for two weeks.
3. **M2: Account-free extras.** RSS subscriptions inbox, watch later queue, notes and tags, NewPipe and FreeTube import.
   - Exit: you stop opening your old client's subscription feed.
4. **M3: Android app.** Same core module, Compose UI, share target, upload notifications, hand-off to NewPipe/LibreTube.
   - Exit: "add to profile" from your phone takes under 3 seconds.
5. **M4: Sync.** Per-device encrypted change logs, merge by replay (see Decisions), Syncthing setup guide.
   - Exit: curate on the phone, see it on the desktop without manual steps.
6. **M5: Public release.** Onboarding with a Takeout walkthrough, export back to other clients, F-Droid and Flathub packaging.

## Future: other platforms

v1 is YouTube-only, but it's built so other platforms plug in later without changes to the core. The profile works from text, so it can score videos from any platform. A YouTube-trained profile could then suggest similar creators on PeerTube or Nebula, which offers a way off YouTube, not just a better way to use it.

Design rules from v1 onward:

- **Platform-qualified IDs.** Every video, channel and playlist is stored as `platform:id` (e.g. `youtube:dQw4w9WgXcQ`), never as a bare YouTube ID. This applies to the database schema, sync format and exports.
- **One plugin per platform.** Each platform implements `ExtractorSource`: metadata, related videos, search, channel feed and an open-in-player link. Core, profile and ranking code never call a platform directly.
- **Capabilities, not assumptions.** A plugin declares what it supports (e.g. no related videos, no RSS). Candidate generation uses what's available and skips the rest.
- **Platform-neutral metadata.** One shared video model (title, description, tags, duration, channel, language, thumbnail) with an extra field for platform-specific data.

| Platform | Likely effort | Why |
| --- | --- | --- |
| PeerTube | Low | Open API, federated search; already supported by NewPipeExtractor |
| media.ccc.de | Low | Supported by NewPipeExtractor; talks have rich descriptions |
| Vimeo, Dailymotion, Odysee | Medium | Metadata via yt-dlp or APIs; candidate generation built for each platform |
| Nebula and other paid platforms | Medium | Needs the user's own login; check terms first |
| TikTok, Instagram | High | Actively block scraping; short videos carry little text for the profile |

### Sensitive platforms (e.g. adult sites)

Adult sites are technically feasible: Pornhub has an official affiliate API via HubTraffic (check its current terms), and its tag-rich metadata suits the profile well. The hard parts are separation, distribution and regulation, and they shape the design:

- **Isolated profiles.** Each sensitive platform gets its own profile, with its own vectors, history and candidates. It never influences, and is never influenced by, other profiles.
- **Locked by default.** Hidden from the profile switcher until unlocked with a PIN or the device's biometric lock. It never appears in notifications, recent items or the Discover page of other profiles.
- **Encrypted at rest.** Stored in a separate database file with its own key, so it can be wiped on its own.
- **Sync is opt-in for each profile.** Off by default, so the data never lands on a shared or synced device by accident.
- **Separate plugin.** Distributed apart from the main app, because Google Play does not allow sexual content and F-Droid flags it as an anti-feature.
- **Region-aware.** Age-verification laws change access by country; the plugin should fail gracefully when a site is blocked or requires verification.

v1 implication: the profile model must support multiple isolated profiles from the start, even if the UI shows only one.

## Risks and decisions

The biggest risk is dependence on unofficial YouTube access. Everything else can be fixed in code.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| YouTube changes break the extractor | Discover and metadata fetch stop working | Source interface with fallbacks (RSS, Invidious/Piped, yt-dlp); cache all metadata locally; track NewPipeExtractor releases |
| Scraping is against YouTube's terms of service | Rate limits, IP blocks; no Play Store listing | Conservative request rates, local caching; distribute through F-Droid, GitHub and Flathub |
| GPL-3.0 licence of NewPipeExtractor | The app must be GPL-compatible | Release under GPL-3.0 from day one |
| Cold start: small profile, weak picks | Users give up after the first Discover visit | Seed from Takeout playlists and top channels; ask for 10 picks during onboarding |
| Filter bubble | Recommendations only ever show more of the same | Exploration slots, a new-channel bonus, diversity re-ranking |
| Embedding model size on phones | Large download, slow first run | Quantized model, download on first use, desktop can share precomputed vectors through sync |

### Decisions

| Question | Decision | Why |
| --- | --- | --- |
| Project name | Decided: **Forage** (other candidates were Palate, Glean, Sift) | Forage = you go looking when you want something new, nothing is pushed; not tied to YouTube. Check GitHub, F-Droid and app stores for clashes |
| Transcripts | Not in v1. Test them in M0 on videos with weak picks; add later only if they clearly help | Titles, descriptions, tags and notes are cheap and usually enough; transcripts are slow, often missing and noisy when auto-generated |
| Shorts | Hidden by default in Discover and the subscriptions inbox, with a setting to show them. Shorts you add to the profile on purpose still count | Little text for the profile; endless swiping goes against the app's purpose. Detect by `/shorts/` URL or extractor flag, not duration (Shorts can be up to 3 minutes) |
| Sync conflicts | Each device writes only its own encrypted change log (`phone.log`, `laptop.log`) in the synced folder. Merge = replay all logs in time order: latest change wins per field; sets (profile, tags, watch later) use separate add/remove events; deletions leave a tombstone | No Syncthing conflict copies, no device silently overwriting another, deleted items don't come back |

- [ ] Check GitHub, F-Droid and Flathub for other projects called Forage before the first public release
