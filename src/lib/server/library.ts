import { cached, call, ServiceError } from './services/client';
import { getService, servicesOfType, type ServiceRecord } from './services/store';
import type { CastMember, Episode, FileInfo, LibraryDetail, LibraryItem, LibraryKind, LibraryResult, LibraryStatus, Season } from '$lib/library/types';

/* ---------------------------------------------------------------- */
/* Arr API shapes (only the fields we use)                           */
/* ---------------------------------------------------------------- */
type ArrImage = { coverType: string; url?: string; remoteUrl?: string };
type Ratings = { value?: number; imdb?: { value?: number }; tmdb?: { value?: number } };
type Common = {
	id: number;
	title: string;
	sortTitle?: string;
	titleSlug?: string;
	year?: number;
	images?: ArrImage[];
	monitored: boolean;
	added?: string;
	ratings?: Ratings;
	genres?: string[];
	qualityProfileId?: number;
	overview?: string;
	runtime?: number;
	certification?: string;
	path?: string;
	rootFolderPath?: string;
	imdbId?: string;
	tmdbId?: number;
	tags?: number[];
};
type SonarrSeries = Common & {
	status?: string;
	network?: string;
	tvdbId?: number;
	nextAiring?: string;
	statistics?: { episodeFileCount?: number; episodeCount?: number; totalEpisodeCount?: number; sizeOnDisk?: number };
	seasons?: Array<{
		seasonNumber: number;
		monitored: boolean;
		statistics?: { episodeFileCount?: number; episodeCount?: number; totalEpisodeCount?: number; sizeOnDisk?: number };
	}>;
};
type MediaInfo = {
	videoCodec?: string;
	audioCodec?: string;
	audioChannels?: number;
	resolution?: string;
	audioLanguages?: string;
};
type ArrQuality = { quality?: { name?: string; resolution?: number } };
type RadarrFile = {
	path?: string;
	relativePath?: string;
	size?: number;
	dateAdded?: string;
	quality?: ArrQuality;
	mediaInfo?: MediaInfo;
	languages?: Array<{ name: string }>;
	releaseGroup?: string;
};
type RadarrMovie = Common & {
	hasFile?: boolean;
	isAvailable?: boolean;
	status?: string;
	sizeOnDisk?: number;
	studio?: string;
	inCinemas?: string;
	digitalRelease?: string;
	movieFile?: RadarrFile;
};
type SonarrEpisode = {
	id: number;
	seasonNumber: number;
	episodeNumber: number;
	title?: string;
	airDateUtc?: string;
	monitored: boolean;
	hasFile: boolean;
	episodeFileId?: number;
	overview?: string;
};
type SonarrEpisodeFile = { id: number; size?: number; quality?: ArrQuality };
type Credit = {
	personName: string;
	character?: string;
	type: string;
	order?: number;
	images?: ArrImage[];
};

/* ---------------------------------------------------------------- */
/* Images                                                            */
/* ---------------------------------------------------------------- */

/** Arr image URLs include the instance's URL base (e.g. /sonarr/MediaCover/…); the service URL already has it. */
function arrImagePath(s: ServiceRecord, url: string): string {
	const base = new URL(s.url).pathname.replace(/\/+$/, '');
	let p = url.startsWith('/') ? url : '/' + url;
	if (base && p.startsWith(base + '/')) p = p.slice(base.length);
	return p;
}

/** Proxied arr cover; `size` picks the arr's pre-scaled variant (poster-500.jpg, fanart-360.jpg …). */
export function arrImage(s: ServiceRecord, images: ArrImage[] | undefined, type: 'poster' | 'fanart', size?: number): string | null {
	const im = images?.find((i) => i.coverType === type);
	if (!im?.url) return null;
	let p = arrImagePath(s, im.url);
	if (size) p = p.replace(new RegExp(`/${type}\\.jpg`), `/${type}-${size}.jpg`);
	return `/api/img/${s.id}?p=${encodeURIComponent(p)}`;
}

