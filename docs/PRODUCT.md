# Bosunarr — product strategy

> Status: draft for the owner, 11 Nov 2026. Opinionated on purpose. Prices and policies were checked on
> this date and change often, so re-check the linked sources before you act on a number.

## TL;DR

1. **Positioning:** *the open-source, no-server, Material You manager for your whole \*arr stack on Android.*
   nzb360 is the only full-stack Android rival. It is closed source and charges for Pro. The iOS side is
   crowded (Helmarr, Ruddarr, YaArr, Dashboarr and others). LunaSea, the old cross-platform open-source
   option, was archived in April 2025. **That leaves no polished, open-source, full-stack manager on Android**,
   and Bosunarr can be it.
2. **Biggest gaps** compared with what paying users expect: **Usenet clients (SABnzbd, NZBGet)**,
   **notifications**, **home-screen widgets**, **interactive (manual) release search**, **Plex + Tautulli**,
   **Lidarr**, and a **Play Store build**.
3. **Monetisation:** free core plus a **one-time "Bosunarr Pro" unlock at US$9.99**, with regional pricing
   (about ₹349 in India). Sell it through Google Play Billing in the Play build and through signed offline
   license keys in the official GitHub build. Anyone who compiles the source gets everything, as AGPL
   requires. Protect the **name and logo with a trademark policy**, not the code. A **subscription** comes
   later and only covers what has a recurring cost: a hosted push relay.
4. **Play Store:** you need a `play` flavor that drops `REQUEST_INSTALL_PACKAGES` and the self-updater and
   excludes **Whisparr**. You also need a privacy policy, a Data safety form, demo mode for the reviewer,
   and (on a personal account) a **12-tester, 14-day closed test**. Start that clock this week; it is the
   critical path.
5. **Next 2 weeks:** Play flavor and closed test, Seerr rename, SABnzbd and NZBGet, encrypted config export
   and biometric lock, Pro entitlement plumbing. **Next 2 months:** notifications (polling + ntfy with no
   server), Glance widgets, interactive search, Lidarr, Plex + Tautulli, Transmission and Deluge, tablet
   layouts, Unraid.

---

## 0. Where Bosunarr stands today

From `android/app/src/main/java/app/bosunarr/data/ServiceType.kt` and `feature/**`:

| Area | Today |
|---|---|
| Services (11) | Sonarr, Radarr (multi-instance, e.g. 4K), Whisparr v3, Prowlarr, Bazarr, Jellyseerr, Overseerr, qBittorrent, Jellyfin, Jellystat, Wizarr |
| Screens | Dashboard; Library (unified, detail, add, wanted/cutoff); Discover + Requests (Seerr API); Calendar; Activity (\*arr queue + torrents, magnet share target); a screen per service |
| Platform | Native Kotlin/Compose, Material You (dynamic color), onboarding, password-manager autofill, primary + `altUrl` fallback per service, encrypted on-device storage (device-bound key, excluded from backup), in-app updates from GitHub Releases, AGPL-3.0, no server |
| In progress | Demo mode, SSH server management (stats + Docker), UX polish, website redesign |
| **Not there yet** | Usenet clients, other torrent clients, Plex/Emby, Lidarr, **interactive search** (no `/api/v3/release` calls), notifications, widgets, backup/restore, biometric lock, custom headers / basic auth for reverse proxies, tablet layouts, localisation, Play build |

**What already sets it apart:** a Jellyfin-first stack (Jellystat and Wizarr are rare in other apps), a
modern Material You UI, no account and no cloud, and open source.

---

## 1. Competitive analysis

### 1.1 The field

| App | Platforms | Price model | Standout | Weaknesses | Status |
|---|---|---|---|---|---|
| **nzb360** | Android only | Free + Pro unlock. Historically one-time at about **US$12** ($8.40 on sale per the developer, Nov 2024; users reported $13 in Jan 2025). Recent release notes mention "feature credits". Check the live Play price. | 21 services: SAB, NZBGet, Deluge, Transmission, µTorrent, qBit, rTorrent; Sonarr/Radarr/Lidarr/Chaptarr/Bazarr/Seerr; Prowlarr/NZBHydra2/Jackett/Newznab; **Unraid** (Docker, array health), Tautulli, **Tracearr**. Card dashboard with 30+ cards, universal calendar, auto/manual search, **Wake-on-LAN**, LAN/remote auto-switch, custom headers, push with deep links. About 800k downloads, 4.9★, since 2012. | Closed source; no iOS; ageing design language; no Jellyfin-native screens (it relies on Tracearr/Tautulli); it was once pulled from Play (reason never made public). | Very active (v24.x, Jul 2026) |
| **Helmarr** | iOS/iPadOS 26+ | Free core; **Pro $3.99/mo**, **Ultra $4.99/mo**, **Lifetime $34.99**, or **$4.99 per service** | About 25 services incl. Plex, Emby, Tautulli, Unraid (Docker/VMs), **SSH/SFTP terminal**, Tdarr, rTorrent, SAB/NZBGet, Wizarr, Jellystat, Tracearr. **Widgets**, push (sets up webhooks automatically), Siri/Shortcuts, iPad layout, release browser, LAN/WAN fallback | Confusing tiers; iOS only; closed | Very active (2.5.x) |
| **Ruddarr** | iOS/iPadOS/Mac/visionOS | Free, MIT; **Ruddarr+ subscription (about $5.99)** for push only | Best-rated iPhone \*arr app (4.87★, 765 US ratings); polished SwiftUI; Shortcuts, iCloud sync; open-source push relay on a Cloudflare Worker | Sonarr + Radarr **only**; no download clients, requests or Android | Active (2.1) |
| **Arrcade** | iOS, Android, web | Free; Pro **£5.99 one-time** | One account across every platform; 13 services; TMDB discovery; push in Pro | Closed source; no widgets or Shortcuts; web needs a public address; the comparison article that ranks it is written by Arrcade itself | New (2026) |
| **Arrmate** | Android APK, iOS (AltStore), web, desktop | Free, MIT (Flutter) | **ntfy push with automatic webhook setup** in Sonarr/Radarr, interactive search, qBit; self-updating APK, the same idea as Bosunarr | Tiny (3★ on GitHub, created Jan 2026); Radarr + Sonarr + qBit only | New |
| **JellyWatch** | Android (Play) + web | Free Jellyfin admin; **Premium subscription** for Radarr/Sonarr/Jellyseerr | Jellyfin admin first (sessions, users, tasks), push, multiple instances | Thin \*arr coverage; the \*arr side is behind a paywall | New (2026) |
| **YaArr** | iOS | Free; $1.99 to remove ads | 17 services incl. download clients, Shortcuts | Few ratings, short track record | New |
| **Dashboarr** | iOS 15.1+ | Free, GPL-3.0 | Runs on older iPhones; 9 services | qBit only; push needs a self-hosted backend | Active |
| **Pocket for Seerr / Seerr mobile** | iOS / iOS + Android via GitHub | Free | Requests and approvals only | Single purpose | Active |
| **LunaSea** | Android, iOS, desktop (Flutter) | Was free; cloud push | Was *the* open-source all-in-one | **Archived 3 Apr 2025**, pulled from both stores, push servers shut down. The successor app was cancelled because it needed store monetisation. | Dead. The "Zagreus" iOS fork never shipped. |
| **Transdroid** | Android (Play, F-Droid) | Free, GPL | Remote control for about 20 torrent and Usenet clients, widgets | Download clients only; dated UI; no \*arr | Maintained |
| **Findroid / Jellyfin for Android** | Android | Free, GPL | Jellyfin **playback**, offline downloads | Players, not managers. They are *complements*: deep-link to them for "Play". | Active |
| **Notifiarr** | Web service + Go client (not a phone app) | Free tier; Patron (one-time) / Subscriber (monthly) | Rich Discord notifications for \*arr/Plex, TRaSH sync | Discord-centric; needs an account and its cloud; some users complain about features moving behind the paywall | Active |

