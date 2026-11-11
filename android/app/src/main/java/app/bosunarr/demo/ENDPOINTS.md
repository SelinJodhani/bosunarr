# Demo mode: endpoint checklist

Every request to `http://demo-<service>.bosunarr.invalid` is answered by `DemoInterceptor` (installed on the base
OkHttp client in `AppContainer`, so the API client, the long-timeout `rawCall` and Coil all go through it). Nothing
reaches the network. Routing: `DemoWorld.handle` → one handler per service.

Fixtures: `assets/demo/catalog.json` (the films and series), `assets/demo/<service>/*.json` (static answers;
strings like `@now-3h` / `@naive+7d` become times relative to now), `assets/demo/images/*.webp`. Anything that
depends on today (calendar, queue, sessions, statistics) is generated at runtime from the catalog. Writes mutate the
in-memory world, which is rebuilt each time the demo is entered.

`DemoEndpointsTest` (src/test) calls every row below through the real `ServiceClient` and decodes the answers with
the app's own models; run it after a feature starts calling something new.

Legend: **F** = static fixture, **G** = generated, **W** = write that changes the demo state.

## Connection tests (`ServiceClient.test`, health widget, Settings)

| Service | Probe | Source |
|---|---|---|
| Sonarr / Radarr | `GET /api/v3/system/status` | F `sonarr|radarr/system-status.json` |
| Prowlarr | `GET /api/v1/system/status` | F `prowlarr/system-status.json` |
| Bazarr | `GET /api/system/status` | F `bazarr/system-status.json` |
| Jellyseerr | `GET /api/v1/settings/about` | F `jellyseerr/settings-about.json` + G counts |
| qBittorrent | `POST /api/v2/auth/login` (204 + `Set-Cookie: SID=demo`), `GET /api/v2/app/version` | G (`DemoQbit`) |
| Jellyfin | `GET /System/Info` | F `jellyfin/system-info.json` |
| Jellystat | `GET /api/getLibraries` | G |
| Wizarr | `GET /api/status` | G (counts from `wizarr/*.json`) |

## Sonarr / Radarr (`DemoArr`)

| Endpoint | Used by | Source |
|---|---|---|
| `GET /api/v3/series`, `/series/{id}` | Library, series detail | G from catalog |
| `PUT /api/v3/series/{id}` | monitor series / season | W |
| `DELETE /api/v3/series/{id}?deleteFiles=` | delete series | W |
| `GET /api/v3/series/lookup?term=` | Library → Add | G (catalog search) |
| `POST /api/v3/series` | Library → Add | W (+ grabs with "search") |
| `GET /api/v3/episode?seriesId=` | series detail | G |
| `PUT /api/v3/episode/monitor` | episode monitor | W |
| `GET /api/v3/episodefile?seriesId=` | series detail | G |
| `GET /api/v3/languageprofile` | add series (v3 compat) | F |
| `GET /api/v3/movie`, `/movie/{id}` | Library, movie detail | G |
| `PUT /api/v3/movie/{id}` | monitor movie | W |
| `DELETE /api/v3/movie/{id}?deleteFiles=` | delete movie | W |
| `GET /api/v3/movie/lookup?term=` | Library → Add | G |
| `POST /api/v3/movie` | Library → Add | W (+ torrent when "search") |
| `GET /api/v3/qualityprofile`, `/rootfolder` | Library, Add, service screen | F |
| `GET /api/v3/calendar?start&end[&unmonitored][&includeSeries]` | Calendar, dashboard Up next | G (dates relative to today) |
| `GET /api/v3/queue?page&pageSize&include…` | Activity → Queue | G from the simulated torrents |
| `DELETE /api/v3/queue/{id}?removeFromClient&blocklist` | remove queue item | W |
| `GET /api/v3/queue/status` | dashboard Downloads, service screen | G |
| `GET /api/v3/history?…&eventType=` | dashboard Recently added, service History tab | G (filters by event type) |
| `GET /api/v3/health`, `/diskspace` | dashboard Health / Disks, service screen | F |
| `GET /api/v3/system/status` | service screen | F |
| `GET /api/v3/command`, `POST /api/v3/command` | service tasks + actions, search buttons | W (searches start downloads) |
| `GET /api/v3/wanted/missing`, `/wanted/cutoff` | service screen | G |
| `GET /MediaCover/{id}/{poster,fanart}[-size].jpg`, `/MediaCover/lookup/{key}/…` | all posters | `images/` |

## qBittorrent (`DemoQbit`, `DemoTorrents`)