/** TMDb images go through the user's Jellyseerr/Overseerr when there is one (it may have better egress), else direct. */
function tmdbImage(remoteUrl: string | undefined, size = 'w185'): string | null {
	const m = remoteUrl?.match(/image\.tmdb\.org\/t\/p\/\w+(\/[\w.-]+)$/);
	if (!m) return null;
	const seerr = servicesOfType('jellyseerr', 'overseerr')[0];
	if (seerr) return `/api/img/${seerr.id}?p=${encodeURIComponent(`/imageproxy/tmdb/t/p/${size}${m[1]}`)}`;
	return `/api/img/tmdb?p=${encodeURIComponent(`/t/p/${size}${m[1]}`)}`;
}

/* ---------------------------------------------------------------- */
/* Lookups                                                           */
/* ---------------------------------------------------------------- */
type Named = { id: number; name?: string; label?: string };

function lookup(s: ServiceRecord, path: '/api/v3/qualityprofile' | '/api/v3/tag'): Promise<Map<number, string>> {
	return cached(`lib:${s.id}:${path}`, 10 * 60_000, async () => {
		const rows = await call<Named[]>(s, path).catch(() => [] as Named[]);
		return new Map(rows.map((r) => [r.id, r.name ?? r.label ?? String(r.id)]));
	});
}

export const UHD = /2160|4k|uhd|ultra[\s-]?hd/i;

/** Quality profile names by id (cached). */
export const qualityProfiles = (s: ServiceRecord) => lookup(s, '/api/v3/qualityprofile');

function rating(r?: Ratings): number | null {
	const v = r?.imdb?.value || r?.tmdb?.value || r?.value;
	return v ? Math.round(v * 10) / 10 : null;
}

function sortTitle(title: string, sort?: string) {
	return (sort || title).toLowerCase().replace(/^(the|a|an)\s+/, '');
}

function openUrl(s: ServiceRecord, kind: LibraryKind, slug?: string) {
	if (!slug) return null;
	return `${s.externalUrl ?? s.url}/${kind === 'movie' ? 'movie' : 'series'}/${slug}`;
}

/* ---------------------------------------------------------------- */
/* Normalisers                                                       */
/* ---------------------------------------------------------------- */

function seriesStatus(x: SonarrSeries): LibraryStatus {
	const have = x.statistics?.episodeFileCount ?? 0;
	const want = x.statistics?.episodeCount ?? 0;
	if (want === 0) return have > 0 ? 'downloaded' : x.monitored ? 'upcoming' : 'unmonitored';
	if (have >= want) return 'downloaded';
	if (!x.monitored) return have > 0 ? 'partial' : 'unmonitored';
	return have === 0 ? 'missing' : 'partial';
}

function movieStatus(x: RadarrMovie): LibraryStatus {
	if (x.hasFile) return 'downloaded';
	if (!x.monitored) return 'unmonitored';
	return x.isAvailable === false ? 'upcoming' : 'missing';
}

function seriesItem(s: ServiceRecord, x: SonarrSeries, profiles: Map<number, string>): LibraryItem {
	const qp = x.qualityProfileId != null ? (profiles.get(x.qualityProfileId) ?? null) : null;
	return {
		key: `${s.id}-series-${x.id}`,
		serviceId: s.id,
		serviceName: s.name,
		kind: 'series',
		id: x.id,
		title: x.title,
		sortTitle: sortTitle(x.title, x.sortTitle),
		year: x.year || null,
		poster: arrImage(s, x.images, 'poster', 500),
		monitored: x.monitored,
		status: seriesStatus(x),
		continuing: x.status === 'continuing' || x.status === 'upcoming',
		sizeOnDisk: x.statistics?.sizeOnDisk ?? 0,
		added: x.added ?? null,
		rating: rating(x.ratings),
		genres: x.genres ?? [],
		qualityProfile: qp,
		is4k: UHD.test(s.name) || UHD.test(qp ?? ''),
		episodes: { have: x.statistics?.episodeFileCount ?? 0, total: x.statistics?.episodeCount ?? 0 },
		network: x.network ?? null,
		tmdbId: x.tmdbId || null,
		tvdbId: x.tvdbId || null
	};
}

