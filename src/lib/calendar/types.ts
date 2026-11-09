// Client-safe shapes for the merged Sonarr + Radarr calendar.

export type CalendarKind = 'episode' | 'movie';
/** airing = TV episode; the rest are Radarr release types. */
export type CalendarRelease = 'airing' | 'cinema' | 'digital' | 'physical';
/** downloaded = on disk; missing = released but not on disk; unaired = not released / not available yet. */
export type CalendarStatus = 'downloaded' | 'missing' | 'unaired';

/** One instance's copy of an entry (Radarr + Radarr 4K both list the same movie). */
export type CalendarCopy = {
	serviceId: number;
	serviceName: string;
	is4k: boolean;
	status: CalendarStatus;
	monitored: boolean;
	href: string;
};

export type CalendarEntry = {
	/** Unique across instances and release types; safe as an {#each} key. */
	id: string;
	kind: CalendarKind;
	release: CalendarRelease;
	/** Series or movie title. */
	title: string;
	year: number | null;
	/** "S01E03 · Episode title" for episodes, null for movies. */
	episodeLabel: string | null;
	/** Premiere / finale marker for episodes. */
	marker: 'premiere' | 'season finale' | 'series finale' | 'midseason finale' | null;
	/** ISO instant. For all-day entries (movie releases) only the date part (UTC) is meaningful. */
	date: string;
	allDay: boolean;
	/** Minutes, when known. */
	runtime: number | null;
	/** Network for series, studio for movies. */
	network: string | null;
	/** Combined across copies: downloaded only when every monitored copy is. */
	status: CalendarStatus;
	monitored: boolean;
	is4k: boolean;
	poster: string | null;
	/** Library detail page of the primary copy. */
	href: string;
	copies: CalendarCopy[];
};

export type CalendarResult = {
	start: string;
	end: string;
	entries: CalendarEntry[];
	errors: Array<{ service: string; error: string }>;
};

export const RELEASE_LABEL: Record<CalendarRelease, string> = {
	airing: 'Episode',
	cinema: 'In cinemas',
	digital: 'Digital release',
	physical: 'Physical release'
};

export const STATUS_TEXT: Record<CalendarStatus, string> = {
	downloaded: 'Downloaded',
	missing: 'Missing',
	unaired: 'Upcoming'
};

/** Hard cap on one request's range (a 6-week month grid). */
export const MAX_RANGE_DAYS = 42;
