/**
 * Bazarr adapter for the dedicated service screen.
 *
 * Routes and payloads were taken from Bazarr's source (bazarr/api/*, flask-restx). Notes:
 * - Auth: `X-API-KEY` (handled by `call()`).
 * - Arguments are parsed by flask-restx reqparse from query/form values, so writes send them as query params.
 * - `/api/badges` counts missing *languages*; `/api/{episodes,movies}/wanted` `total` counts *items*.
 * - Bazarr's own `/images/...` poster proxy needs a browser session (API keys aren't accepted), so posters
 *   come from the matching Sonarr/Radarr in Bosunarr's library instead (matched by arr id + title).
 * - Provider "reset" is global in Bazarr (no per-provider reset exists).
 */
import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import { library } from '../library';
import type { LibraryItem } from '$lib/library/types';
import type {
	ActionResult,
	BazarrAction,
	BazarrKind,
	BazarrOverview,
	BazarrProfile,
	BazarrProvider,
	HistoryAction,
	HistoryItem,
	HistoryPage,
	SubLang,
	WantedItem,
	WantedPage
} from '$lib/servicepages/bazarr/types';

export const PAGE_SIZE = 25;
const SEARCH_TIMEOUT_MS = 120_000;

const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

/* ---------------------------------------------------------------- */
/* Input validation                                                  */
/* ---------------------------------------------------------------- */
export function parseKind(v: unknown): BazarrKind {
	if (v === 'episodes' || v === 'movies') return v;
	throw new ServiceError('kind must be "episodes" or "movies"', 400);
}

export function parsePage(v: unknown): number {
	if (v === null || v === undefined || v === '') return 1;
	const n = Number(v);
	if (!Number.isInteger(n) || n < 1 || n > 100_000) throw new ServiceError('Invalid page', 400);
	return n;
}

function parseId(v: unknown): number {
	const n = typeof v === 'number' ? v : Number(v);
	if (!Number.isInteger(n) || n < 1 || n > 2 ** 31) throw new ServiceError('Invalid id', 400);
	return n;
}

export function parseAction(body: unknown): BazarrAction {
	const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
	switch (b.action) {
		case 'search':
			return { action: 'search', kind: parseKind(b.kind), id: parseId(b.id) };
		case 'searchAll':
			return { action: 'searchAll', kind: b.kind === 'all' ? 'all' : parseKind(b.kind) };
		case 'resetProviders':
			return { action: 'resetProviders' };
		default:
			throw new ServiceError('Unknown action', 400);
	}
}

/* ---------------------------------------------------------------- */
/* Bazarr payloads                                                   */
/* ---------------------------------------------------------------- */
type BzLang = { name?: string; code2?: string; code3?: string; forced?: boolean; hi?: boolean };
type BzEpisodeWanted = {
	seriesTitle: string;
	episode_number: string;
	episodeTitle: string;
	missing_subtitles: BzLang[];
	sonarrSeriesId: number;
	sonarrEpisodeId: number;
};
type BzMovieWanted = { title: string; missing_subtitles: BzLang[]; radarrId: number };
type BzHistory = {
	seriesTitle?: string;
	episode_number?: string;
	episodeTitle?: string;
	title?: string;
	sonarrEpisodeId?: number;
	radarrId?: number;
	action: number;
	language?: BzLang | null;
	provider?: string | null;
	score?: string | null;
	timestamp?: string | null;
	parsed_timestamp?: string | null;
	upgradable?: boolean;
};
type BzProfile = { profileId: number; name: string; cutoff: number | null; items: Array<{ id: number; language: string; hi?: string | boolean; forced?: string | boolean }> };

const truthy = (v: unknown) => v === true || v === 'True' || v === 'true';

function lang(l: BzLang | null | undefined): SubLang | null {
	if (!l?.code2 && !l?.name) return null;
	return { code: l.code2 ?? l.code3 ?? '', name: l.name ?? l.code2 ?? '?', hi: Boolean(l.hi), forced: Boolean(l.forced) };
}

/** "1x5" → "S01E05" */
function episodeLabel(n: string | undefined): string | null {
	const m = n?.match(/^(\d+)x(\d+)$/);
	if (!m) return n ?? null;
	return `S${m[1].padStart(2, '0')}E${m[2].padStart(2, '0')}`;
}