function movieItem(s: ServiceRecord, x: RadarrMovie, profiles: Map<number, string>): LibraryItem {
	const qp = x.qualityProfileId != null ? (profiles.get(x.qualityProfileId) ?? null) : null;
	return {
		key: `${s.id}-movie-${x.id}`,
		serviceId: s.id,
		serviceName: s.name,
		kind: 'movie',
		id: x.id,
		title: x.title,
		sortTitle: sortTitle(x.title, x.sortTitle),
		year: x.year || null,
		poster: arrImage(s, x.images, 'poster', 500),
		monitored: x.monitored,
		status: movieStatus(x),
		continuing: false,
		sizeOnDisk: x.sizeOnDisk ?? 0,
		added: x.added ?? null,
		rating: rating(x.ratings),
		genres: x.genres ?? [],
		qualityProfile: qp,
		is4k: UHD.test(s.name) || UHD.test(qp ?? '') || (x.movieFile?.quality?.quality?.resolution ?? 0) >= 2160,
		episodes: null,
		network: x.studio ?? null,
		tmdbId: x.tmdbId || null,
		tvdbId: null
	};
}

function fileInfo(f: RadarrFile | undefined): FileInfo | null {
	if (!f) return null;
	const mi = f.mediaInfo;
	const audio = [mi?.audioCodec, mi?.audioChannels ? String(mi.audioChannels) : null].filter(Boolean).join(' ') || null;
	return {
		path: f.path ?? f.relativePath ?? '',
		size: f.size ?? 0,
		quality: f.quality?.quality?.name ?? null,
		resolution: mi?.resolution ?? null,
		videoCodec: mi?.videoCodec ?? null,
		audio,
		languages: (f.languages ?? []).map((l) => l.name).filter(Boolean),
		releaseGroup: f.releaseGroup ?? null,
		dateAdded: f.dateAdded ?? null
	};
}

/* ---------------------------------------------------------------- */
/* Public API                                                        */
/* ---------------------------------------------------------------- */

/** Every movie and series across all Sonarr/Radarr instances. Partial failures are reported, not fatal. */
export function library(): Promise<LibraryResult> {
	return cached('lib:all', 60_000, async () => {
		const sonarrs = servicesOfType('sonarr');
		const radarrs = servicesOfType('radarr');
		const jobs = [
			...sonarrs.map(async (s) => {
				const [rows, profiles] = await Promise.all([call<SonarrSeries[]>(s, '/api/v3/series'), lookup(s, '/api/v3/qualityprofile')]);
				return rows.map((x) => seriesItem(s, x, profiles));
			}),
			...radarrs.map(async (s) => {
				const [rows, profiles] = await Promise.all([call<RadarrMovie[]>(s, '/api/v3/movie'), lookup(s, '/api/v3/qualityprofile')]);
				return rows.map((x) => movieItem(s, x, profiles));
			})
		];
		const all = [...sonarrs, ...radarrs];
		const results = await Promise.allSettled(jobs);
		const errors: LibraryResult['errors'] = [];
		const items: LibraryItem[] = [];
		results.forEach((r, i) => {
			if (r.status === 'fulfilled') items.push(...r.value);
			else errors.push({ service: all[i].name, error: r.reason instanceof Error ? r.reason.message : String(r.reason) });
		});
		if (all.length > 0 && errors.length === all.length) throw new ServiceError(errors[0].error);
		items.sort((a, b) => a.sortTitle.localeCompare(b.sortTitle));
		return { items, errors };
	});
}

function arrService(serviceId: number, kind: LibraryKind): ServiceRecord {
	const s = getService(serviceId);
	if (!s || s.type !== (kind === 'movie' ? 'radarr' : 'sonarr')) throw new ServiceError('Not found', 404);
	return s;
}

export function libraryDetail(serviceId: number, kind: LibraryKind, id: number): Promise<LibraryDetail> {
	if (!Number.isInteger(id) || id <= 0) return Promise.reject(new ServiceError('Not found', 404));
	const s = arrService(serviceId, kind);
	return cached(`lib:${s.id}:${kind}:${id}`, 30_000, () => (kind === 'movie' ? movieDetail(s, id) : seriesDetail(s, id)));
}

