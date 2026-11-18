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

The profile is a set of video embeddings, not one average, so separate interests stay separate. Each Discover visit runs the pipeline below, using cached metadata where it can. The details below reflect what the M0 spike found to work on a real profile.

&#91;embedded content: recommendation pipeline · 6 stages, 1 feedback loop\]

- **Profile.** Each included video becomes a vector built from its title, channel name, tags, your notes and the start of its description, in that order (the model reads only the first 128 tokens). Videos you mark "not for me" become negative examples.
- **Topics.** Average-linkage clustering merges videos until no two groups are similar enough (threshold 0.35). Groups of three or more are topics you can see, pin or mute; everything else goes into one "varied interests" group. A real profile is a few strong topics plus many one-off interests, and k-means forced those into mixed clusters. Topic labels come from the words that set a topic apart, never from channel names.
- **Candidates.** For a sample of profile videos from each topic (more from the varied group): related videos via the extractor, and one search per topic built from its top keywords. Channels that creators feature or collaborate with are planned but not built. A 150-video profile yields about 1,800 candidates. Candidates whose title is clearly in a language the profile doesn't contain are dropped, because the embedding model matches across languages.
- **Candidate text.** Candidates are scored on title and channel name only, since that is all a list result carries and fetching full metadata for every candidate is too slow. Profile videos use the full text.
- **Ranking.** Score = mean similarity to the three nearest profile videos, plus a bonus for channels you don't follow, plus a freshness bonus, minus a small penalty for candidates found only by search. Drop watched, saved and excluded videos, then re-rank for diversity (MMR) with at most two picks per channel. Keep 3 of 20 slots for exploration: looser matches from channels you don't know.
- **Negative examples.** The "not for me" penalty applies only as far as a candidate is closer to a rejected video than to the profile. Subtracting the plain similarity sank whole subjects: rejecting three beginner cube tutorials removed every cube video.
- **Freshness.** Every recent upload gets a small bonus. It is larger where you save videos soon after release, measured per candidate from its nearest profile videos, so trailers and news favour new uploads while documentaries are left alone.
- **Feedback.** "More like this" adds the video to the profile at reduced weight, "not for me" adds a negative example, and "save" puts it in watch later.

## Tech stack

Kotlin with Compose Multiplatform lets one codebase target Android and desktop. NewPipeExtractor, the most battle-tested account-free YouTube library, is plain Java, so it runs on both without a server.

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Compose Multiplatform (Android + desktop JVM) | One UI codebase; native feel on Android |
| Shared core | Kotlin/JVM library (`core`) | Import, profile, ranking and sync logic written once. Android and desktop are both JVM and NewPipeExtractor is Java, so a Multiplatform module adds nothing yet |
| YouTube data | NewPipeExtractor behind an `ExtractorSource` interface | Metadata, related videos, search, channel tabs; no API key |
| Fallback sources | Channel RSS feeds; optional Invidious/Piped instance; yt-dlp on desktop | Keeps working when one source breaks |
| Embeddings | paraphrase-multilingual-MiniLM-L12-v2 via ONNX Runtime | Runs offline; handles German, English, French titles. Rare compound words match poorly across languages. The tokenizer library (DJL) is desktop-only, so Android needs another |
| Language detection | Lingua, accurate mode, over about 20 common languages | Built for short texts. Two lighter options failed on real titles. Heavy (80 MB library, several hundred MB of memory), so Android needs another |
| Storage | SQLite via SQLDelight | Shared schema on both platforms, easy to export |
| Vector search | Brute-force cosine similarity in memory | Fast enough for tens of thousands of videos; no extra dependency |
| Sync | Encrypted per-device change logs (age or libsodium) in a user-chosen folder | Works with Syncthing, Nextcloud or a USB stick; no server to run |
| Background work | WorkManager (Android), coroutine scheduler (desktop) | RSS checks and candidate prefetch |

Alternative considered: Flutter or Tauri. Both would mean reimplementing the extractor or running it as a sidecar process, which loses the main advantage.

## Data import formats

Every import lands videos as *watched, not in profile*. The user then curates. Nothing goes into the profile automatically.

| Source | File | What we take | Notes |
| --- | --- | --- | --- |
| Google Takeout | `history/watch-history.html` or `.json` | Video ID, title, channel, timestamp | HTML is Takeout's default and JSON an option; both are parsed. Skip ad entries, posts and removed videos with no URL. Dates in the HTML are in the account's language |
| Google Takeout | `subscriptions/subscriptions.csv`, `playlists/*-videos.csv` | Channel IDs; playlist video IDs with the date added | Playlists list IDs only, so titles need a metadata fetch. Watch later is the best profile seed: every entry was saved on purpose |
| NewPipe | Exported `.zip` containing `newpipe.db` (SQLite) | Watch history, subscriptions, local playlists | Schema changes between versions; read defensively |
| FreeTube | `history.db`, `profiles.db`, `playlists.db` (one JSON object per line) | History, subscriptions, playlists | Also accepts its own export files |
| LibreTube | JSON backup | History, subscriptions, playlists | Later milestone |
| Manual | URL, in-app search, share intent | Single videos | Fastest path for "I know I watched this" |

