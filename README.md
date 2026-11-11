# Bosunarr

> The bosun runs the deck crew. **Bosunarr runs your \*arr crew.**

**Website:** <https://selinjodhani.github.io/bosunarr/> · **Android app:** [download the APK](https://github.com/SelinJodhani/bosunarr/releases/latest)

A fast, self-hosted web app for managing your media stack — Sonarr, Radarr, Prowlarr, Bazarr,
Jellyseerr/Overseerr, qBittorrent, Jellyfin, Jellystat and Wizarr — with a Material 3 interface
that works the same on your phone and your laptop. Install it as an app (PWA) from the browser.

**Status:** early development (milestone M1). The Dashboard and service setup work today; Library,
Discover, Calendar and Activity are next. See [PLAN.md](PLAN.md).

## Features

- **Works out of the box.** Run one container, create an admin, add your services with URL + API key,
  press *Test*, done. No config files.
- **Your keys stay on the server.** API keys and passwords are encrypted at rest (AES-256-GCM) and every
  request to your services is made server-side. Browsers never see a key.
- **Dashboard with widgets** — service health, downloads, queue, upcoming releases, pending requests,
  disk space, now playing. Add, remove, reorder and resize; saved per user.
- **Fast.** Svelte 5 compiles away the framework; widgets paint instantly from cache and refresh in
  the background only while the tab is visible.
- **Responsive Material 3.** Navigation bar on phones, rail on tablets and laptops; light, dark and
  accent colours.
- **Multiple instances** of any service (e.g. Radarr and Radarr 4K).

## Quick start

```bash
docker run -d --name bosunarr -p 8080:8080 \
  -e ORIGIN=http://localhost:8080 \
  -v ./bosunarr-data:/data \
  ghcr.io/selinjodhani/bosunarr:latest
```

Open http://localhost:8080, create the admin account, then **Settings → Add service**.
See [docker-compose.example.yml](docker-compose.example.yml) for Compose.

| Variable | Default | Purpose |
|---|---|---|
| `ORIGIN` | — | The URL you open Bosunarr at. Required behind a reverse proxy. |
| `PORT` | `8080` | Listen port |
| `BOSUNARR_DATA_DIR` | `/data` | Database and generated secret |
| `BOSUNARR_SECRET` | generated | Key used to encrypt stored credentials. Back up `/data/secret.key` if you don't set it. |

## Supported services

| Service | Credentials | Used for |
|---|---|---|
| Sonarr, Radarr, Lidarr | API key | Queue, calendar, disk, health |
| Prowlarr | API key | Health (indexers page coming) |
| Bazarr | API key | Health (subtitles page coming) |
| Jellyseerr / Seerr / Overseerr | API key | Pending requests |
| qBittorrent | Web UI username + password | Live downloads |
| Jellyfin | API key | Now playing |
| Jellystat | API key | Health (stats page coming) |
| Wizarr | API key | Health (users page coming) |

## Security notes

- Put Bosunarr behind HTTPS if you expose it beyond your LAN/VPN; set `ORIGIN` to the HTTPS URL.
- Only image paths are proxied (`/api/img`), not arbitrary URLs.
- Login is rate-limited; passwords are hashed with Argon2id.

## Development

```bash
npm install
npm run dev        # http://localhost:5173
npm run check      # type-check
npm run build && node build
```

## License

[AGPL-3.0](LICENSE)
