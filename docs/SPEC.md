# Forage — Project Spec

Nov 16, 2026 · @Jannis

Forage is your account-free home for YouTube and other platforms: everything an account gives you (likes, history, subscriptions, playlists, recommendations), with the data on your own devices. Recommendations come only from videos you like, and only when you ask. You leave Forage only to play a video, in whichever player you like (NewPipe, LibreTube, FreeTube, mpv or a browser).

Until November 2026 the spec called Forage a companion that "sits next to whatever client you already use and does not replace it". The plan for the broader app, with layout sketches, is the doc "Forage: where we're heading"; its decisions are under [Direction](#direction-an-account-free-home-nov-2026) below.

## Vision and principles

- **You own the data.** The profile, history and subscriptions live on your devices in open formats you can read, edit, export and delete. No account, no server, no telemetry.
- **Curated, not tracked.** Watching something does not change your taste. Only videos you like count, so autoplay and accidental clicks never shape the recommendations.
- **Pull, not push.** There's no recommendation feed on the start screen. Recommendations live on a Discover page you open on purpose.
- **Explainable.** Every recommendation shows why it was picked ("similar to 3 videos you like", "new channel near your woodworking cluster").
- **Works with any player.** The app finds and organizes videos. Playback happens in the client you already use.

## v1 feature set

v1 is the smallest version that answers the key question: are the recommendations good enough to use?

| Area | v1 scope | Later |
| --- | --- | --- |
| Import | Google Takeout (history, subscriptions, playlists); NewPipe and FreeTube exports | LibreTube, Invidious, Piped |
| Library | Liked, History, Playlists and Not interested; bulk like by channel, watch count or date range; playlists made, renamed and filled in Forage | Auto-suggest likely favourites from rewatches |
| Manual add | Paste a URL, search inside the app, Android share target | Browser extension |
| Taste profile | Your likes; viewed as topic clusters you can rename, pin or mute | Several profiles ("learning", "evening") |
| Discover | On-demand page: generate a batch, see the reason for each pick, then *like*, *not interested* or *save* | Mood or topic slider, "surprise me" mode |
| Subscriptions | Channel RSS feeds, a new-uploads inbox, optional notifications; Shorts hidden by default | Per-channel filters (minimum length, keywords) |
| Watch later | One queue across devices and players | Smart ordering by length |
| Notes and tags | Free-text notes and tags per video; tags also feed the profile | Search across notes |
| Playback hand-off | Open in NewPipe/LibreTube (Android intent), FreeTube, mpv or browser (desktop) | Embedded preview player |
| Sync | Encrypted per-device change logs, synced with Syncthing or any folder sync | Direct device-to-device sync |
| Export | Full data as JSON/SQLite; subscriptions back out to NewPipe/FreeTube formats | One-click client migration |

## How recommendations work

The profile is a set of video embeddings, not one average, so separate interests stay separate. Each Discover visit runs the pipeline below, using cached metadata where it can. The details below reflect what the M0 spike found to work on a real profile.

&#91;embedded content: recommendation pipeline · 6 stages, 1 feedback loop\]

- **Profile.** Each liked video becomes a vector built from its title, channel name, tags, your notes and the start of its description, in that order (the model reads only the first 128 tokens). Videos you mark "not interested" become negative examples.
- **Topics.** Average-linkage clustering merges videos until no two groups are similar enough (threshold 0.35). Groups of three or more are topics you can see, pin or mute; everything else goes into one "varied interests" group. A real profile is a few strong topics plus many one-off interests, and k-means forced those into mixed clusters. Topic labels come from the words that set a topic apart, never from channel names.
- **Candidates.** For a sample of profile videos from each topic (more from the varied group): related videos via the extractor, and one search per topic built from its top keywords. Channels that creators feature or collaborate with are planned but not built. A 150-video profile yields about 1,800 candidates. Candidates whose title is clearly in a language the profile doesn't contain are dropped, because the embedding model matches across languages.
- **Candidate text.** Candidates are scored on title and channel name only, since that is all a list result carries and fetching full metadata for every candidate is too slow. Profile videos use the full text.
- **Ranking.** Score = mean similarity to the three nearest profile videos, plus a bonus for channels you don't follow, plus a freshness bonus, minus a small penalty for candidates found only by search. Drop watched, saved, playlisted and rejected videos, then re-rank for diversity (MMR) with at most two picks per channel. Keep 3 of 20 slots for exploration: looser matches from channels you don't know.
- **Negative examples.** The "not interested" penalty applies only as far as a candidate is closer to a rejected video than to the profile. Subtracting the plain similarity sank whole subjects: rejecting three beginner cube tutorials removed every cube video.
- **Freshness.** Every recent upload gets a small bonus. It is larger where you save videos soon after release, measured per candidate from its nearest profile videos, so trailers and news favour new uploads while documentaries are left alone.
- **Feedback.** "Like" adds the video to your likes like anywhere else (until Nov 2026 "more like this" added it at half weight; those likes keep that weight), "not interested" adds a negative example, and "save" puts it in watch later.

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

## Direction: an account-free home (Nov 2026)

Forage grows from a recommendation companion into the place where you find, collect and organize videos across platforms without an account anywhere. The principles above stay. What changes:

- **A simpler model.** Your taste is your likes. Like, Not interested, Watch later, Playlists and History replace the library states *in profile*, *excluded* and *not for me*. Database version 10 migrates them: *in profile* becomes liked, *not for me* becomes not interested, and *excluded* becomes no decision (an excluded video was in the history or a playlist anyway, which keeps it out of recommendations).
- **Layout.** Four tabs: Home, Discover, Subscriptions, Library (a bottom bar on phones, the sidebar on desktop), and a video page behind every video with Play as the way out. Topics become "Your taste" in Library, with rename, pin and mute.
- **Search within reach.** On phones the search bar sits just above the tab bar on every tab, slides away while scrolling down and returns when scrolling up; in Library it searches your own videos. On desktop it stays at the top.
- **Liking without watching:** a like counts right away; the video's details are fetched in the background (up to 30 at once; more wait for "Fetch details") and the taste updates when they arrive.
- **History:** every video opened from Forage, plus imports. On by default, with "Pause history"; single entries or all of it can be deleted. The private vault keeps its own, encrypted history.
- **Importing subscriptions:** all of them automatically, then "Imported 143 channels" with a quick unfollow list.
- **Home without content:** a "Get started" card (import from Takeout, search, like a few videos); each step disappears once done.
- **Pictures of channels and performers:** YouTube avatars come with the channel pages Subscriptions loads anyway; performer pictures from the site's performer list (to confirm when subscriptions are built); a coloured initial otherwise.
- **Left out:** playing videos, comments and anything else needing an account, live streams, and an algorithmic home feed.
- **Name:** Forage stays.

Order of work, each step usable on its own:

1. **The plan.** Done, Nov 19, 2026.
2. **Likes, playlists and history.** **Done, Nov 19, 2026.** The library shows Liked, History, each playlist and Not interested. Playlists have a table of their own (they can be empty and renamed); videos are added from the history, likes or other playlists. Opened videos go into the history unless it's paused (Settings or the History list), which sorts by most recent or most watched. Search results and Discover picks are liked with one tap. On phones, holding a thumbnail starts its preview after 250 ms instead of the system's half second.
3. **The video page.** **Done, Nov 19, 2026.** Tapping a video anywhere (library, Discover, watch later, topics, search results, related videos) opens its page: the thumbnail (preview frames on hover or hold; a tap plays), title, length and age, the channel with Follow, Play, then Like, Not interested, Later and Playlist, where it already is (history, playlists), the description, tags and related videos. Details come from the cache, else they're fetched as the page opens. Related videos open their own pages; Back, Escape or the phone's back gesture go back one page, and a page opened from search returns to the search. In the library a click opens a video; selecting is by the round check or a long press, and while something is selected clicks add to the selection. Follow writes to the subscriptions the Subscriptions step will use. On the private platform, which names no uploader, each performer is listed and followed on their own (`pornhub:pornstar/first-last`, after the site's performer pages); Subscriptions can find their new videos by name in searches and the catalogue.
4. **New navigation and Home**, with the bottom search bar. **Done, Nov 19, 2026.** Four tabs: Home, Discover, Subscriptions, Library (a bottom bar on phones, the sidebar on desktop).
   - **Home:** "Get started" (import, search, like three videos; gone once done), Continue (watch later), the latest Discover batch (or an invitation to Discover), recently watched, and who you follow.
   - **Library** opens on an overview: cards for Liked, Watch later, Playlists and History, "Your taste" (the former Topics tab, with rename, pin and mute), Not interested, All videos and recently liked. Each card opens its list; Back (the link, Escape or the phone's back gesture) returns to the overview.
   - **Search:** on phones a bar just above the tabs, sliding away while scrolling down and back when scrolling up; on desktop a bar at the top of every tab but the library, which has its own. Outside the library it searches the platforms, with the results on their own page as big cards (tap the picture for the video's page, hold it for a preview; Play, Like, Later and Add to playlist under each) (videos open from there, and Back returns to them); in the library it filters the list on screen, or from the overview searches all videos. The "Add videos" dialog is down to adding by link.
   - **Subscriptions** lists who you follow, with Following/Follow to change it; their new uploads come in step 5.
   - **Playlists** open on a page of their own, laid out like the queue: the first video large (Play, Remove), the rest in rows, Rename and Delete. A playlist keeps an order of its own (database version 12 adds positions, starting from the order videos were added; imports keep the export's order): "Your order" is the default, with Recently added and Shortest besides, and Reorder puts a handle on each video to drag it elsewhere (Back or Done ends it). The playlists page shows each as a card with its first video's picture and how many videos it holds. The overview's cards all keep one height.
   - **Liked, History, Not interested and All videos** are pages of their own too: a title and a line about the list, the orders that fit it (Recent, Oldest, Most watched, Shortest), and only their own tools (Add by link on Liked; Pause and Clear on History; the liked/not liked filter on mixed lists; Fetch details where something lacks them). No tab row. Liked and History, where you mostly play videos or keep them, use the queue's big rows: Play, Add to playlist, and Unlike or Remove from history, with when it was liked or last watched. Not interested and All videos stay lists to select several from (by their circles or a long press).
   - **YouTube's Watch later** (Takeout exports it as a playlist, named in the export's language) goes into Forage's watch-later queue, on import and, through migration 10.sqm (database version 11), for exports already imported. The queue and playlists fetch details for imported videos without titles, 200 at a time.
   - The Android launcher icon is adaptive now (gradient background, sparkle foreground, a single-colour layer for themed icons), so launchers no longer put it on a white disc.
5. **Subscriptions**, with pictures and a new-uploads inbox (milestone M2's inbox). **Done, Nov 20, 2026.**
   - **Uploads:** each followed channel's latest uploads are kept locally (its newest 30; database version 13). YouTube: the channel page for its name and picture, then its Videos tab, which has lengths and leaves Shorts out. The private platform: its API's performer filter sorted by newest, keeping only videos that list the performer, else a name search filtered the same way (not yet tried against the site: this development machine can't reach it).
   - **When:** opening Home or Subscriptions checks the channels not checked in the last half hour, one after another with a short pause, in the background; Refresh checks all. A channel the platform has removed is marked gone and asked again weekly.
   - **New:** an upload found after the subscriptions were last visited (not by its upload date, which YouTube gives roughly). A channel's first check only fills its list, so a whole back catalogue never arrives as new. Leaving the tab marks everything seen.
   - **Screens:** Subscriptions lists everyone's uploads newest first, with no ranking, split into New and Earlier; each with Play, Add to playlist and Save for later. The people along the top, with a dot for new uploads, narrow the list to one. Manage lists who you follow, the gone ones first, to unfollow (or follow again). Home shows the newest uploads in a row; the tab shows how many are new.
   - **Pictures:** YouTube avatars from the channel page, on Subscriptions, Home and video pages; for channels nobody follows, a video page looks the picture up (remembered for a week; database version 14). Performers' pictures come from their profile pages on the site (the page's preview image, else its avatar; tried as pornstar, then model, then channel pages), looked up after a feed check for those followed and for video pages, remembered for a week. Not tried against the site yet: this development machine can't reach it.
   - **Missing pictures:** a picture found is kept for a week, "none" for a day; one that didn't download is tried again after a short rest; Refresh looks up every missing one again and retries those that didn't load. Manage says why someone shows a letter (no profile page found, no picture on it, the site or the image server refused), to tell what to fix.
   - **Performers in tags:** a video page lists a performer you follow whose name is only among its tags (amateur models often are), with their Following button, and library rows show the name. Only people you follow count, since tags hold far more than names.
   - **Not interested** on an upload also takes it out of the subscriptions: the way to drop someone else's video that only carries a performer's name as a tag.
   - **Order:** the people along the top and in Manage follow a chosen order: by name, by latest upload, or most recently followed (kept in settings).
   - **Performers the API doesn't list** (amateur models are often only in their videos' tags): after the performer filter, a name search by newest and by relevance, keeping videos that list the performer or carry their name as a tag.
   - Lists keep their scroll position while a video page is in front, and start at the top when their order or filter changes.

Sync (M4) follows after these.

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
   - **Moved ahead of M2 (Nov 2026)**, since the user watches mostly on the phone. Stage 1 built: the YouTube side on Android. Stage 2, the private vault and the private platform, comes next.
   - How it's shared: the model behind the screens (`AppModel`) and the screens live in `app/src/main/kotlin`, compiled by both the desktop and the Android module. What differs goes through a `Platform` interface (images, models, players, storage, files); desktop-only UI (tooltips, scrollbars) comes in through `LocalExtras`. Below 640 dp the app is compact: profile and settings at the top, bottom navigation, tighter margins, wrapped or swipeable rows, full-width dialogs, stacked buttons where labels would wrap, and phone layouts for the watch later queue, search results and topic strips. A `phoneScreenshots` task renders the screens at phone size.
   - Porting work: the tokenizer is plain Kotlin now (identical to the reference on 2,607 texts); every request uses `HttpURLConnection` instead of `java.net.http`; ONNX Runtime is per app (desktop jar, Android AAR).
   - Android specifics: the 8-bit model (118 MB, downloaded on first use; 0.994 same-text cosine to the full model, 8.9 of 10 nearest titles the same), Android's on-device text classifier for language detection (untested on titles so far), links opened by the system in the user's default app, "Share → Forage" adding the shared link without opening the app, and Import taking a Google Takeout zip as a phone downloads it (read without unpacking; the desktop accepts the zip too), or a `forage.db` copied from the desktop, which asks before replacing a library that isn't empty (until sync makes that unnecessary). Android 15+ (API 35, for Java 21's library methods), ARM64 only, about 60 MB. Compose on Android is 1.9 (Material 3 1.4), since Compose 1.12 needs Android Gradle plugin 9.1 and with it Gradle 9.
   - **Stage 2 built (Nov 2026): the private vault and the private platform on Android.** The desktop's in-memory vault needs sqlite-jdbc's serialize, which has no Android build, so Android's vault is SQLCipher: the database file encrypted page by page (never on disk in the clear either), with the key and hidden file name derived from the PIN exactly as on the desktop (`PrivateVaults.derived`, `.cipher` files). Vault files differ between platforms; sync is to exchange change logs, not files. The platform opens vaults (`Platform.openPrivateVault`). The catalogue runs on a small `SqlFile` interface, JDBC on the desktop and Android's SQLite on the phone, reading large blobs in 1 MB pieces (Android's cursor rows stop at 2 MB). Importing the dump takes a stream (a phone's picker gives streams), holds less in memory (a tag's video list starts with its second video; no second spelling per tag), and reports running out of memory with the way around it: choosing a `pornhub.db` made on a computer, which Settings takes over as it is. Shared private-platform links go to the private vault while it's unlocked. On a phone, touching and holding a thumbnail plays its preview frames.
   - Release builds are signed with a key the user keeps (`FORAGE_KEYSTORE`, `FORAGE_KEYSTORE_PASSWORD`); it's never in the repository, which is public.
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
- **Platform.** The vault offers YouTube and the first sensitive platform (below); the normal vault offers YouTube only.

The first sensitive platform plugin as built (Nov 2026), module `source-pornhub`:

- **API.** The HubTraffic API lives on under the site itself (`/webmasters/search`, `/webmasters/video_by_id`): JSON, no account or key. hubtraffic.com no longer resolves; whether the terms at hubtraffic.net still apply, and what they allow for automated use, is unverified and up to the user to check.
- **Metadata.** Title, tags, categories, performers, duration and publish date. Categories and tags go into the profile as tags; the performers stand in for the creator, since the API names no uploader. There is no description or language.
- **Topics by tags.** Nearly every video shares the site's generic tags, so the text model saw them all as alike and put almost everything in one topic. For platforms with descriptive tags (`Capability.DESCRIPTIVE_TAGS`), two of its videos count as similar by 20% embedding, 68% TF-IDF tag similarity across the profile and a fixed 12% for coming from such a site, which lowers the bar for grouping them without changing it for other platforms in the same vault (a first version without that part left 7 of 13 videos ungrouped). Tags on every video, or on one only, weigh nothing. Topics are named after whole tags, never after names or tags on more than half the profile.
- **Candidates without related videos.** The API has none, so the candidate generator routes searches by platform and, for a platform without related videos, runs two keyword searches per topic plus searches by the tags of a few sampled profile videos (including from the varied group), which stand in for their related videos. Those use each video's most telling tags: ones shared with a few other profile videos, not the generic ones. Mixed vaults search each topic on the platform most of its videos come from.
- **Thumbnails.** The links are signed and expire after about a day, so they are kept for a few hours and looked up again after that, with one retry when the site asks Forage to slow down; memory only, like the rest of the vault. A thumbnail that fails is tried again after a few minutes. Each video offers several images (thumbnail, alternate, preview frames); they are tried in order, skipping image servers that refused one, at most five requests per video. When a server refuses, the links are looked up again, up to three times, until they point at another server: the API hands out links on different image servers from one answer to the next, and only one server refuses. Network errors and busy servers get one retry, and Settings lists why images didn't load in this session. Cards whose images were refused try again by themselves every 20 seconds or so, three times, while they're on screen.
- **Refused images.** One of the site's image servers (pix-cdn77) refuses images to requests that don't come from the site, its hotlink protection. Forage doesn't pretend to be the site to get around that; cards say "Preview only on the site" instead. Sending the site as referrer stays open. The user found no terms covering the API; the site does invite anyone to embed all its videos, and publishes an embed dump (a CSV with video and thumbnail URLs, tags, duration, title and embed codes) for other sites to import.
- **Region-aware.** HTTP 403/451 and age-verification pages instead of JSON are reported as blocked, with an explanation, rather than as missing videos.
- **Playback** in the browser. FreeTube and mpv stay YouTube-only.
- **Starting a profile.** There is still no history export. The "Add videos" dialog searches any platform the vault offers (YouTube, and in the private vault the private platform) and adds results with one click, fetching their details in the background; it also takes several pasted links at once. "More like this" on picks grows the profile from there.
- **Catalogue.** The site publishes an embed dump for other sites to import: a 1.6 GB zip holding a 19.6 GB file of about five million lines, `|`-separated: embed code, thumbnail, preview frames (`;`), title, tags (`;`), categories (`;`), performers (`;`), duration in seconds, views, likes, dislikes, larger preview frames (`;`), and in the real file one more field after those, 13 in all (unidentified; the first import, which counted positions from the end, read every line one field off). The reader now finds the four counts as the first run of four whole numbers after the performers and places the rest around them; the import reports the layout it saw, and an index built by the first reader is marked outdated and ignored until imported again. The dump has no upload date, but thumbnail addresses hold the day the video was processed (`…/videos/202510/05/…`), which Forage reads as the upload date for catalogue videos, and for API results without one. Settings in the private vault imports it straight from the zip into `catalogues/pornhub.db` in the data folder, unencrypted by the user's choice since it holds only the public catalogue (it shows that Forage has it, nothing about the user). Videos with under 1,000 views are left out. Each tag keeps a delta-encoded list of its videos, so matching reads a few dozen lists: per topic, the profile's tags are weighted by rarity in the catalogue, every video is scored by the tags it shares (normalized by its tag count, nudged by views and rating), and the best 80 per topic (160 pinned, 20 per sampled video of the varied group) join the candidates. Keyword searches stay, since the dump is a snapshot; per-video tag searches give way to the catalogue. Previews use the catalogue's thumbnail first. Measured on 500,000 synthetic videos: import 8 s, matching 16–33 ms.
- **Getting the dump.** Settings offers "Download and import": one click downloads the zip from the site (resuming where a broken-off download stopped), imports it and deletes it. Not automatic, since it is 1.6 GB to download and 1.3 GB on disk. "Choose a downloaded file…" imports a zip the user already has. A refusal or an age-verification page is reported, not saved.
- **Hover previews.** The site's hover clips aren't in the API or the dump, but each video's 16 preview frames are: resting the pointer on a card cycles through them, with a bar showing where in the video each frame is. For catalogue videos the frame addresses are worked out from the thumbnail's (they differ only in their number), falling back to the API's list if those don't load.
- **First rated batch** (Nov 2026, 13-video profile, catalogue plus searches): 10 of 20 the user would watch and 3 maybes, including all of the top four; the misses sat in the lower half. For comparison, YouTube's third M0 round had 14 of 20 from a 150-video profile.
- **Distribution.** The desktop package includes the plugin but offers it only inside the private vault. A separate distribution matters for app stores (Android, later), not for the self-installed desktop package.

Plan (followed): design for it from M1 (the vault boundary), build the first sensitive plugin after M1's core loop works, as a separate module. There is no known history export to import from, so such a profile starts from pasted URLs.

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
| Project name | Decided: **Forage** (other candidates were Palate, Glean, Sift); confirmed for the broader app in Nov 2026 | Forage = you go looking when you want something new, nothing is pushed; not tied to YouTube. Check GitHub, F-Droid and app stores for clashes |
| Transcripts | Not in v1. Test them in M0 on videos with weak picks; add later only if they clearly help | Titles, descriptions, tags and notes are cheap and usually enough; transcripts are slow, often missing and noisy when auto-generated |
| Shorts | Hidden by default in Discover and the subscriptions inbox, with a setting to show them. Shorts you add to the profile on purpose still count | Little text for the profile; endless swiping goes against the app's purpose. Detect by `/shorts/` URL or extractor flag, not duration (Shorts can be up to 3 minutes). **Open problem:** Takeout lists Shorts with normal watch URLs and the extractor did not flag them either, so Shorts in the history go undetected |
| Clustering | Average-linkage clustering with a similarity threshold, plus a "varied interests" group | k-means needs the number of clusters up front and produced mixed clusters on a real profile |
| Profile storage in M0 | A hand-edited text file of video IDs | Enough for the spike; M1 moves library, profile and feedback into the database |
| Sync conflicts | Each device writes only its own encrypted change log (`phone.log`, `laptop.log`) in the synced folder. Merge = replay all logs in time order: latest change wins per field; sets (profile, tags, watch later) use separate add/remove events; deletions leave a tombstone | No Syncthing conflict copies, no device silently overwriting another, deleted items don't come back |

- [ ] Check GitHub, F-Droid and Flathub for other projects called Forage before the first public release