Lessons for Bosunarr:

- **LunaSea's death is the warning.** A free app whose cloud push you run yourself is unsustainable. Its
  successor died because there was no revenue model. Charge from day one, and keep recurring costs at zero
  unless they are paid for.
- **Helmarr proves iOS users will pay $35 lifetime or $4/month** for breadth, widgets and push. **nzb360
  proves Android users pay about $12 one-time** for breadth, and has done so for 14 years.
- **Ruddarr and Arrmate prove "open source + paid push" works** and that auto-configuring \*arr webhooks is
  now the expected UX.
- **Nobody on Android combines open source, a modern UI and breadth.** That is the gap.

### 1.2 Feature matrix (✅ yes · ◐ partial/limited · ❌ no · ? unverified)

| Feature | **Bosunarr** | nzb360 | Helmarr | Ruddarr | Arrcade | Arrmate | JellyWatch |
|---|---|---|---|---|---|---|---|
| Android | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ (APK) | ✅ |
| Open source | ✅ AGPL | ❌ | ❌ | ✅ MIT | ❌ | ✅ MIT | ❌ |
| No account / no vendor cloud | ✅ | ✅ | ◐ (push) | ◐ (push) | ❌ (account) | ✅ (ntfy) | ? |
| Sonarr / Radarr | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ◐ Premium |
| Multiple instances (4K) | ✅ | ✅ | ✅ | ✅ | ? | ? | ✅ |
| Lidarr | ❌ | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| Prowlarr | ✅ | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| Bazarr | ✅ | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| Seerr requests + approve | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ◐ Premium |
| qBittorrent | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| Transmission / Deluge / rTorrent | ❌ | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| **SABnzbd / NZBGet** | ❌ | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| Jellyfin | ✅ | ◐ (via Tracearr) | ◐ | ❌ | ? | ❌ | ✅ deep |
| Plex / Tautulli | ❌ | ◐ Tautulli | ✅ | ❌ | ? | ❌ | ❌ |
| Jellystat / Wizarr | ✅ / ✅ | ❌ / ❌ | ✅ / ✅ | ❌ | ? | ❌ | ❌ |
| Unified calendar | ✅ | ✅ | ✅ | ✅ | ? | ✅ | ◐ |
| **Interactive (manual) search** | ❌ | ✅ | ✅ | ✅ | ? | ✅ | ❌ |
| **Push / notifications** | ❌ | ✅ | ✅ | ✅ paid | ✅ Pro | ✅ ntfy | ✅ |
| **Home-screen widgets** | ❌ | ◐ legacy | ✅ | ? | ❌ | ? | ? |
| Unraid / Docker | 🔜 SSH | ✅ | ✅ | ❌ | ? | ❌ | ❌ |
| SSH terminal / stats | 🔜 | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Wake-on-LAN | ❌ | ✅ | ? | ❌ | ? | ❌ | ❌ |
| LAN/remote URL fallback | ✅ (`altUrl`) | ✅ | ✅ | ? | ? | ? | ? |
| Custom headers / basic auth | ❌ | ✅ | ✅ | ? | ? | ? | ? |
| Tablet layout | ❌ | ? | ✅ | ✅ | ? | ✅ | ? |
| Material You / dynamic color | ✅ | ◐ | n/a | n/a | ? | ◐ | ? |
| Price (Pro) | — | ~$12 once | $34.99 once / $3.99 mo | ~$5.99 sub (push) | £5.99 once | free | sub |

