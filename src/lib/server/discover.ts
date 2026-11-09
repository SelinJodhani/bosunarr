// Discover + requests through the user's Jellyseerr / Overseerr. Every TMDb lookup happens server-side via Seerr.
import { cached, call, invalidate, ServiceError } from './services/client';
import { getService, servicesOfType, type ServiceRecord } from './services/store';
import { library } from './library';
import type {
	Availability,
	CreateRequest,
	DiscoverDetail,
	DiscoverHome,
	DiscoverItem,
	DiscoverPage,
	DiscoverRow,
	Genre,
	LibraryLink,
	MediaType,
	Rating,
	RequestAction,
	RequestBrief,
	RequestFilter,
	RequestItem,
	RequestsResult,
	RequestStatus,
	SeasonInfo,
	SectionId
} from '$lib/discover/types';

/* ---------------------------------------------------------------- */
/* Seerr API shapes (only the fields we use)                         */
/* ---------------------------------------------------------------- */
type SeerrUser = { displayName?: string; username?: string; plexUsername?: string; jellyfinUsername?: string; email?: string };
type SeerrRequestRaw = {
	id: number;
	status: number;
	is4k: boolean;
	type?: MediaType;
	createdAt: string;
	requestedBy?: SeerrUser;
	modifiedBy?: SeerrUser | null;
	seasons?: Array<{ seasonNumber: number }>;
	media: { tmdbId: number; mediaType: MediaType; status?: number; status4k?: number };
};
type MediaInfo = {
	status?: number;
	status4k?: number;
	seasons?: Array<{ seasonNumber: number; status?: number; status4k?: number }>;
	requests?: SeerrRequestRaw[];
};
type SeerrResult = {
	id: number;
	mediaType: MediaType | 'person' | 'collection';
	title?: string;
	name?: string;
	posterPath?: string | null;
	backdropPath?: string | null;
	releaseDate?: string;
	firstAirDate?: string;
	voteAverage?: number;
	mediaInfo?: MediaInfo;
};
type SeerrPage = { page: number; totalPages: number; totalResults: number; results: SeerrResult[] };
type SeerrDetail = SeerrResult & {
	overview?: string;
	tagline?: string;
	runtime?: number;
	episodeRunTime?: number[];
	status?: string;
	genres?: Array<{ id: number; name: string }>;
	networks?: Array<{ name: string }>;
	productionCompanies?: Array<{ name: string }>;
	credits?: {
		cast?: Array<{ name: string; character?: string; profilePath?: string | null; order?: number }>;
		crew?: Array<{ name: string; job?: string }>;
	};
	externalIds?: { imdbId?: string | null; tvdbId?: number | null };
	seasons?: Array<{ seasonNumber: number; name?: string; episodeCount?: number; airDate?: string | null }>;
	releases?: { results?: Array<{ iso_3166_1: string; release_dates?: Array<{ certification?: string }> }> };
	contentRatings?: { results?: Array<{ iso_3166_1: string; rating?: string }> };
};
type RtRatings = { criticsScore?: number; audienceScore?: number; criticsRating?: string };
type CombinedRatings = { rt?: RtRatings; imdb?: { criticsScore?: number } };
type ArrServer = { id: number; name: string; is4k: boolean };

const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

/* ---------------------------------------------------------------- */
/* Helpers                                                           */
/* ---------------------------------------------------------------- */

/** The Seerr instance to use: a specific one, or the first configured. */
function seerr(serviceId?: number): ServiceRecord | undefined {
	if (serviceId != null && Number.isFinite(serviceId)) {
		const s = getService(serviceId);
		return s && (s.type === 'jellyseerr' || s.type === 'overseerr') ? s : undefined;
	}
	return servicesOfType('jellyseerr', 'overseerr')[0];
}

function requireSeerr(serviceId?: number): ServiceRecord {
	const s = seerr(serviceId);
	if (!s) throw new ServiceError(serviceId == null ? 'Add a Jellyseerr or Overseerr service in Settings to discover and request' : 'Not found', 404);
	return s;
}