All formats above should be checked against current app versions before writing parsers. Keep a folder of real sample exports as test fixtures.

## Build order

Test recommendation quality first, with no UI. Everything after that is packaging.

1. **M0: Recommendation spike (desktop CLI).** Parse your own Takeout file, hand-pick 50 to 100 profile videos, fetch metadata, embed, generate candidates, print 20 ranked picks with reasons.
   - Exit: at least 5 of 20 picks are ones you'd actually watch. If not, tune candidates and scoring before building anything else.
   - **Done, Nov 17, 2026.** Profile: the 149 most recent Watch later videos. Round 1: 5 of 20 would be watched, 2 maybe. Round 3, after tuning from feedback: 14 of 20, 3 maybe, 3 no. The misses were word-level matches that missed the tone (a real job interview next to comedy interviews), a generic compilation and a tabloid take on a topic.
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

How this is built: the database file is the isolation boundary. A "vault" is one database file holding its own profiles, history, metadata cache and vectors, and no code path reads across files. The main vault is unencrypted; a sensitive platform gets its own encrypted file, which can be wiped by deleting it. Within a vault, several profiles ("learning", "evening") can share history and cache.

The private vault as built (Nov 2026):

- **Encryption.** The whole database is sealed with AES-256-GCM, with the key derived from the user's PIN or passphrase by PBKDF2-HMAC-SHA256 (600,000 rounds, per-installation salt). Only the JDK's own cryptography is used; no extra library or native code.
- **Never on disk in the clear.** Unlocking decrypts the file into an in-memory SQLite database; changes are encrypted back to the file a few seconds after they happen and on lock. Its thumbnails, settings and player output stay in memory too.
- **Existence stays hidden.** The vault's file name is derived from the PIN, so a wrong PIN and no vault look identical, and creating a vault never overwrites another. "Private profiles…" is always in the profile menu. Someone browsing the data folder sees that encrypted files exist, but not what they hold.
- **Shared, impersonal parts.** The embedding and language models are shared with the normal vault; they hold no personal data.
- **Limits.** A short numeric PIN can be guessed offline by someone with the file, so the app recommends a passphrase. A forgotten PIN can't be recovered. While unlocked, the data is in memory, where the operating system may swap it to disk.
- **Platform.** The vault works with YouTube today; the first sensitive platform plugin is still to come, after checking the site's API terms.

Plan: design for it from M1 (the vault boundary), build the first sensitive plugin after M1's core loop works, as a separate module outside the default build. Before writing it, check that the HubTraffic API still exists and what its terms allow. There is no known history export to import from, so such a profile starts from pasted URLs.

## Risks and decisions

The biggest risk is dependence on unofficial YouTube access. Everything else can be fixed in code.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| YouTube changes break the extractor | Discover and metadata fetch stop working | Source interface with fallbacks (RSS, Invidious/Piped, yt-dlp); cache all metadata locally; track NewPipeExtractor releases |
| YouTube bot-checks the network | Requests fail at random; seen on a cloud server, where a fetch took 10 to 35 seconds per video | Pause between requests, retry with growing waits, stop a run that keeps failing and resume from the cache later. Home connections should fare better; not yet measured |
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
| Shorts | Hidden by default in Discover and the subscriptions inbox, with a setting to show them. Shorts you add to the profile on purpose still count | Little text for the profile; endless swiping goes against the app's purpose. Detect by `/shorts/` URL or extractor flag, not duration (Shorts can be up to 3 minutes). **Open problem:** Takeout lists Shorts with normal watch URLs and the extractor did not flag them either, so Shorts in the history go undetected |
| Clustering | Average-linkage clustering with a similarity threshold, plus a "varied interests" group | k-means needs the number of clusters up front and produced mixed clusters on a real profile |
| Profile storage in M0 | A hand-edited text file of video IDs | Enough for the spike; M1 moves library, profile and feedback into the database |
| Sync conflicts | Each device writes only its own encrypted change log (`phone.log`, `laptop.log`) in the synced folder. Merge = replay all logs in time order: latest change wins per field; sets (profile, tags, watch later) use separate add/remove events; deletions leave a tombstone | No Syncthing conflict copies, no device silently overwriting another, deleted items don't come back |

- [ ] Check GitHub, F-Droid and Flathub for other projects called Forage before the first public release