| Endpoint | Used by | Source |
|---|---|---|
| `POST /api/v2/auth/login` | session | G |
| `GET /api/v2/app/version` | connection test | G |
| `GET /api/v2/sync/maindata?rid=` | Activity → Torrents, qBittorrent screen | G (always `full_update`, live progress / speeds) |
| `GET /api/v2/torrents/info?filter=active`, `/transfer/info` | dashboard Downloads | G |
| `GET /api/v2/torrents/properties|trackers|files?hash=` | torrent detail sheet | G |
| `GET /api/v2/torrents/categories` | Add torrent sheet | G |
| `POST /api/v2/torrents/{stop,start,pause,resume}` | pause / resume (one or all) | W |
| `POST /api/v2/torrents/{recheck,reannounce}` | torrent actions | W (no-op) |
| `POST /api/v2/torrents/delete`, `/setCategory`, `/filePrio` | torrent actions | W |
| `POST /api/v2/torrents/add` (form or multipart) | Add torrent sheet, shared magnets | W |
| `POST /api/v2/transfer/setSpeedLimitsMode`, `/toggleSpeedLimitsMode`, `GET /speedLimitsMode` | alt speed toggle | W |

## Jellyseerr (`DemoSeerr`)

| Endpoint | Used by | Source |
|---|---|---|
| `GET /api/v1/discover/{trending,movies,movies/upcoming,tv,tv/upcoming}` | Discover rows | G |
| `GET /api/v1/search?query=&page=` | Discover search | G |
| `GET /api/v1/{movie,tv}/{id}` | media detail, request titles | G |
| `GET /api/v1/{movie,tv}/{id}/{recommendations,similar}` | media detail | G |
| `GET /api/v1/movie/{id}/ratingscombined`, `/movie/{id}/ratings`, `/tv/{id}/ratings` | media detail | G (tv: 404, like no RT data) |
| `GET /api/v1/auth/me` | can-manage check | F `users.json` |
| `GET /api/v1/request?take&skip&filter&sort`, `/request/count` | Requests, dashboard, service screen | G |
| `POST /api/v1/request` | request sheet | W (auto-approved → Radarr → download) |
| `POST /api/v1/request/{id}/{approve,decline,retry}`, `DELETE /api/v1/request/{id}` | request actions | W |
| `GET /api/v1/service/{radarr,sonarr}`, `/service/{type}/{id}` | request sheet | F (+ arr profiles / root folders) |
| `GET /api/v1/settings/about` | service screen | F + G |
| `GET /imageproxy/tmdb/t/p/{size}/{key}[-bg].jpg` | posters / backdrops | `images/` |

## Jellyfin (`DemoJellyfin`)

| Endpoint | Used by | Source |
|---|---|---|
| `GET /System/Info` | service screen | F |
| `GET /Items/Counts`, `/Library/VirtualFolders`, `/Items?ParentId=…&Limit=0` | service screen | G (what the arrs have on disk) |
| `GET /Users` | dashboard, service screen | F `jellyfin/users.json` |
| `GET /Items/Latest?userId=…&GroupItems=true` | dashboard Recently added, service screen | G |
| `GET /Sessions?activeWithinSeconds=` | Now playing | G (positions advance in real time) |
| `POST /Library/Refresh` | scan button | W (libraries show "scanning" for 10 s) |
| `GET /Items/{id}/Images/Primary` | posters | `images/` |

## Jellystat (`DemoJellystat`)

| Endpoint | Source |
|---|---|
| `GET /api/getLibraries` | G |
| `GET /stats/getViewsOverTime?days=` | G (deterministic daily plays) |
| `GET /stats/getAllUserActivity` | G |
| `POST /stats/getMostViewedByType` `{days, type}` | G |
| `POST /stats/getMostActiveUsers` `{days}` | G |
| `GET /api/getHistory?page&size` | G |
| `GET /proxy/getSessions` | G (same sessions as Jellyfin) |

## Prowlarr (`DemoProwlarr`)

| Endpoint | Source |
|---|---|
| `GET /api/v1/system/status`, `/health`, `/indexer`, `/indexerstatus`, `/indexerstats` | F `prowlarr/*.json` |
| `POST /api/v1/indexer/testall` | G (400 with one failing indexer, like Prowlarr) |
| `GET /api/v1/search?query&categories` | G (releases for matching catalog titles) |
| `POST /api/v1/search` (grab) | W (adds a torrent) |

## Bazarr (`DemoBazarr`)

| Endpoint | Source |
|---|---|
| `GET /api/system/status`, `/system/health`, `/providers` | F `bazarr/*.json` |
| `GET /api/badges` | G |
| `GET /api/{episodes,movies}/wanted?start&length` | G |
| `GET /api/{episodes,movies}/history?start&length` | G |
| `PATCH /api/{episodes,movies}/subtitles?…&language=` | W (moves the item from Wanted to History) |
| `POST /api/system/tasks?taskid=` | W (no-op) |
| `POST /api/providers?action=reset` | W (clears the throttled provider) |

## Wizarr (`DemoWizarr`)

| Endpoint | Source |
|---|---|
| `GET /api/status` | G |
| `GET /api/users`, `/libraries`, `/servers` | F `wizarr/*.json` |
| `GET /api/invitations` | F, then W |
| `POST /api/invitations` | W |
| `DELETE /api/invitations/{id}` | W |

## Not covered

- "Open in Sonarr/Radarr/Jellyseerr" buttons open the service's own web UI in the browser; the `.invalid` demo
  hosts don't exist there, so the browser shows its own error page.
- Whisparr and Overseerr have no demo instance (Jellyseerr stands in for requests).