const HISTORY_ACTIONS: HistoryAction[] = ['deleted', 'downloaded', 'manual', 'upgraded', 'uploaded', 'synced', 'translated', 'extracted'];

/* ---------------------------------------------------------------- */
/* Posters via the library                                           */
/* ---------------------------------------------------------------- */
const norm = (t: string) => t.toLowerCase().replace(/[^a-z0-9]+/g, '');

async function posterIndex(): Promise<Map<string, LibraryItem[]>> {
	const lib = await library().catch(() => null);
	const idx = new Map<string, LibraryItem[]>();
	for (const it of lib?.items ?? []) {
		const k = `${it.kind}:${it.id}`;
		const list = idx.get(k);
		if (list) list.push(it);
		else idx.set(k, [it]);
	}
	return idx;
}

/** Pick the library entry for a Bazarr item: same arr id and title; prefer non-4K copies. */
function match(idx: Map<string, LibraryItem[]>, kind: 'series' | 'movie', id: number, title: string): LibraryItem | null {
	const candidates = idx.get(`${kind}:${id}`) ?? [];
	const t = norm(title);
	const same = candidates.filter((c) => norm(c.title) === t);
	return same.find((c) => !c.is4k) ?? same[0] ?? null;
}

/* ---------------------------------------------------------------- */
/* Overview                                                          */
/* ---------------------------------------------------------------- */
function languageNames(s: ServiceRecord): Promise<Map<string, string>> {
	return cached(`bz:${s.id}:langs`, 10 * 60_000, async () => {
		const rows = await call<Array<{ name: string; code2: string }>>(s, '/api/system/languages');
		return new Map(Array.isArray(rows) ? rows.map((r) => [r.code2, r.name]) : []);
	});
}

export function overview(s: ServiceRecord): Promise<BazarrOverview> {
	return cached(`bz:${s.id}:overview`, 15_000, async () => {
		const parts = await Promise.allSettled([
			call<{ data: Record<string, unknown> }>(s, '/api/system/status'),
			call<Record<string, unknown>>(s, '/api/badges'),
			call<{ data: Array<{ object: string; issue: string }> }>(s, '/api/system/health'),
			call<{ data: Array<{ name: string; status: string; retry: string }> }>(s, '/api/providers'),
			call<BzProfile[]>(s, '/api/system/languages/profiles'),
			languageNames(s)
		]);
		const [status, badges, health, providers, profiles, names] = parts;
		const labels = ['status', 'badges', 'health', 'providers', 'languages profiles', 'languages'];
		const errors = parts.flatMap((p, i) => (p.status === 'rejected' ? [`${labels[i]}: ${errMsg(p.reason)}`] : []));
		// If nothing came back, it's the service that's down — surface that as an error.
		if (errors.length === parts.length && parts[0].status === 'rejected') throw parts[0].reason;

		const st = status.status === 'fulfilled' && status.value && typeof status.value === 'object' ? (status.value.data ?? {}) : {};
		const bd = badges.status === 'fulfilled' && badges.value && typeof badges.value === 'object' ? badges.value : {};
		const nameOf = names.status === 'fulfilled' ? names.value : new Map<string, string>();
		const str = (v: unknown) => (typeof v === 'string' && v ? v : null);
		const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

		const prov: BazarrProvider[] =
			providers.status === 'fulfilled' && Array.isArray(providers.value?.data)
				? providers.value.data.map((p) => {
						const issue = p.status && p.status !== 'Good' ? p.status : null;
						return { name: p.name, issue, until: issue && p.retry && p.retry !== '-' ? p.retry : null };
					})
				: [];

		const prof: BazarrProfile[] =
			profiles.status === 'fulfilled' && Array.isArray(profiles.value)
				? profiles.value.map((p) => {
						const languages = (p.items ?? []).map((i) => ({
							code: i.language,
							name: nameOf.get(i.language) ?? i.language,
							hi: truthy(i.hi),
							forced: truthy(i.forced)
						}));
						// cutoff is an item id (65535 = "any language")
						const cut = p.cutoff === 65535 ? 'Any' : (languages[(p.items ?? []).findIndex((i) => i.id === p.cutoff)]?.name ?? null);
						return { id: p.profileId, name: p.name, cutoff: cut, languages };
					})
				: [];

		return {
			version: str(st.bazarr_version),
			sonarrVersion: str(st.sonarr_version),
			radarrVersion: str(st.radarr_version),
			startTime: typeof st.start_time === 'number' ? st.start_time : null,
			wanted: { episodes: num(bd.episodes), movies: num(bd.movies) },
			sonarrSignalr: str(bd.sonarr_signalr),
			radarrSignalr: str(bd.radarr_signalr),
			health: health.status === 'fulfilled' && Array.isArray(health.value?.data) ? health.value.data.map((h) => ({ object: String(h.object ?? ''), issue: String(h.issue ?? '') })) : [],
			providers: prov,
			profiles: prof,
			errors
		};
	});
}