async function movieDetail(s: ServiceRecord, id: number): Promise<LibraryDetail> {
	const [m, profiles, tags, credits] = await Promise.all([
		call<RadarrMovie>(s, `/api/v3/movie/${id}`),
		lookup(s, '/api/v3/qualityprofile'),
		lookup(s, '/api/v3/tag'),
		call<Credit[]>(s, `/api/v3/credit?movieId=${id}`).catch(() => [] as Credit[])
	]);
	const cast: CastMember[] = credits
		.filter((c) => c.type === 'cast')
		.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
		.slice(0, 20)
		.map((c) => ({
			name: c.personName,
			character: c.character || null,
			photo: tmdbImage(c.images?.find((i) => i.coverType === 'headshot')?.remoteUrl)
		}));
	return {
		...movieItem(s, m, profiles),
		overview: m.overview || null,
		runtime: m.runtime || null,
		certification: m.certification || null,
		studio: m.studio || null,
		path: m.path ?? null,
		rootFolder: m.rootFolderPath ?? null,
		fanart: arrImage(s, m.images, 'fanart'),
		imdbId: m.imdbId || null,
		inCinemas: m.inCinemas ?? null,
		digitalRelease: m.digitalRelease ?? null,
		nextAiring: null,
		tags: (m.tags ?? []).map((t) => tags.get(t) ?? String(t)),
		file: fileInfo(m.movieFile),
		seasons: [],
		cast,
		openUrl: openUrl(s, 'movie', m.titleSlug)
	};
}

async function seriesDetail(s: ServiceRecord, id: number): Promise<LibraryDetail> {
	const [x, profiles, tags, episodes, files] = await Promise.all([
		call<SonarrSeries>(s, `/api/v3/series/${id}`),
		lookup(s, '/api/v3/qualityprofile'),
		lookup(s, '/api/v3/tag'),
		call<SonarrEpisode[]>(s, `/api/v3/episode?seriesId=${id}`),
		call<SonarrEpisodeFile[]>(s, `/api/v3/episodefile?seriesId=${id}`).catch(() => [] as SonarrEpisodeFile[])
	]);
	const fileById = new Map(files.map((f) => [f.id, f]));
	const bySeason = new Map<number, Episode[]>();
	for (const e of episodes) {
		const f = e.episodeFileId ? fileById.get(e.episodeFileId) : undefined;
		const list = bySeason.get(e.seasonNumber) ?? [];
		list.push({
			id: e.id,
			number: e.episodeNumber,
			title: e.title || `Episode ${e.episodeNumber}`,
			airDate: e.airDateUtc ?? null,
			monitored: e.monitored,
			hasFile: e.hasFile,
			quality: f?.quality?.quality?.name ?? null,
			size: f?.size ?? null,
			overview: e.overview || null
		});
		bySeason.set(e.seasonNumber, list);
	}
	const seasons: Season[] = (x.seasons ?? [])
		.map((se) => ({
			number: se.seasonNumber,
			monitored: se.monitored,
			have: se.statistics?.episodeFileCount ?? 0,
			total: se.statistics?.episodeCount ?? 0,
			size: se.statistics?.sizeOnDisk ?? 0,
			episodes: (bySeason.get(se.seasonNumber) ?? []).sort((a, b) => a.number - b.number)
		}))
		// Newest first, specials last — what people usually want to see.
		.sort((a, b) => (a.number === 0 ? 1 : b.number === 0 ? -1 : b.number - a.number));
	return {
		...seriesItem(s, x, profiles),
		overview: x.overview || null,
		runtime: x.runtime || null,
		certification: x.certification || null,
		studio: null,
		path: x.path ?? null,
		rootFolder: x.rootFolderPath ?? null,
		fanart: arrImage(s, x.images, 'fanart'),
		imdbId: x.imdbId || null,
		inCinemas: null,
		digitalRelease: null,
		nextAiring: x.nextAiring ?? null,
		tags: (x.tags ?? []).map((t) => tags.get(t) ?? String(t)),
		file: null,
		seasons,
		cast: [],
		openUrl: openUrl(s, 'series', x.titleSlug)
	};
}
