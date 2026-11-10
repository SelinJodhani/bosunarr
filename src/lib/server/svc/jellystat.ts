/**
 * Jellystat adapter for the per-service screen. Read-only.
 *
 * Endpoints used (Jellystat backend, auth header `x-api-token` added by `call()`):
 *   GET  /proxy/getSessions            Jellyfin sessions passed through (upstream serves this without auth)
 *   GET  /api/getLibraries             library id/name/CollectionType
 *   GET  /api/getHistory               grouped play history, paged (?page&size&search)
 *   GET  /stats/getViewsOverTime       per-day, per-library {count, duration(minutes)} (?days)
 *   GET  /stats/getAllUserActivity     per-user last activity
 *   GET  /stats/getLibraryCardStats    per-library counts, runtime (ticks), plays, watched (seconds)
 *   POST /stats/getMostViewedByType    {days, type: Movie|Series}  top 5
 *   POST /stats/getMostActiveUsers     {days}  top 5
 *   POST /stats/getMostUsedClient      {days}  top 5
 * Postgres bigint/numeric columns arrive as strings; everything goes through `num()`.
 */
import { cached, call, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import {
	PAGE_SIZES,
	PERIODS,
	type JsDay,
	type JsHistory,
	type JsLibrary,
	type JsOverview,
	type JsSession,
	type JsTop,
	type JsTopItem,
	type PeriodDays
} from '$lib/servicepages/jellystat/types';

type Row = Record<string, unknown>;

const num = (v: unknown): number => {
	const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
	return Number.isFinite(n) ? n : 0;
};
const str = (v: unknown): string => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
const optStr = (v: unknown): string | null => str(v) || null;

function rows(s: ServiceRecord, v: unknown): Row[] {
	if (!Array.isArray(v)) throw new ServiceError(`${s.name} sent an unexpected response`, 502);
	return v.filter((x): x is Row => Boolean(x) && typeof x === 'object');
}
function obj(s: ServiceRecord, v: unknown): Row {
	if (!v || typeof v !== 'object' || Array.isArray(v)) throw new ServiceError(`${s.name} sent an unexpected response`, 502);
	return v as Row;
}

const post = (s: ServiceRecord, path: string, body: unknown) =>
	call<unknown>(s, path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

/* ------------------------------------------------------------------ */
/* Validation                                                          */
/* ------------------------------------------------------------------ */

export function parseDays(v: string | null): PeriodDays {
	const n = Number(v ?? 30);
	if (!PERIODS.includes(n as PeriodDays)) throw new ServiceError(`days must be one of ${PERIODS.join(', ')}`, 400);
	return n as PeriodDays;
}

export function parseHistoryQuery(p: URLSearchParams): { page: number; size: number; q: string } {
	const page = Number(p.get('page') ?? 1);
	if (!Number.isInteger(page) || page < 1 || page > 100_000) throw new ServiceError('page must be a whole number from 1', 400);
	const size = Number(p.get('size') ?? 25);
	if (!(PAGE_SIZES as readonly number[]).includes(size)) throw new ServiceError(`size must be one of ${PAGE_SIZES.join(', ')}`, 400);
	const q = (p.get('q') ?? '').trim();
	if (q.length > 100) throw new ServiceError('Search is too long', 400);
	return { page, size, q };
}

/* ------------------------------------------------------------------ */
/* Now playing                                                          */
/* ------------------------------------------------------------------ */

const TICKS = 1e7;
const pad = (n: unknown) => String(num(n)).padStart(2, '0');

/**
 * `/proxy/getSessions` is served without auth upstream, so a wrong or revoked key would still "work" there.
 * Prove the key against an authenticated endpoint first (cached, cheap DB read) before showing sessions.
 */
const checkKey = (s: ServiceRecord) => cached(`js:${s.id}:auth`, 60_000, () => call(s, '/api/getLibraries').then(() => true));

export function nowPlaying(s: ServiceRecord): Promise<JsSession[]> {
	return cached(`js:${s.id}:now`, 5_000, async () => {
		const [, raw] = await Promise.all([checkKey(s), call(s, '/proxy/getSessions')]);
		const sessions = rows(s, raw);
		return sessions
			.filter((x) => x.NowPlayingItem && typeof x.NowPlayingItem === 'object')
			.map((x): JsSession => {
				const it = x.NowPlayingItem as Row;
				const ps = (x.PlayState && typeof x.PlayState === 'object' ? x.PlayState : {}) as Row;
				const isEp = it.Type === 'Episode';
				const runtime = num(it.RunTimeTicks) / TICKS;
				const pos = num(ps.PositionTicks) / TICKS;
				return {
					id: str(x.Id) || `${str(x.UserName)}:${str(it.Id)}`,
					user: str(x.UserName) || 'Someone',
					title: str(isEp ? it.SeriesName : it.Name) || str(it.Name) || 'Unknown',
					subtitle: isEp ? `S${pad(it.ParentIndexNumber)}E${pad(it.IndexNumber)} · ${str(it.Name)}` : optStr(it.ProductionYear),
					device: [str(x.Client), str(x.DeviceName)].filter(Boolean).join(' · '),
					progress: runtime > 0 ? Math.min(1, Math.max(0, pos / runtime)) : 0,
					paused: ps.IsPaused === true,
					method: optStr(ps.PlayMethod),
					positionSec: Math.round(pos),
					runtimeSec: Math.round(runtime)
				};
			});
	});
}

/* ------------------------------------------------------------------ */
/* Overview + plays per day                                             */
/* ------------------------------------------------------------------ */

const MONTHS: Record<string, string> = { Jan: '01', Feb: '02', Mar: '03', Apr: '04', May: '05', Jun: '06', Jul: '07', Aug: '08', Sep: '09', Oct: '10', Nov: '11', Dec: '12' };

/** Jellystat keys days as `toLocaleDateString('en-US', {month:'short', day:'2-digit', year:'numeric'})`, e.g. "Nov 07, 2026". */
function keyToDate(key: string): string | null {
	const m = /^([A-Z][a-z]{2}) (\d{1,2}), (\d{4})$/.exec(key.trim());
	if (m && MONTHS[m[1]]) return `${m[3]}-${MONTHS[m[1]]}-${m[2].padStart(2, '0')}`;
	const d = new Date(key);
	return Number.isNaN(+d) ? null : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

type Bucket = 'movies' | 'episodes' | 'other';

export function overview(s: ServiceRecord): Promise<JsOverview> {
	return cached(`js:${s.id}:overview`, 60_000, async () => {
		const [views, libs, users] = await Promise.all([
			call(s, '/stats/getViewsOverTime?days=90').then((v) => obj(s, v)),
			call(s, '/api/getLibraries').then((v) => rows(s, v)),
			call(s, '/stats/getAllUserActivity').then((v) => rows(s, v))
		]);
		const bucketOf = new Map<string, Bucket>();
		for (const l of libs) {
			const t = str(l.CollectionType);
			bucketOf.set(str(l.Name), t === 'movies' ? 'movies' : t === 'tvshows' ? 'episodes' : 'other');
		}

		const byDate = new Map<string, JsDay & { minutes: number }>();
		for (const r of rows(s, views.stats ?? [])) {
			const date = keyToDate(str(r.Key));
			if (!date) continue;
			const day = byDate.get(date) ?? { date, movies: 0, episodes: 0, other: 0, minutes: 0 };
			for (const [lib, v] of Object.entries(r)) {
				if (lib === 'Key' || !v || typeof v !== 'object') continue;
				const { count, duration } = v as Row;
				day[bucketOf.get(lib) ?? 'other'] += num(count);
				day.minutes += num(duration);
			}
			byDate.set(date, day);
		}
		const all = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));

		const now = Date.now();
		const periods = PERIODS.map((days) => {
			const slice = all.slice(-days);
			const since = now - days * 864e5;
			return {
				days,
				plays: slice.reduce((t, d) => t + d.movies + d.episodes + d.other, 0),
				minutes: slice.reduce((t, d) => t + d.minutes, 0),
				activeUsers: users.filter((u) => {
					const t = Date.parse(str(u.LastActivityDate));
					return Number.isFinite(t) && t >= since;
				}).length
			};
		});
		return { periods, daily: all.map(({ minutes: _m, ...d }) => d) };
	});
}

/* ------------------------------------------------------------------ */
/* Top lists                                                            */
/* ------------------------------------------------------------------ */

const topItems = (list: Row[], nameKey: string): JsTopItem[] =>
	list
		.map((r) => ({ name: str(r[nameKey]) || 'Unknown', plays: num(r.Plays), ...(r.total_playback_duration !== undefined ? { seconds: num(r.total_playback_duration) } : {}) }))
		.slice(0, 5);

export function top(s: ServiceRecord, days: PeriodDays): Promise<JsTop> {
	return cached(`js:${s.id}:top:${days}`, 60_000, async () => {
		const [movies, shows, users, clients] = await Promise.all([
			post(s, '/stats/getMostViewedByType', { days, type: 'Movie' }).then((v) => rows(s, v)),
			post(s, '/stats/getMostViewedByType', { days, type: 'Series' }).then((v) => rows(s, v)),
			post(s, '/stats/getMostActiveUsers', { days }).then((v) => rows(s, v)),
			post(s, '/stats/getMostUsedClient', { days }).then((v) => rows(s, v))
		]);
		return { days, movies: topItems(movies, 'Name'), shows: topItems(shows, 'Name'), users: topItems(users, 'Name'), clients: topItems(clients, 'Client') };
	});
}

/* ------------------------------------------------------------------ */
/* History                                                              */
/* ------------------------------------------------------------------ */

export function history(s: ServiceRecord, page: number, size: number, q: string): Promise<JsHistory> {
	const qs = new URLSearchParams({ page: String(page), size: String(size) });
	if (q) qs.set('search', q);
	return cached(`js:${s.id}:history:${qs}`, 15_000, async () => {
		const res = obj(s, await call(s, `/api/getHistory?${qs}`));
		const list = rows(s, res.results ?? []).map((r) => {
			const isEp = Boolean(r.SeriesName);
			return {
				id: `${str(r.Id)}:${str(r.NowPlayingItemId)}:${str(r.EpisodeId)}:${str(r.UserId)}`,
				user: str(r.UserName) || 'Unknown',
				title: str(isEp ? r.SeriesName : r.NowPlayingItemName) || 'Unknown',
				subtitle: isEp ? `S${pad(r.SeasonNumber)}E${pad(r.EpisodeNumber)} · ${str(r.NowPlayingItemName)}` : null,
				client: str(r.Client),
				device: str(r.DeviceName),
				seconds: num(r.PlaybackDuration),
				method: optStr(r.PlayMethod),
				at: optStr(r.ActivityDateInserted),
				plays: Math.max(1, num(r.TotalPlays))
			};
		});
		return { page, pages: Math.max(0, Math.floor(num(res.pages))), size, rows: list };
	});
}

/* ------------------------------------------------------------------ */
/* Libraries                                                            */
/* ------------------------------------------------------------------ */

const KIND_ORDER: JsLibrary['kind'][] = ['movies', 'tvshows', 'music', 'other'];

export function libraries(s: ServiceRecord): Promise<JsLibrary[]> {
	return cached(`js:${s.id}:libraries`, 300_000, async () => {
		const list = rows(s, await call(s, '/stats/getLibraryCardStats'));
		return list
			.filter((l) => l.archived !== true)
			.map((l): JsLibrary => {
				const t = str(l.CollectionType);
				return {
					id: str(l.Id) || str(l.Name),
					name: str(l.Name) || 'Library',
					kind: t === 'movies' || t === 'tvshows' || t === 'music' ? t : 'other',
					items: num(l.Library_Count),
					seasons: num(l.Season_Count),
					episodes: num(l.Episode_Count),
					contentSec: Math.round(num(l.total_play_time) / TICKS),
					plays: num(l.Plays),
					watchedSec: num(l.total_playback_duration),
					lastPlayed: optStr(l.ActivityDateInserted),
					lastItem: optStr(l.ItemName)
				};
			})
			.sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || a.name.localeCompare(b.name));
	});
}