/* ---------------------------------------------------------------- */
/* Wanted                                                            */
/* ---------------------------------------------------------------- */
function episodeItem(x: BzEpisodeWanted, idx: Map<string, LibraryItem[]>): WantedItem {
	const lib = match(idx, 'series', x.sonarrSeriesId, x.seriesTitle);
	const ep = episodeLabel(x.episode_number);
	return {
		key: `episodes:${x.sonarrEpisodeId}`,
		kind: 'episodes',
		id: x.sonarrEpisodeId,
		seriesId: x.sonarrSeriesId,
		title: x.seriesTitle,
		subtitle: [ep, x.episodeTitle].filter(Boolean).join(' · ') || null,
		missing: (x.missing_subtitles ?? []).map(lang).filter((l): l is SubLang => l !== null),
		poster: lib?.poster ?? null,
		href: lib ? `/library/${lib.serviceId}/series/${lib.id}` : null
	};
}

function movieItem(x: BzMovieWanted, idx: Map<string, LibraryItem[]>): WantedItem {
	const lib = match(idx, 'movie', x.radarrId, x.title);
	return {
		key: `movies:${x.radarrId}`,
		kind: 'movies',
		id: x.radarrId,
		seriesId: null,
		title: x.title,
		subtitle: lib?.year ? String(lib.year) : null,
		missing: (x.missing_subtitles ?? []).map(lang).filter((l): l is SubLang => l !== null),
		poster: lib?.poster ?? null,
		href: lib ? `/library/${lib.serviceId}/movie/${lib.id}` : null
	};
}

export function wanted(s: ServiceRecord, kind: BazarrKind, page: number): Promise<WantedPage> {
	return cached(`bz:${s.id}:wanted:${kind}:${page}`, 20_000, async () => {
		const start = (page - 1) * PAGE_SIZE;
		const [r, idx] = await Promise.all([
			call<{ data: unknown[]; total: number }>(s, `/api/${kind}/wanted?start=${start}&length=${PAGE_SIZE}`),
			posterIndex()
		]);
		if (!r || typeof r !== 'object' || !Array.isArray(r.data)) throw new ServiceError(`${s.name} returned an unexpected wanted list`);
		const items =
			kind === 'episodes'
				? (r.data as BzEpisodeWanted[]).map((x) => episodeItem(x, idx))
				: (r.data as BzMovieWanted[]).map((x) => movieItem(x, idx));
		return { kind, items, total: Number(r.total) || 0, page, pageSize: PAGE_SIZE };
	});
}

/* ---------------------------------------------------------------- */
/* History                                                           */
/* ---------------------------------------------------------------- */
export function history(s: ServiceRecord, kind: BazarrKind, page: number): Promise<HistoryPage> {
	return cached(`bz:${s.id}:history:${kind}:${page}`, 20_000, async () => {
		const start = (page - 1) * PAGE_SIZE;
		const r = await call<{ data: BzHistory[]; total: number }>(s, `/api/${kind}/history?start=${start}&length=${PAGE_SIZE}`);
		if (!r || typeof r !== 'object' || !Array.isArray(r.data)) throw new ServiceError(`${s.name} returned an unexpected history list`);
		const items = r.data.map((x, i): HistoryItem => {
			const episode = kind === 'episodes';
			const ep = episodeLabel(x.episode_number);
			return {
				key: `${kind}:${start + i}:${x.sonarrEpisodeId ?? x.radarrId ?? 0}`,
				kind,
				title: (episode ? x.seriesTitle : x.title) ?? 'Unknown',
				subtitle: episode ? [ep, x.episodeTitle].filter(Boolean).join(' · ') || null : null,
				action: HISTORY_ACTIONS[x.action] ?? 'other',
				language: lang(x.language),
				provider: x.provider || null,
				score: x.score || null,
				when: x.timestamp || null,
				at: x.parsed_timestamp || null,
				upgradable: Boolean(x.upgradable)
			};
		});
		return { kind, items, total: Number(r.total) || 0, page, pageSize: PAGE_SIZE };
	});
}