const TMDB_PATH = /^\/[\w.-]+$/;
function tmdbImg(s: ServiceRecord, path: string | null | undefined, size: string): string | null {
	if (!path || !TMDB_PATH.test(path)) return null;
	return `/api/img/${s.id}?p=${encodeURIComponent(`/imageproxy/tmdb/t/p/${size}${path}`)}`;
}

function availability(n: number | undefined): Availability {
	switch (n) {
		case 5:
			return 'available';
		case 4:
			return 'partial';
		case 3:
			return 'processing';
		case 2:
			return 'pending';
		default:
			return 'none';
	}
}

const REQUEST_STATUS: Record<number, RequestStatus> = { 1: 'pending', 2: 'approved', 3: 'declined', 4: 'failed', 5: 'completed' };

const userName = (u?: SeerrUser | null) => u?.displayName || u?.username || u?.jellyfinUsername || u?.plexUsername || 'Unknown';

const yearOf = (d?: string | null) => {
	const y = Number((d ?? '').slice(0, 4));
	return y > 1800 ? y : null;
};

function item(s: ServiceRecord, r: SeerrResult): DiscoverItem | null {
	if (r.mediaType !== 'movie' && r.mediaType !== 'tv') return null;
	if (!Number.isInteger(r.id)) return null;
	return {
		key: `${r.mediaType}-${r.id}`,
		serviceId: s.id,
		tmdbId: r.id,
		mediaType: r.mediaType,
		title: r.title ?? r.name ?? `TMDb ${r.id}`,
		year: yearOf(r.releaseDate ?? r.firstAirDate),
		poster: tmdbImg(s, r.posterPath, 'w300_and_h450_face'),
		rating: r.voteAverage ? Math.round(r.voteAverage * 10) / 10 : null,
		status: availability(r.mediaInfo?.status),
		status4k: availability(r.mediaInfo?.status4k)
	};
}

/** Normalise and de-duplicate (TMDb paging can repeat items; a duplicate key would break keyed lists). */
function items(s: ServiceRecord, results: SeerrResult[] | undefined, seen = new Set<string>()): DiscoverItem[] {
	const out: DiscoverItem[] = [];
	for (const r of results ?? []) {
		const it = item(s, r);
		if (!it || seen.has(it.key)) continue;
		seen.add(it.key);
		out.push(it);
	}
	return out;
}

/* ---------------------------------------------------------------- */
/* Sections                                                          */
/* ---------------------------------------------------------------- */
const SECTIONS: Record<'trending' | 'movies' | 'tv' | 'movies-upcoming' | 'tv-upcoming', { title: string; path: string }> = {
	trending: { title: 'Trending', path: '/api/v1/discover/trending' },
	movies: { title: 'Popular movies', path: '/api/v1/discover/movies' },
	tv: { title: 'Popular series', path: '/api/v1/discover/tv' },
	'movies-upcoming': { title: 'Upcoming movies', path: '/api/v1/discover/movies/upcoming' },
	'tv-upcoming': { title: 'Upcoming series', path: '/api/v1/discover/tv/upcoming' }
};
const GENRE_SECTION = /^genre-(movie|tv)-(\d{1,7})$/;

function sectionPath(section: string, page: number): string {
	if (section in SECTIONS) return `${SECTIONS[section as keyof typeof SECTIONS].path}?page=${page}`;
	const g = section.match(GENRE_SECTION);
	if (g) return `/api/v1/discover/${g[1] === 'movie' ? 'movies' : 'tv'}?page=${page}&genre=${Number(g[2])}`;
	throw new ServiceError('Unknown section', 400);
}

function validPage(p: unknown): number {
	const n = Number(p ?? 1);
	if (!Number.isInteger(n) || n < 1 || n > 500) throw new ServiceError('Invalid page', 400);
	return n;
}

function toPage(s: ServiceRecord, r: SeerrPage): DiscoverPage {
	return { items: items(s, r.results), page: r.page ?? 1, totalPages: r.totalPages ?? 1, totalResults: r.totalResults ?? 0 };
}

