# Bosunarr — plan

> *The bosun runs the deck crew. Bosunarr runs your \*arr crew.*

An **open-source, self-hosted** web app (installable PWA) for managing a media stack —
Sonarr, Radarr, Prowlarr, Bazarr, Jellyseerr/Overseerr, qBittorrent, Jellyfin, Jellystat, Wizarr
and the host itself — with a fast, responsive **Material 3** UI that works on phones and laptops.

Anyone can `docker run` it, open it, create an admin account, add their services (URL + API key)
in the UI, and it works out of the box. No config files required.

---

## 1. Principles

1. **Out of the box.** One container, one volume. First run → create admin → add services from a guided form
   with "Test connection". Optional env/YAML import for people who prefer config as code.
2. **Keys never reach the browser.** The server stores credentials encrypted (AES-256-GCM, key generated on
   first run into the data volume, or supplied via `BOSUNARR_SECRET`) and proxies every call.
3. **No lag.** SSR + small client bundles (Svelte 5 compiles away the framework), list virtualization,
   stale-while-revalidate caching, optimistic updates, SSE for live data, image proxy with resized/cached
   WebP posters, prefetch on hover/touch-start. Budgets: < 100 KB JS initial, LCP < 1.5 s on a mid phone,
   60 fps scroll on a 5,000-item library.
4. **Responsive.** M3 adaptive layouts: navigation bar (compact) → rail (medium) → drawer (expanded);
   list-detail panes on wide screens.
5. **Server-side everything that touches third parties** (e.g. TMDb via the user's Jellyseerr), so ISP blocks
   on the client don't matter.
6. **Safe by default.** Destructive actions confirm; Docker access read-only unless enabled; no web terminal.

## 2. Stack

| Layer | Choice | Why |
|---|---|---|
| App | **SvelteKit 2 + Svelte 5** (TypeScript), adapter-node | Smallest/fastest client, SSR + API routes in one process |
| UI | **m3-svelte** + Google **material-color-utilities** | Material 3 components, dynamic color, tonal palettes |
| Data | **SQLite** (better-sqlite3) + Drizzle ORM | Zero-ops; one file in `/data` |
| Auth | Built-in users (argon2id), sessions (HttpOnly cookies); optional OIDC + trusted-header (Authelia/Tailscale) later | Works exposed or behind a proxy |
| Live | Server-Sent Events | Simple, proxy-friendly |
| Ship | Multi-arch Docker image (amd64 + arm64), GHCR | Runs on NAS, Pi, VPS |
| Tests | Vitest (unit, integration adapters with recorded fixtures), Playwright (phone + desktop viewports) | |

## 3. Architecture

```
Browser (PWA)  ──HTTPS──▶  Bosunarr container
                           ├─ SvelteKit SSR pages
                           ├─ /api/*          app API (auth, dashboard, settings)
                           ├─ /api/svc/:id/*  per-instance typed adapters (key injected server-side)
                           ├─ aggregators     library · calendar · activity · health · search
                           ├─ image proxy     resize + cache posters/fanart
                           ├─ SSE hub         queue · torrents · now-playing · health
                           └─ SQLite (/data)  users · services (encrypted creds) · dashboards · prefs
                                   │
                                   ▼  user's services (any number of instances of each type)
```

**Service adapters** are plugins with one shape: `{ type, testConnection, capabilities, endpoints, widgets }`.
Multiple instances per type are first-class (e.g. "Radarr" + "Radarr 4K").

## 4. Navigation (top-level tabs)

`Dashboard · Library · Discover · Calendar · Activity · Services · Settings`

- **Dashboard** — the user's own grid of widgets (see §5). Default layout on first run is generated from the
  services added.
- **Library** — unified movies + series across all Sonarr/Radarr instances; A–Z rail, sort, filters
  (monitored, missing, cutoff unmet, 4K, tags), virtualized grid/list, detail page with poster-derived theme.
- **Discover** — Jellyseerr/Overseerr: trending, popular, genres, search, request (incl. 4K), approve/decline.
- **Calendar** — week strip + agenda; month view on wide screens.
- **Activity** — merged arr queues + download client; ETA, remove, blocklist.
- **Services** — one page per instance: Prowlarr (search, indexers, stats), Bazarr (wanted, history),
  qBittorrent (full control), Jellystat (now playing, history, graphs), Wizarr (users, invites),
  arr instances (health, disk, system), Host (CPU/RAM/disk/network, containers, logs).
- **Settings** — services (add/edit/test), users, appearance (seed color, light/dark/AMOLED), notifications.

## 5. Dashboard tab

- Responsive grid: 4 columns phone, 8 tablet, 12 desktop; widgets have S/M/L sizes and reflow automatically.
- **Edit mode:** add widget (picker filtered to the user's services), drag to reorder, resize, configure,
  remove; saved per user (and per device-class optionally).
- **Widget catalogue (v1):**
  - Service health (all instances, dot + latency)
  - Now playing (Jellyfin/Jellystat)
  - Downloads (qBittorrent speeds + top torrents)
  - Queue (arr queues)
  - Upcoming (calendar, next 7 days)
  - Recently added (library)
  - Requests (pending Jellyseerr requests with approve/decline)
  - Disk space (per root folder / host drive)
  - Host stats (CPU / RAM / network sparkline)
  - Indexer health (Prowlarr)
  - Stats (plays this week, top users — Jellystat)
  - Quick links (custom URLs)
- Widgets share the cache with full pages, so the dashboard is instant after first load.

## 6. Supported services (v1)

Sonarr, Radarr (n instances), Prowlarr, Bazarr, Jellyseerr / Overseerr, qBittorrent, Jellyfin, Jellystat,
Wizarr, Host (via a read-only Docker socket proxy + `/proc`). Later: Lidarr, Readarr, Transmission/Deluge/SAB,
Plex/Tautulli, Emby, Cleanuparr, Uptime Kuma.

## 7. Milestones

| # | Deliverable |
|---|---|
| M0 | Repo, CI, Docker image; M3 shell (adaptive nav, theming); first-run admin; services CRUD with encrypted keys + test connection |
| M1 | Dashboard tab with edit mode + first widgets (health, downloads, queue, upcoming, requests, disk) |
| M2 | Library + detail (read) for Sonarr/Radarr, image proxy |
| M3 | Activity + qBittorrent control, SSE |
| M4 | Discover + requests |
| M5 | Calendar |
| M6 | Write actions: monitor, search, interactive release search, delete (with confirms) |
| M7 | Prowlarr, Bazarr, Jellystat, Wizarr pages |
| M8 | Host page (metrics, containers, logs) |
| M9 | PWA polish, notifications (Web Push / ntfy / Apprise), a11y, perf budgets in CI, docs site, v1.0 release |

## 8. Repo & release

- License **AGPL-3.0** (keeps forks open) — or MIT if wider reuse is preferred.
- README with screenshots, `docker-compose.yml` example, CONTRIBUTING, adapter-writing guide.