Sources: [nzb360.com](https://nzb360.com/), [nzb360 price (developer, r/usenet)](https://reddit.birdcat.cafe/r/usenet/comments/1h1caoe/nzb360_black_friday_sale_30_off),
[nzb360 changelogs (APKMirror)](https://www.apkmirror.com/apk/kevin-foreman/nzb360/nzb360-media-server-manager-24-1-release/),
[nzb360 Play removal thread](https://forums.sabnzbd.org/viewtopic.php?p=92338),
[Helmarr App Store](https://apps.apple.com/us/app/helmarr/id1638624921),
[Ruddarr GitHub](https://github.com/ruddarr/app) / [App Store](https://apps.apple.com/app/ruddarr/id6476240130),
[Arrcade comparison, Oct 2026 (vendor-written)](https://arrcade.co.uk/blog/best-cross-platform-sonarr-radarr-app),
[Arrmate](https://github.com/lucasliet/arrmate),
[JellyWatch](https://jellywatch.app/blog/manage-radarr-sonarr-jellyseerr-android-jellywatch),
[LunaSea shutdown](https://reddit.birdcat.cafe/r/LunaSeaApp/comments/1jq008t/website_update_thank_you_good_bye/ml4wdvg) / [archived repo](https://github.com/JagandeepBrar/lunasea),
[Transdroid](https://github.com/erickok/transdroid), [Findroid](https://github.com/jarnedemeulemeester/findroid),
[Notifiarr sponsors](https://github.com/sponsors/Notifiarr).

### 1.3 Ecosystem changes that affect Bosunarr

- **Overseerr and Jellyseerr merged into Seerr** (v3.0.0, 14 Feb 2026); `sct/overseerr` is now archived.
  Show a single **"Seerr"** service type (keep the old ids as aliases) and update copy and logos.
  ([Seerr blog](https://docs.seerr.dev/blog/seerr-release/))
- **Readarr is retired** (archived 27 Jun 2025, its metadata source is dead). Don't build for it.
  Watch **Chaptarr**, which nzb360 already supports. ([Readarr repo](https://github.com/Readarr/Readarr))
- **Huntarr imploded** (Feb 2026: an alleged unauthenticated API-key leak; repo, subreddit and Discord went
  offline). **Do not integrate it.** That episode is a selling point for Bosunarr's "keys never leave your
  phone" stance.
  ([ElfHosted](https://store.elfhosted.com/blog/2026/02/24/huntarr-ends-its-hunt-newtarr-takes-it-up/),
  [PiunikaWeb](https://piunikaweb.com/2026/02/24/huntarr-security-vulnerability-arr-api-keys-exposed/))
- **Tracearr** (about 2.7k★, stream monitoring for Plex/Jellyfin/Emby) was added by both nzb360 and Helmarr
  in 2026. It is a rising star.
- **Unraid 7.2 ships an official GraphQL API with API keys**, so Unraid support no longer needs a plugin.
  ([Unraid API docs](https://docs.unraid.net/API/))

---

## 2. Missing integrations, ranked

**Demand signals:** GitHub stars (fetched 11 Nov 2026) and **linuxserver.io Docker pulls**, which is the
best proxy for "\*arr users actually run this". For reference, the linuxserver images of Radarr and Sonarr
have about **2.4B** pulls each.

| # | Integration | Demand | What users would do in Bosunarr | API / auth | Effort | Priority |
|---|---|---|---|---|---|---|
| 1 | **Seerr** (rename) | 13.2k★ | Same as today. It is a branding and compatibility fix. | Same API, API key | S | **P0** |
| 2 | **SABnzbd** | 3.2k★, **721M** pulls | Queue/history, pause/resume, speed limit, priority, retry failed, add NZB via share/URL | `GET /api?mode=…&apikey=&output=json` | S | **P0** |
| 3 | **NZBGet** (nzbgetcom fork) | **810M** pulls | Same as SAB | JSON-RPC over HTTP, basic auth (user/pass) | S | **P0** |
| 4 | **Lidarr** | 5.8k★, 570M pulls | Artists/albums library, add, wanted, queue, calendar | \*arr `api/v1`, API key; reuses Sonarr/Radarr code | M (music UI) | **P0** |
| 5 | **Plex** | 910M pulls (linuxserver alone) | Now playing, kill stream, libraries, scan library, recently added | X-Plex-Token from the PIN flow on plex.tv (`plex.tv/link`). Users authorise on Plex's own site; never type a Plex password into the app. | M | **P1** |
| 6 | **Tautulli** | 6.6k★, **991M** pulls | Watch stats, history, top users and titles, Plex streams | `/api/v2?apikey=&cmd=` | S | **P1** |
| 7 | **Transmission** | 15.4k★, 538M pulls | Same feature set as the qBit screen | JSON-RPC, `X-Transmission-Session-Id` handshake, basic auth | S–M | **P1** |
| 8 | **Deluge** | 333M pulls | Same | Web JSON-RPC `/json`, password login → session cookie | M | **P1** |
| 9 | **Unraid** | (nzb360 and Helmarr both have it) | Array/parity/disk health, Docker and VM start/stop, notifications | GraphQL `/graphql`, `x-api-key` (7.2+) | M | **P1** |
| 10 | **Emby** | 46M pulls | Same as the Jellyfin screen | Jellyfin's ancestor; near-identical REST, API key | S (after Jellyfin) | **P1** |
| 11 | **Jackett / NZBHydra2** | 16.4k★, 1.58B pulls / 528M (hydra2 + nzbhydra2) | Indexer search and status for people who never moved to Prowlarr | Torznab/Newznab + API key | S–M | P2 |
| 12 | **Tracearr** | 2.7k★, new | Streams, history and account-sharing detection | REST, API key | S–M | P2 |
| 13 | **Gluetun** | 16k★ | "VPN up? Public IP? Forwarded port?" card. Restart VPN. | Control server HTTP API (`:8000`), API key or basic auth in recent versions | S | P2 |
| 14 | **rTorrent / ruTorrent** | 154M pulls | Torrent control | XML-RPC (or ruTorrent `httprpc`), basic auth | M–L | P2 |
| 15 | **Portainer** | 38.8k★ | Containers: start/stop/restart/logs, stacks | REST, `X-API-Key` | M (overlaps the SSH-Docker work) | P2 |
| 16 | **Cleanuparr** | 2.7k★ | Stalled/slow/strike lists, run a job now | REST (+ API key) | S–M | P2 |
| 17 | **Maintainerr** | 2.4k★ | "Leaving soon" collections, rules, exclude a title | REST | M | P2 |
| 18 | **Autobrr** | 3.2k★ | IRC announce feed, filters on/off, recent pushes | REST, `X-API-Token` | M | P2 |
| 19 | **Tdarr** | 4.4k★ | Transcode queue, node status, space saved | REST, optional API key | S–M | P2 |
| 20 | **Audiobookshelf** | 15k★ | Libraries, recently added, scan, users (fills the Readarr gap) | REST, Bearer token | M | P2 |
| 21 | **TrueNAS** | — | Pool health, apps | JSON-RPC over WebSocket (25.04+), API key | M–L | P3 |
| 22 | **Mylar3 / Kapowarr** | 1.5k★ (15.8M pulls) / 1.1k★ | Comics wanted/queue | Mylar `/api?cmd=&apikey=`; Kapowarr REST | M | P3 |
| 23 | **Kavita / Komga** | 12.1k★ / 6.8k★ | Library stats, scan | REST (API key / basic) | M | P3 |
| 24 | **Navidrome** | 24.6k★ | Now playing, scan | Subsonic API | S | P3 (players already exist) |
| 25 | **Notifiarr** | 0.9k★ | Treat it as a *notification source*, not a screen | Client API key | S | P3 |
| 26 | **Aria2** | 43k★ (general purpose, not \*arr) | Download control | JSON-RPC + secret token | S | P3 |
| 27 | **Chaptarr** | (nzb360 has it) | Books | \*arr-style | M | Watch |
| — | **Homepage / Homarr** | 33.6k★ / 5.3k★ | **Don't integrate. *Import from* them:** parse a pasted Homepage `services.yaml` to pre-fill URLs and keys during onboarding. | YAML | S | P2 (onboarding win) |
| — | **Unpackerr, Recyclarr, Kometa** | 1.5k / 2.2k / 3.5k★ | Little to do: CLIs and daemons with no meaningful API. At most "last run OK" via logs over SSH. | — | — | Skip |
| — | **Huntarr** | dead, insecure | — | — | — | **Never** |
| — | **Immich** | 119k★ | Off-topic (photos). At most a storage/health card. | REST, API key | S | Skip for now |
| — | **Readarr** | retired | — | — | — | **Never** |

**Takeaway:** the next three integrations (SABnzbd, NZBGet, Lidarr) open Bosunarr to the **whole Usenet half
of the \*arr world and music users** for small effort. Plex + Tautulli then makes Bosunarr relevant to the
largest media-server user base. Today it is invisible to Plex users.

Pulls: [linuxserver Docker Hub](https://hub.docker.com/u/linuxserver) (queried via the API, ordered by
pull count); stars via the GitHub API.

---

## 3. Missing features users expect from a paid app

### 3.1 Notifications without running a server (most-requested feature in this category)

Three layers, built in this order:

| Layer | How | Pros | Cons | Tier |
|---|---|---|---|---|
| **A. Background polling** | `WorkManager` periodic work (minimum interval 15 min; Doze can defer it). Diff the queue, history, health and pending Seerr requests against the last snapshot, then post local notifications. | Zero infrastructure. Works on LAN, Tailscale or any URL the phone can reach. | Not real-time. Silent when the phone can't reach the server. Battery-budgeted. | Free (basic: health + finished downloads). Pro: every event type plus quiet hours. |
| **B. ntfy / UnifiedPush, auto-configured** | One tap: Bosunarr creates an **ntfy connection** in Sonarr/Radarr/Prowlarr (supported natively under *Settings → Connect*) and in **Seerr** (has an ntfy agent) via their APIs. The topic is a random 128-bit name on the user's ntfy server or ntfy.sh. Delivery uses the **ntfy app as UnifiedPush distributor**, or Bosunarr subscribes itself. | Real-time. Still no Bosunarr server. Private if the ntfy server is self-hosted. Arrmate and Helmarr show this auto-setup is now table stakes. | Needs the ntfy app or a self-hosted ntfy. The UnifiedPush payload limit is about 4 KB, so send a compact message and have Bosunarr fetch details. | **Pro** |
| **C. Hosted "Bosunarr Push" relay** (later) | A stateless Cloudflare Worker (copy Ruddarr's open-source design) receives \*arr webhooks and forwards them via FCM. The FCM part is Play build only. | Zero setup for non-technical users. Works when the phone is off-LAN. | Recurring cost, a privacy story to tell (titles pass through the relay), and Data safety declarations. | **Subscription** (the only one) |

Ship A and B before C. They match the "no server" promise and cost nothing to run.
([ntfy \*arr examples](https://docs.ntfy.sh/examples), [Seerr ntfy agent](https://docs.seerr.dev/using-seerr/notifications/ntfy/),
[Ruddarr+ rationale](https://apps.apple.com/app/ruddarr/id6476240130))

### 3.2 Everything else

| Feature | Notes | Effort | Tier |
|---|---|---|---|
| **Interactive search (manual release pick)** | `GET /api/v3/release?episodeId=` / `movieId=` → list with quality, size, age, seeders, rejections, custom-format score → `POST /api/v3/release`. A core power-user action that all serious competitors have. | M | **Free** (core \*arr functionality; gating it would feel petty) |
| **Season pass / bulk monitor** | Monitor/unmonitor seasons and episodes in bulk, plus "monitor future only" | S–M | Free |
| **Home-screen widgets (Jetpack Glance)** | Download queue (with live speed), Up next/calendar, Server health, Pending requests. Refresh via WorkManager. | M | **Pro** (Helmarr's headline feature) |
| **Quick Settings tile** | Toggle qBit alt-speed limits, pause all downloads, show health | S | Pro |
| **App shortcuts (static + dynamic)** | Search library, Add movie, Activity, Requests | S | Free |
| **Encrypted config backup/restore** | Export every service (URLs, keys) to a file encrypted with a user passphrase (Argon2id/scrypt KDF + AES-GCM via the platform or Tink). Import on a new phone or a QR for phone-to-phone transfer. Required, because today's device-bound key means a lost phone means re-entering every API key. | M | **Export/import free** (it builds trust). Automatic scheduled backups to a SAF folder are Pro. |
| **Biometric / device-credential lock** | `BiometricPrompt` on launch and after N minutes in the background; `FLAG_SECURE` option. These are API keys to someone's server. | S | Free |
| **Multiple profiles / servers** | e.g. "Home" and "Parents' house" stacks, each with its own dashboard; quick switcher | M | Pro |
| **Custom headers, basic auth, client certificates** | Many people sit behind Authelia, Authentik, Cloudflare Access or Caddy basic auth. nzb360 and Helmarr support headers. | S–M | Free (it is a connectivity bug for these users, not a feature) |
| **Tablet / foldable layouts** | `material3-adaptive` list-detail and supporting-pane layouts; Play gives large-screen-ready apps better placement. | M | Free |
| **Statistics** | Library size growth, quality and codec breakdown, disk forecast, download volume per indexer (Prowlarr stats), Jellystat/Tautulli watch stats | M | Pro |
| **Calendar export** | Expose the \*arr iCal feeds (`/feed/v3/calendar/…ics?apikey=`) via "Subscribe in calendar", or write to the Android `CalendarContract` | S | Pro |
| **Wake-on-LAN** | UDP magic packet to broadcast; optional "wake, then wait for service" flow | S | Pro |
| **SSH tunnelling** (builds on the in-progress SSH work) | Local port-forward through a bastion (VPS or router) so services are reachable without a VPN. Pairs with SSH key management in Android Keystore. | M | Pro |
| **Tailscale** | Embedding a tailnet node (`tsnet`/`libtailscale`) conflicts with Android's one-VPN rule and is heavy. Instead: detect a `100.x` / `*.ts.net` address and use it as the `altUrl`; "Open Tailscale" when unreachable; onboarding help for MagicDNS/HTTPS certs. | S | Free |
| **Localisation** | Externalise strings, then use Weblate (free hosting for libre projects). Priorities: DE, FR, ES, PT-BR, NL, IT, PL, RU. The \*arr community is very European. | M then ongoing | Free |
| **Accessibility** | Content descriptions on posters and icon buttons, 48 dp targets, TalkBack labels for progress, font scaling to 200 %, don't convey status by color alone (status chips) | S–M | Free |
| **Wear OS** | One Tile: queue progress plus "pause all". Low demand. | M | Later, Pro |
| **Android Auto** | **Don't.** Android Auto only allows specific app categories (media, messaging, navigation, POI, IoT…), and a server manager is not one of them. | — | No |
| **"Play on Jellyfin/Plex"** | Deep-link from a library item to Findroid, the Jellyfin app or Plex | S | Free |
| **Search everywhere** | One search box across library, Seerr, Prowlarr and the calendar | M | Free |

---

## 4. Monetisation

### 4.1 Market prices

| App | Model | Price |
|---|---|---|
| nzb360 (Android) | Free + one-time Pro (plus recent "feature credits") | about US$12–13 normal, about $8.40 on sale |
| Helmarr (iOS) | Freemium; subscription *or* lifetime *or* per-service | $3.99/mo · $4.99/mo Ultra · **$34.99 lifetime** · $4.99 per service |
| Ruddarr (iOS) | Free + push subscription | about $5.99 (Ruddarr+) |
| Arrcade | Free + one-time Pro | £5.99 |
| YaArr | Ads, $1.99 to remove | $1.99 |
| Notifiarr | Patron (one-time) / Subscriber (monthly) | — |

The self-hosting crowd is **subscription-averse**. They self-host *to avoid* subscriptions. nzb360's
14-year one-time model and Helmarr's "buy once and be done forever" positioning
([developer post](https://reddit.sentinel-team.org/posts/1pqoyqc/snapshots/2025-12-20T08%3A10%3A44.996769Z))
are the norm. Charging a subscription is defensible only for a service with a running cost (a push relay).

### 4.2 Options considered

| Option | Verdict |
|---|---|
| Donations only (GitHub Sponsors, Ko-fi) | Brings in very little. LunaSea is the cautionary tale. Keep as a side channel. GitHub Sponsors pays out in India. |
| Paid Play app, free on GitHub/F-Droid (Conversations/OsmAnd+ model) | Simple, but a paid-upfront app with no trial converts poorly, and the core audience happily sideloads. |
| Subscription for everything | Wrong for this audience; it invites "sub-arr" backlash. |
| Per-service unlocks (Helmarr) | Confusing, and it punishes exactly the power users who'd pay. |
| **Freemium + one-time Pro, with a subscription only for hosted push** | **Recommended.** |

### 4.3 Recommendation: "Bosunarr Pro", one-time

- **Price:** **US$9.99** one-time. That is below nzb360 (about $12), far below Helmarr lifetime ($34.99), and
  above Arrcade (£5.99), justified by breadth and open source. **Launch at $6.99 for the first 30 days** to
  seed reviews. Use Play's regional pricing: **India ₹349**, and Brazil, Turkey and others at Play's suggested
  local equivalents. Pro covers the user's own devices (Play purchases follow the Google account).
- **Free forever** (has to be good enough to earn the 5★ reviews that sell Pro):
  every service integration · library/detail/add · Discover + requests · calendar · activity · **interactive
  search** · season pass · 1 dashboard · polling notifications for health and finished downloads · biometric
  lock · encrypted export/import · custom headers · tablet layouts · shortcuts · deep-link to players.
- **Pro** (convenience, power and server management):
  **home-screen widgets** · **real-time push via ntfy/UnifiedPush** + every notification type + quiet hours ·
  **SSH server management (stats, Docker), Unraid, Portainer** · **SSH tunnelling** · Wake-on-LAN · multiple
  profiles/servers · Quick Settings tile · statistics/insights · calendar export · scheduled automatic backups ·
  extra themes (AMOLED, custom seed colors, alternative icons) · Wear OS tile (later).
- **Later, the only subscription: "Bosunarr Push"** hosted relay, **US$0.99/mo or $9.99/yr**, for people who
  don't want to run ntfy. It exists *only* because it costs money to run.
- **Sales:** Black Friday / anniversary sales (−30 %) work for nzb360 every year. Do the same.

### 4.4 AGPL-3.0 implications (and why they're fine)

- **Anyone may rebuild the app and redistribute it, even on Play and even with Pro unlocked**, as long as they
  ship the source. You cannot stop that with code. Accept it. You are selling **official binaries, Play
  convenience, automatic updates, support and continued development**, not secrecy.
- **Real moat = trademark.** Adopt a short `TRADEMARKS.md`: the AGPL covers the code, but the name "Bosunarr"
  and the logo are reserved, so forks must rebrand (Firefox/Signal model). Play's impersonation policy then
  works *for* you against clone listings. Consider an Indian trademark filing (Class 9) once revenue justifies
  it.
- **Proprietary Play libraries.** The Play Billing Library and Play In-App Updates are not AGPL-compatible
  "System Libraries". As copyright holder, **add a GPL §7 additional permission** to the license allowing
  linking with Google Play Billing and Play Core in the `play` flavor. Keep them out of the `foss` flavor.
- **Contributors:** to keep the right to ship Play builds and to relicense later, require a **CLA** (e.g.
  CLA Assistant) or contributions under the same §7 exception. Decide this **before** you accept outside
  PRs.
- **License checks in the source are trivially removable.** Make that an explicit, friendly policy: "Build it
  yourself and everything is unlocked; buying Pro is how you support the project." Self-builders were never
  going to pay; this keeps goodwill and an F-Droid/IzzyOnDroid presence.

### 4.5 Channels and mechanics

| Channel | Build | Pro unlock |
|---|---|---|
| Google Play | `play` flavor | **Google Play Billing**, one-time non-consumable `pro_lifetime`. Restore via `queryPurchasesAsync`. Server-side verification isn't needed for a one-time unlock in a no-server app. |
| GitHub Releases (official APK) | `github` flavor (self-updater, Whisparr) | **Offline license key**: a merchant of record emails a key that is an **Ed25519-signed token** (email hash + order id); the app verifies it with the embedded public key, so there is no server. |
| F-Droid / IzzyOnDroid / self-build | `foss` flavor | Everything unlocked; donate link |

**Merchant of Record for license keys:** an MoR handles worldwide VAT/GST and remits to you, which matters
from India. Candidates: **Dodo Payments** (India-based MoR), **Paddle**, **Lemon Squeezy**. Lemon Squeezy
is reportedly migrating to Stripe Managed Payments, and India is footnoted on its payout list, so verify
before choosing. Fees are about 5 % + $0.50.
([Lemon Squeezy countries](https://docs.lemonsqueezy.com/help/getting-started/supported-countries))

### 4.6 India: payments and tax (brief; get a CA)

- **Play payouts:** set up the Play **payments profile**. **Merchant verification under RBI's Payment
  Aggregator – Cross Border rules is mandatory**, and unverified accounts get a **sales block**.
  ([Google Payments help](https://support.google.com/paymentscenter/answer/7421525))
- **Play service fee:** 15 % on the first US$1M/year for nearly all developers.
  ([Play service fees](https://support.google.com/googleplay/android-developer/answer/9384608))
- **GST:** Google says developers with an **Indian merchant address may be responsible for GST on sales to
  Indian customers** (Google remits only for non-Indian developers). Foreign sales via an MoR are typically
  zero-rated exports of services under a **LUT**. Keep **FIRC/e-BRC** for foreign inward remittances. The
  GST registration threshold for services is ₹20 lakh, but confirm with a CA whether OIDAR rules apply.
- **Income tax:** report as business/professional income. Presumptive taxation under §44ADA may apply.
- **Privacy of your address:** on Play, monetised apps make you a "trader" (EU DSA), so your **name, address
  and phone become public**. Consider registering a sole proprietorship (Udyam) and an **organization Play
  account** with a business address and D-U-N-S. That also **removes the 12-tester rule** (see §5).
- **Sponsors:** GitHub Sponsors supports Indian bank accounts.
  ([GitHub changelog](https://github.blog/changelog/2022-05-23-github-sponsors-is-now-available-in-india))

---

## 5. Play Store readiness checklist

### 5.1 Build flavors (do this first)

```
flavorDimensions += "channel"
productFlavors {
  create("github") { dimension = "channel" }   // self-updater, Whisparr, license-key Pro
  create("play")   { dimension = "channel" }   // Play Billing, Play In-App Updates, no Whisparr
  create("foss")   { dimension = "channel" }   // no proprietary deps, everything unlocked
}
```

- [ ] **Remove `REQUEST_INSTALL_PACKAGES`, the `FileProvider` for updates, and the updater UI from `play`.**
      Play forbids apps updating themselves "using any method other than Google Play's update mechanism"
      and only allows this permission when it is core to the app's purpose.
      Use `src/play/AndroidManifest.xml` with `tools:node="remove"` and a `BuildConfig.CHANNEL` switch.
      Replace the updater with **Play In-App Updates** (flexible flow).
      ([Policy](https://support.google.com/googleplay/android-developer/answer/12085295),
      [Device & Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379))
- [ ] **Exclude Whisparr from `play` at compile time** (the `ServiceType` entry, library chip, add flow).
      Play doesn't allow apps that contain or promote sexual content. Whisparr surfaces adult posters and
      titles, which an IARC "Everyone/Teen" rating can't cover, and a reviewer seeing it means removal.
      Keep it in `github`/`foss`. The listing should not mention it.
      ([Inappropriate content](https://support.google.com/googleplay/android-developer/answer/9878810))
- [ ] Keep `play` and `github` **signed with the same key** (§5.4) and keep `play` versionCodes ≥ `github`,
      so users can move from the GitHub build to Play without uninstalling.

### 5.2 Policy and declarations

- [ ] **Target SDK:** new apps and updates must target **API 36** since 31 Aug 2026. ✅ Already
      `targetSdk = 36`. Plan API 37 for Aug 2027.
      ([Target API requirements](https://developer.android.com/google/play/requirements/target-sdk))
- [ ] **Cleartext traffic:** `network_security_config.xml` allows cleartext and user CAs globally. That is
      acceptable on Play, but (a) document why in the listing and privacy policy (LAN services are plain
      HTTP), (b) show an in-app "HTTP, not encrypted" warning on non-LAN, non-tailnet hosts, and (c) consider
      allowing cleartext only for private ranges and `.ts.net`/`.local` domains. You can't express IP ranges
      in the config, so this is a soft warning in code.
- [ ] **Privacy policy** at `site/privacy.html` (public URL, linked in the listing and in-app). It should say
      that no data is collected by the developer, credentials stay on the device, encrypted with a
      device-bound key and excluded from backups, the app talks only to servers the user adds, and the only
      exceptions are Play Billing, GitHub for update checks (`github` only) and ntfy/relay if enabled.
- [ ] **Data safety form:** with no analytics, crash SDK or developer server, declare **no data collected,
      no data shared**. If Bosunarr Push launches, declare "App activity / other" collected for app
      functionality, encrypted in transit, not shared. Revisit whenever an SDK is added.
- [ ] **App access:** reviewers can't reach a home server. Provide **demo mode** (in progress) and explain in
      Play Console → *App access* how to enter it. Without this the review fails.
- [ ] **Content rating (IARC):** a utility with no user-to-user communication. Answer honestly that the app
      displays metadata and artwork from the user's own server. Expect *Teen/12+* because of movie artwork.
- [ ] **Intellectual property / piracy optics:** torrent clients and \*arr remotes are allowed, but avoid
      "torrent", "download movies free", indexer names and real tracker screenshots in the listing. Use
      demo-mode screenshots with public-domain or TMDB-safe artwork. nzb360 was pulled from Play once; don't
      give a reviewer a reason.
- [ ] **Permissions:** `POST_NOTIFICATIONS` (runtime prompt when notifications ship). No
      `QUERY_ALL_PACKAGES`. If SSH tunnelling runs in the background, a foreground service with
      `foregroundServiceType="dataSync"` or `specialUse` plus the declaration form.
- [ ] **Billing:** current Play Billing Library major version (Play retires old ones on a rolling schedule).
      The Pro unlock must use Play Billing inside the Play build. Don't link to the license-key store from
      the `play` flavor.
- [ ] **Large screens:** follow Play's large-screen quality guidelines (no locked orientation, resizable,
      keyboard/mouse friendly). This feeds tablet and Chromebook placement.

### 5.3 Account and testing

- [ ] **Personal account created after 13 Nov 2023:** each new app needs **12 testers opted in to a closed
      test for 14 consecutive days** before production access. Recruit from r/selfhosted, r/sonarr and the
      repo's Discussions. Testers must *use* the app; engagement is reportedly checked.
      ([summary](https://ontest.app/blog/google-play-12-testers-14-days-requirement-explained))
      **Alternative:** an organization account (D-U-N-S) is exempt and hides your home address (§4.6).
- [ ] Payments profile + **RBI PA-CB merchant verification** before turning on paid features.

### 5.4 Signing

- [ ] Enrol in **Play App Signing** and **upload your existing release key** (export with PEPK) instead of
      letting Google generate one, so Play and GitHub APKs share a signature. Use a separate **upload key**
      for CI.
- [ ] **Android developer verification (sideloading):** from **30 Sep 2026** certified devices in Brazil,
      Indonesia, Singapore and Thailand only install apps from **verified developers**, expanding
      **globally in 2027**. The GitHub APK is your main channel, so **register the package name `app.bosunarr`
      and the signing key in the Android Developer Console now**.
      ([Help Net Security](https://www.helpnetsecurity.com/2026/06/19/android-developer-verification-rollout-markets/),
      [TechCrunch](https://techcrunch.com/2025/08/25/google-will-require-developer-verification-for-android-apps-outside-the-play-store/))

### 5.5 Store listing assets

- [ ] Title (≤30 chars): **"Bosunarr: \*arr stack manager"**. Short description (≤80): "Sonarr, Radarr,
      Jellyfin & more — one fast, private, open-source app." Full description (≤4000) with keywords:
      Sonarr, Radarr, Lidarr, Prowlarr, Bazarr, Seerr, Jellyfin, qBittorrent, SABnzbd, self-hosted, homelab.
- [ ] 512×512 icon (already have), **1024×500 feature graphic**, **4–8 phone screenshots** (demo mode, light +
      dark, with captions), **7" and 10" tablet screenshots** once tablet layouts ship, optional 30 s video.
- [ ] Category **Tools**; tags: Tools, Productivity.
- [ ] Support email, website and privacy policy URL.
- [ ] **Pre-launch report** (Firebase Test Lab) must come back clean, including accessibility warnings.

---

## 6. Prioritised roadmap

### Top 10, in order

| # | Item | Why | Effort |
|---|---|---|---|
| 1 | **Play flavor + closed test started** (manifest split, no Whisparr, Play In-App Updates, privacy policy, Data safety, demo-mode reviewer access, listing) | The 14-day tester clock is the critical path to any revenue. Start it now and build the rest during the test. | M |
| 2 | **SABnzbd + NZBGet** | About 1.5B combined linuxserver pulls. Usenet users can't use Bosunarr *at all* today, and they are the most likely to pay (they already pay for Usenet). | S + S |
| 3 | **Notifications v1**: WorkManager polling + one-tap ntfy/UnifiedPush setup in \*arr/Seerr | The #1 expected feature. No server, so it stays true to the brand. The core Pro hook. | M |
| 4 | **Glance widgets** (queue, up next, health) + QS tile | The most visible "this is a premium app" feature; Helmarr's headline. Pro hook. | M |
| 5 | **Interactive search + season pass** | Power users expect it; every serious competitor has it; it's free goodwill. | M |
| 6 | **Encrypted backup/restore + biometric lock + custom headers/basic auth** | Trust, and unblocks reverse-proxy users. Losing config after a phone change produces 1★ reviews. | M |
| 7 | **Pro entitlement + Play Billing + license keys** | Plumbing to monetise items 3, 4 and 9. Ship it ready but inactive until the Play listing is live. | M |
| 8 | **Lidarr** + **Seerr rename** | Cheap breadth: Lidarr reuses \*arr code; Seerr is a correctness fix after the Feb 2026 merge. | M / S |
| 9 | **Plex + Tautulli** | Opens the largest media-server user base; today Bosunarr is Jellyfin-only. | M |
| 10 | **Tablet/foldable layouts + Unraid** (with the SSH/Docker work) | Play large-screen placement; Unraid is the most common \*arr host OS and both rivals have it. | M + M |

### Next 2 weeks

- Play flavor, signing plan, privacy policy, Data safety, listing assets from demo mode; **open the closed test
  with ≥12 testers on day 1**. Register for Android developer verification.
- Seerr service type (alias the Jellyseerr/Overseerr ids; new logo/copy).
- SABnzbd + NZBGet in Activity (merged with the qBit list) plus per-service screens and an NZB share target.
- Encrypted export/import (passphrase) + biometric lock.
- Pro entitlement abstraction (`Entitlements.isPro` from Play Billing / license key / `foss` = true).
- Add `TRADEMARKS.md`, a §7 Play-libraries exception, and a CLA bot.

### Next 2 months

- Notifications A + B (polling, ntfy/UnifiedPush auto-setup), `POST_NOTIFICATIONS` flow, quiet hours.
- Glance widgets ×3, Quick Settings tile, app shortcuts.
- Interactive search (Sonarr/Radarr/Lidarr), season pass, bulk edit.
- Lidarr; Plex (PIN auth) + Tautulli; Transmission + Deluge.
- Custom headers / basic auth / client certs.
- Adaptive list-detail layouts for tablets and foldables.
- Unraid (GraphQL) and Wake-on-LAN, in the same "Server" area as the SSH/Docker work.
- **Production launch on Play** with the $6.99 launch price; announce on r/selfhosted, r/sonarr, r/radarr,
  r/jellyfin, r/usenet and r/androidapps; submit to IzzyOnDroid (the `foss` build).
- Localisation infrastructure (Weblate) + accessibility pass.

### Later (3–9 months)

- Hosted **Bosunarr Push** relay (subscription) via FCM.
- Emby, Tracearr, Gluetun, Portainer, Cleanuparr, Maintainerr, Autobrr, Tdarr, Jackett/NZBHydra2, rTorrent.
- Audiobookshelf (books; fills the Readarr vacuum), then Kavita/Komga/Mylar3 if users ask.
- SSH tunnelling (reach services with no VPN), Tailscale niceties, multiple profiles.
- Statistics/insights, calendar export, Homepage `services.yaml` import in onboarding.
- Wear OS tile.
- Stretch: an iOS version. Helmarr shows iOS users pay more, but it's a separate codebase (or KMP +
  Compose Multiplatform). Revisit after Android revenue proves the model.

### Explicit non-goals

Android Auto · Huntarr · Readarr · in-app media playback (deep-link to Findroid/Jellyfin/Plex instead) ·
a Bosunarr cloud account · ads.

---

## Appendix: demand numbers used

| Repo / image | GitHub ★ (11 Nov 2026) | linuxserver pulls |
|---|---|---|
| Radarr / Sonarr | 14.8k / 17.7k | 2.38B / 2.36B |
| Jackett | 16.4k | 1.58B |
| Tautulli | 6.6k | 991M |
| Plex | — | 910M |
| NZBGet | 0.8k (nzbgetcom) | 810M |
| SABnzbd | 3.2k | 721M |
| Lidarr | 5.8k | 570M |
| Transmission | 15.4k | 538M |
| Bazarr | 4.4k | 479M |
| qBittorrent | — | 348M |
| Deluge | 1.8k | 333M |
| NZBHydra2 | — | 359M (`hydra2`) + 168M (`nzbhydra2`) |
| Jellyfin | 59.2k | 165M |
| rTorrent/ruTorrent | 2.2k | 154M |
| Emby | — | 46M |
| Prowlarr | 7.5k | 45M (newer image) |
| Mylar3 | 1.5k | 15.8M |
| Overseerr → Seerr | 5.0k (archived) → 13.2k | 12.7M |
| Immich · Portainer · Homepage · Navidrome · Gluetun | 118.8k · 38.8k · 33.6k · 24.6k · 16.0k | — |
| Audiobookshelf · Kavita · Komga | 15.0k · 12.1k · 6.8k | — |
| Homarr · Tdarr · Kometa · Autobrr · Wizarr | 5.3k · 4.4k · 3.5k · 3.2k · 3.3k | — |
| Tracearr · Cleanuparr · Jellystat · Maintainerr · Recyclarr | 2.7k · 2.7k · 2.5k · 2.4k · 2.2k | — |
| Unpackerr · Kapowarr · Whisparr · Notifiarr | 1.5k · 1.1k · 1.1k · 0.9k | — |
| Readarr | 3.5k (**archived** 27 Jun 2025) | 89M |

Pull counts favour older images (Jackett, NZBGet) and undercount apps whose official images aren't on
linuxserver. Read them as relative popularity among \*arr users, not absolute install base.