function genres(s: ServiceRecord): Promise<Genre[]> {
	return cached(`disc:${s.id}:genres`, 6 * 3600_000, async () => {
		const [movie, tv] = await Promise.all(
			(['movie', 'tv'] as const).map((t) =>
				call<Array<{ id: number; name: string }>>(s, `/api/v1/genres/${t}`)
					.then((rows) => (Array.isArray(rows) ? rows : []).filter((g) => Number.isInteger(g.id) && g.name).map((g) => ({ id: g.id, name: g.name, mediaType: t })))
					.catch(() => [] as Genre[])
			)
		);
		return [...movie, ...tv];
	});
}

/** The Discover landing page: all built-in rows (page 1) and the genre list, in one request. */
export function discoverHome(): Promise<DiscoverHome> {
	const s = seerr();
	if (!s) return Promise.resolve({ serviceId: null, serviceName: null, rows: [], genres: [], errors: [] });
	return cached(`disc:${s.id}:home`, 5 * 60_000, async () => {
		const ids = Object.keys(SECTIONS) as Array<keyof typeof SECTIONS>;
		const [rowResults, genreList] = await Promise.all([
			Promise.allSettled(ids.map((id) => call<SeerrPage>(s, sectionPath(id, 1)))),
			genres(s)
		]);
		const rows: DiscoverRow[] = [];
		const errors: string[] = [];
		rowResults.forEach((r, i) => {
			if (r.status === 'fulfilled') {
				const list = items(s, r.value.results);
				if (list.length) rows.push({ id: ids[i], title: SECTIONS[ids[i]].title, items: list });
			} else errors.push(errMsg(r.reason));
		});
		if (errors.length === ids.length) throw new ServiceError(errors[0]);
		return { serviceId: s.id, serviceName: s.name, rows, genres: genreList, errors: [...new Set(errors)] };
	});
}

export function discoverList(section: string, page: unknown, serviceId?: number): Promise<DiscoverPage> {
	const s = requireSeerr(serviceId);
	const p = validPage(page);
	const path = sectionPath(section, p);
	return cached(`disc:${s.id}:list:${section}:${p}`, 5 * 60_000, async () => toPage(s, await call<SeerrPage>(s, path)));
}

/** Seerr rejects some characters unless they are percent-encoded strictly (RFC 3986). */
const strictEncode = (q: string) => encodeURIComponent(q).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());

export function discoverSearch(query: unknown, page: unknown, serviceId?: number): Promise<DiscoverPage> {
	const s = requireSeerr(serviceId);
	const q = String(query ?? '').trim();
	if (!q) return Promise.resolve({ items: [], page: 1, totalPages: 1, totalResults: 0 });
	if (q.length > 100) throw new ServiceError('Search is too long', 400);
	const p = validPage(page);
	return cached(`disc:${s.id}:search:${p}:${q.toLowerCase()}`, 2 * 60_000, async () =>
		toPage(s, await call<SeerrPage>(s, `/api/v1/search?query=${strictEncode(q)}&page=${p}`))
	);
}

/* ---------------------------------------------------------------- */
/* Detail                                                            */
/* ---------------------------------------------------------------- */

/** Does this Seerr have a 4K Radarr/Sonarr configured? */
function has4k(s: ServiceRecord, type: MediaType): Promise<boolean> {
	return cached(`disc:${s.id}:4k:${type}`, 10 * 60_000, () =>
		call<ArrServer[]>(s, `/api/v1/service/${type === 'movie' ? 'radarr' : 'sonarr'}`)
			.then((rows) => Array.isArray(rows) && rows.some((r) => r.is4k))
			.catch(() => false)
	);
}