/* ---------------------------------------------------------------- */
/* Actions                                                           */
/* ---------------------------------------------------------------- */
const qs = (o: Record<string, string | number>) => new URLSearchParams(Object.entries(o).map(([k, v]) => [k, String(v)])).toString();

async function searchOne(s: ServiceRecord, kind: BazarrKind, id: number): Promise<ActionResult> {
	// Re-read the item from Bazarr instead of trusting languages sent by the browser.
	const path = kind === 'episodes' ? `/api/episodes/wanted?episodeid[]=${id}` : `/api/movies/wanted?radarrid[]=${id}`;
	const r = await call<{ data: Array<BzEpisodeWanted & BzMovieWanted> }>(s, path);
	const row = Array.isArray(r?.data) ? r.data.find((x) => (kind === 'episodes' ? x.sonarrEpisodeId : x.radarrId) === id) : undefined;
	if (!row) throw new ServiceError('That item no longer needs subtitles', 404);
	const missing = (row.missing_subtitles ?? []).filter((l) => l.code2);
	if (!missing.length) throw new ServiceError('That item no longer needs subtitles', 404);

	const signal = () => AbortSignal.timeout(SEARCH_TIMEOUT_MS);
	for (const l of missing) {
		const params: Record<string, string | number> = {
			language: l.code2!,
			forced: l.forced ? 'true' : 'false',
			hi: l.hi ? 'true' : 'false'
		};
		if (kind === 'episodes') {
			params.seriesid = row.sonarrSeriesId;
			params.episodeid = id;
			await call(s, `/api/episodes/subtitles?${qs(params)}`, { method: 'PATCH', signal: signal() });
		} else {
			params.radarrid = id;
			await call(s, `/api/movies/subtitles?${qs(params)}`, { method: 'PATCH', signal: signal() });
		}
	}
	invalidate(`bz:${s.id}:`);
	const after = await call<{ data: Array<{ missing_subtitles: BzLang[] }> }>(s, path).catch(() => null);
	const left = after?.data?.[0]?.missing_subtitles?.length ?? 0;
	const name = kind === 'episodes' ? `${row.seriesTitle} ${episodeLabel(row.episode_number) ?? ''}`.trim() : row.title;
	return {
		message: left === 0 ? `Found subtitles for ${name}` : left < missing.length ? `Found some subtitles for ${name}; ${left} still missing` : `No subtitles found yet for ${name}`
	};
}

const TASKS: Record<BazarrKind, string> = {
	episodes: 'wanted_search_missing_subtitles_series',
	movies: 'wanted_search_missing_subtitles_movies'
};

export async function runAction(s: ServiceRecord, a: BazarrAction): Promise<ActionResult> {
	switch (a.action) {
		case 'search':
			return searchOne(s, a.kind, a.id);
		case 'searchAll': {
			const kinds: BazarrKind[] = a.kind === 'all' ? ['episodes', 'movies'] : [a.kind];
			// Runs Bazarr's scheduled "search wanted" job now; it works in the background.
			for (const k of kinds) await call(s, `/api/system/tasks?${qs({ taskid: TASKS[k] })}`, { method: 'POST' });
			invalidate(`bz:${s.id}:`);
			const what = a.kind === 'all' ? 'episodes and movies' : a.kind;
			return { message: `Started searching all wanted ${what}` };
		}
		case 'resetProviders':
			await call(s, `/api/providers?${qs({ action: 'reset' })}`, { method: 'POST' });
			invalidate(`bz:${s.id}:`);
			return { message: 'Providers reset' };
	}
}
