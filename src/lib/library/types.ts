// Client-safe shapes for the unified Sonarr + Radarr library.

export type LibraryKind = 'movie' | 'series';

/** missing = monitored and nothing on disk; partial = some episodes missing; upcoming = not released yet. */
export type LibraryStatus = 'downloaded' | 'partial' | 'missing' | 'upcoming' | 'unmonitored';

export type LibraryItem = {
	/** `${serviceId}-${kind}-${id}` — unique across instances. */
	key: string;
	serviceId: number;
	serviceName: string;
	kind: LibraryKind;
	id: number;
	title: string;
	sortTitle: string;
	year: number | null;
	poster: string | null;
	monitored: boolean;
	status: LibraryStatus;
	/** Series: still airing. */
	continuing: boolean;
	sizeOnDisk: number;
	added: string | null;
	/** 0–10 */
	rating: number | null;
	genres: string[];
	qualityProfile: string | null;
	is4k: boolean;
	/** Series only. */
	episodes: { have: number; total: number } | null;
	network: string | null;
	/** External ids, used to link Discover results to the library. */
	tmdbId: number | null;
	tvdbId: number | null;
};

export type LibraryResult = { items: LibraryItem[]; errors: Array<{ service: string; error: string }> };

export type CastMember = { name: string; character: string | null; photo: string | null };

export type FileInfo = {
	path: string;
	size: number;
	quality: string | null;
	resolution: string | null;
	videoCodec: string | null;
	audio: string | null;
	languages: string[];
	releaseGroup: string | null;
	dateAdded: string | null;
};

export type Episode = {
	id: number;
	number: number;
	title: string;
	airDate: string | null;
	monitored: boolean;
	hasFile: boolean;
	quality: string | null;
	size: number | null;
	overview: string | null;
};

export type Season = {
	number: number;
	monitored: boolean;
	have: number;
	total: number;
	size: number;
	episodes: Episode[];
};

export type LibraryDetail = LibraryItem & {
	overview: string | null;
	runtime: number | null;
	certification: string | null;
	studio: string | null;
	path: string | null;
	rootFolder: string | null;
	fanart: string | null;
	imdbId: string | null;
	inCinemas: string | null;
	digitalRelease: string | null;
	nextAiring: string | null;
	tags: string[];
	file: FileInfo | null;
	seasons: Season[];
	cast: CastMember[];
	/** Link into the arr's own UI (external URL when configured). */
	openUrl: string | null;
};