function ratings(s: ServiceRecord, type: MediaType, id: number, tmdb: number | undefined): Promise<Rating[]> {
	return cached(`disc:${s.id}:ratings:${type}:${id}`, 6 * 3600_000, async () => {
		const out: Rating[] = [];
		if (tmdb) out.push({ source: 'tmdb', label: 'TMDb', value: (Math.round(tmdb * 10) / 10).toFixed(1) });
		let rt: RtRatings | undefined;
		let imdb: number | undefined;
		if (type === 'movie') {
			const combined = await call<CombinedRatings>(s, `/api/v1/movie/${id}/ratingscombined`).catch(() => null);
			if (combined && typeof combined === 'object') {
				rt = combined.rt;
				imdb = combined.imdb?.criticsScore;
			} else rt = (await call<RtRatings>(s, `/api/v1/movie/${id}/ratings`).catch(() => undefined)) ?? undefined;
		} else rt = (await call<RtRatings>(s, `/api/v1/tv/${id}/ratings`).catch(() => undefined)) ?? undefined;
		if (imdb) out.push({ source: 'imdb', label: 'IMDb', value: imdb.toFixed(1) });
		if (rt && typeof rt.criticsScore === 'number') out.push({ source: 'rt', label: 'Critics', value: `${rt.criticsScore}%` });
		if (rt && typeof rt.audienceScore === 'number') out.push({ source: 'rtAudience', label: 'Audience', value: `${rt.audienceScore}%` });
		return out;
	});
}

async function libraryLinks(type: MediaType, tmdbId: number, tvdbId: number | null): Promise<LibraryLink[]> {
	const lib = await library().catch(() => null);
	if (!lib) return [];
	const kind = type === 'movie' ? 'movie' : 'series';
	return lib.items
		.filter((i) => i.kind === kind && (i.tmdbId === tmdbId || (tvdbId != null && i.tvdbId === tvdbId)))
		.map((i) => ({ href: `/library/${i.serviceId}/${i.kind}/${i.id}`, serviceName: i.serviceName, is4k: i.is4k }));
}

function certification(d: SeerrDetail, type: MediaType): string | null {
	if (type === 'movie') {
		const rel = d.releases?.results ?? [];
		const pickC = (c: string) => rel.find((r) => r.iso_3166_1 === c)?.release_dates?.find((x) => x.certification)?.certification;
		return pickC('US') || pickC('GB') || pickC('IN') || null;
	}
	const cr = d.contentRatings?.results ?? [];
	return cr.find((r) => r.iso_3166_1 === 'US')?.rating || cr[0]?.rating || null;
}

function brief(r: SeerrRequestRaw): RequestBrief {
	return {
		id: r.id,
		status: REQUEST_STATUS[r.status] ?? 'pending',
		is4k: Boolean(r.is4k),
		seasons: (r.seasons ?? []).map((x) => x.seasonNumber),
		requestedBy: userName(r.requestedBy),
		createdAt: r.createdAt
	};
}

function validId(n: unknown): number {
	const v = Number(n);
	if (!Number.isInteger(v) || v <= 0 || v > 1e9) throw new ServiceError('Not found', 404);
	return v;
}
function validType(t: unknown): MediaType {
	if (t !== 'movie' && t !== 'tv') throw new ServiceError('Not found', 404);
	return t;
}

function fetchDetail(s: ServiceRecord, type: MediaType, id: number) {
	return cached(`disc:${s.id}:detail:${type}:${id}`, 30_000, () => call<SeerrDetail>(s, `/api/v1/${type}/${id}`));
}

export async function discoverDetail(serviceId: unknown, type: unknown, id: unknown): Promise<DiscoverDetail> {
	const s = requireSeerr(validId(serviceId));
	const t = validType(type);
	const tmdbId = validId(id);
	const d = await fetchDetail(s, t, tmdbId);
	const base = item(s, { ...d, mediaType: t, id: tmdbId });
	if (!base) throw new ServiceError('Not found', 404);
	const tvdbId = d.externalIds?.tvdbId ?? null;
	const [rate, can4k, inLibrary] = await Promise.all([ratings(s, t, tmdbId, d.voteAverage), has4k(s, t), libraryLinks(t, tmdbId, tvdbId)]);

	const mi = d.mediaInfo;
	const seasonStatus = new Map((mi?.seasons ?? []).map((x) => [x.seasonNumber, x]));
	// Requested seasons count as pending/processing even before Seerr updates the media's own season rows.
	const requested = new Map<string, Availability>();
	for (const r of mi?.requests ?? []) {
		const st = REQUEST_STATUS[r.status];
		if (st !== 'pending' && st !== 'approved') continue;
		for (const se of r.seasons ?? []) requested.set(`${r.is4k ? 1 : 0}:${se.seasonNumber}`, st === 'pending' ? 'pending' : 'processing');
	}
	const seasons: SeasonInfo[] =
		t === 'tv'
			? (d.seasons ?? [])
					.filter((x) => Number.isInteger(x.seasonNumber) && x.seasonNumber > 0)
					.sort((a, b) => a.seasonNumber - b.seasonNumber)
					.map((x) => {
						const st = seasonStatus.get(x.seasonNumber);
						const merge = (a: Availability, req?: Availability) => (a === 'none' && req ? req : a);
						return {
							number: x.seasonNumber,
							name: x.name || `Season ${x.seasonNumber}`,
							episodes: x.episodeCount ?? 0,
							airDate: x.airDate ?? null,
							status: merge(availability(st?.status), requested.get(`0:${x.seasonNumber}`)),
							status4k: merge(availability(st?.status4k), requested.get(`1:${x.seasonNumber}`))
						};
					})
			: [];

	const runtime = t === 'movie' ? d.runtime : d.episodeRunTime?.[0];
	return {
		...base,
		serviceName: s.name,
		backdrop: tmdbImg(s, d.backdropPath, 'w1920_and_h800_multi_faces'),
		overview: d.overview || null,
		tagline: d.tagline || null,
		runtime: runtime || null,
		certification: certification(d, t),
		genres: (d.genres ?? []).map((g) => g.name),
		date: (t === 'movie' ? d.releaseDate : d.firstAirDate) || null,
		releaseStatus: d.status || null,
		network: (t === 'tv' ? d.networks?.[0]?.name : d.productionCompanies?.[0]?.name) ?? null,
		director: d.credits?.crew?.find((c) => c.job === 'Director')?.name ?? null,
		cast: (d.credits?.cast ?? [])
			.slice()
			.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
			.slice(0, 20)
			.map((c) => ({ name: c.name, character: c.character || null, photo: tmdbImg(s, c.profilePath, 'w185') })),
		ratings: rate,
		seasons,
		requests: (mi?.requests ?? []).map(brief).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
		inLibrary,
		can4k,
		imdbId: d.externalIds?.imdbId || null,
		tvdbId,
		openUrl: `${s.externalUrl ?? s.url}/${t}/${tmdbId}`
	};
}

/* ---------------------------------------------------------------- */
/* Requests                                                          */
/* ---------------------------------------------------------------- */
type TitleInfo = { title?: string; name?: string; posterPath?: string; releaseDate?: string; firstAirDate?: string };

/** Title + poster for a request row. Same cache key and call as the Dashboard widget, so they share it. */
function titleInfo(s: ServiceRecord, type: MediaType, tmdbId: number): Promise<TitleInfo> {
	return cached(`seerr:${s.id}:${type}:${tmdbId}`, 6 * 3600_000, () => call<TitleInfo>(s, `/api/v1/${type}/${tmdbId}`)).catch(() => ({}) as TitleInfo);
}

const FILTERS: Record<RequestFilter, string> = {
	pending: 'pending',
	approved: 'processing',
	available: 'available',
	failed: 'failed',
	all: 'all'
};

export async function requestList(filter: unknown, page: unknown): Promise<RequestsResult> {
	const f = (typeof filter === 'string' && filter in FILTERS ? filter : 'pending') as RequestFilter;
	const p = validPage(page);
	if (p > 10) throw new ServiceError('Invalid page', 400);
	const take = 25 * p;
	return cached(`disc:requests:${f}:${p}`, 10_000, async () => {
		const services = servicesOfType('jellyseerr', 'overseerr');
		const results = await Promise.allSettled(
			services.map(async (s) => {
				const r = await call<{ pageInfo?: { results?: number }; results?: SeerrRequestRaw[] }>(
					s,
					`/api/v1/request?take=${take}&skip=0&filter=${FILTERS[f]}&sort=added`
				);
				const rows = Array.isArray(r.results) ? r.results : [];
				const list = await Promise.all(
					rows.map(async (q): Promise<RequestItem> => {
						const type: MediaType = q.type ?? q.media?.mediaType ?? 'movie';
						const info = await titleInfo(s, type, q.media.tmdbId);
						return {
							key: `${s.id}:${q.id}`,
							serviceId: s.id,
							serviceName: s.name,
							id: q.id,
							tmdbId: q.media.tmdbId,
							mediaType: type,
							title: info.title ?? info.name ?? `TMDb ${q.media.tmdbId}`,
							year: yearOf(info.releaseDate ?? info.firstAirDate),
							poster: tmdbImg(s, info.posterPath, 'w300_and_h450_face'),
							status: REQUEST_STATUS[q.status] ?? 'pending',
							media: availability(q.is4k ? q.media.status4k : q.media.status),
							is4k: Boolean(q.is4k),
							seasons: (q.seasons ?? []).map((x) => x.seasonNumber).sort((a, b) => a - b),
							requestedBy: userName(q.requestedBy),
							modifiedBy: q.modifiedBy ? userName(q.modifiedBy) : null,
							createdAt: q.createdAt
						};
					})
				);
				return { list, more: (r.pageInfo?.results ?? 0) > take };
			})
		);
		const errors: RequestsResult['errors'] = [];
		const seen = new Set<string>();
		const all: RequestItem[] = [];
		let hasMore = false;
		results.forEach((r, i) => {
			if (r.status === 'rejected') return errors.push({ service: services[i].name, error: errMsg(r.reason) });
			hasMore ||= r.value.more;
			for (const it of r.value.list) if (!seen.has(it.key)) (seen.add(it.key), all.push(it));
		});
		if (services.length && errors.length === services.length) throw new ServiceError(errors[0].error);
		all.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
		return { items: all, hasMore, errors };
	});
}

function afterWrite(s: ServiceRecord) {
	invalidate(`disc:${s.id}:`);
	invalidate('disc:requests:');
	invalidate('w:requests');
}

export async function createRequest(body: Partial<CreateRequest>): Promise<{ ok: true }> {
	const s = requireSeerr(validId(body?.serviceId));
	const type = validType(body?.mediaType);
	const tmdbId = validId(body?.tmdbId);
	const is4k = body?.is4k === true;
	if (body?.is4k != null && typeof body.is4k !== 'boolean') throw new ServiceError('Invalid 4K flag', 400);
	if (is4k && !(await has4k(s, type))) throw new ServiceError(`${s.name} has no 4K ${type === 'movie' ? 'Radarr' : 'Sonarr'} configured`, 400);

	const payload: Record<string, unknown> = { mediaType: type, mediaId: tmdbId, is4k };
	if (type === 'tv') {
		const raw = body?.seasons;
		if (!Array.isArray(raw) || raw.length === 0 || raw.length > 100) throw new ServiceError('Choose at least one season', 400);
		const seasons = [...new Set(raw.map(Number))];
		const d = await fetchDetail(s, type, tmdbId);
		const known = new Set((d.seasons ?? []).map((x) => x.seasonNumber).filter((n) => n > 0));
		if (seasons.some((n) => !Number.isInteger(n) || !known.has(n))) throw new ServiceError('Unknown season', 400);
		payload.seasons = seasons.sort((a, b) => a - b);
		if (d.externalIds?.tvdbId) payload.tvdbId = d.externalIds.tvdbId;
	} else if (body?.seasons != null) throw new ServiceError('Movies have no seasons', 400);

	try {
		await call(s, '/api/v1/request', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
	} catch (e) {
		if (e instanceof ServiceError && e.status === 409) throw new ServiceError('Already requested', 409);
		throw e;
	} finally {
		afterWrite(s);
	}
	return { ok: true };
}

export async function requestAction(body: Partial<RequestAction>): Promise<{ ok: true }> {
	const s = requireSeerr(validId(body?.serviceId));
	const id = validId(body?.requestId);
	const action = body?.action;
	if (action !== 'approve' && action !== 'decline' && action !== 'delete') throw new ServiceError('Unknown action', 400);
	try {
		if (action === 'delete') await call(s, `/api/v1/request/${id}`, { method: 'DELETE' });
		else await call(s, `/api/v1/request/${id}/${action}`, { method: 'POST' });
	} finally {
		afterWrite(s);
	}
	return { ok: true };
}
