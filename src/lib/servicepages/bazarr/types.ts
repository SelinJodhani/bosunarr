/** Client-safe shapes returned by `/api/svc/bazarr/[id]/...`. */

export type BazarrKind = 'episodes' | 'movies';

export type SubLang = { code: string; name: string; hi: boolean; forced: boolean };

export type BazarrProvider = {
	name: string;
	/** Bazarr's throttle reason (e.g. `TooManyRequests`), or null when the provider is fine. */
	issue: string | null;
	/** Human text from Bazarr, e.g. "in 2 hours"; null when not throttled. */
	until: string | null;
};

export type BazarrProfile = {
	id: number;
	name: string;
	cutoff: string | null;
	languages: SubLang[];
};

export type BazarrOverview = {
	version: string | null;
	sonarrVersion: string | null;
	radarrVersion: string | null;
	startTime: number | null;
	wanted: { episodes: number; movies: number };
	sonarrSignalr: string | null;
	radarrSignalr: string | null;
	health: { object: string; issue: string }[];
	providers: BazarrProvider[];
	profiles: BazarrProfile[];
	/** Parts that failed to load (the rest still renders). */
	errors: string[];
};

export type WantedItem = {
	/** `${kind}:${id}` */
	key: string;
	kind: BazarrKind;
	/** sonarrEpisodeId or radarrId */
	id: number;
	seriesId: number | null;
	title: string;
	subtitle: string | null;
	missing: SubLang[];
	poster: string | null;
	href: string | null;
};

export type WantedPage = { kind: BazarrKind; items: WantedItem[]; total: number; page: number; pageSize: number };

export type HistoryAction = 'deleted' | 'downloaded' | 'manual' | 'upgraded' | 'uploaded' | 'synced' | 'translated' | 'extracted' | 'other';

export type HistoryItem = {
	key: string;
	kind: BazarrKind;
	title: string;
	subtitle: string | null;
	action: HistoryAction;
	language: SubLang | null;
	provider: string | null;
	/** e.g. "93.33%" as Bazarr formats it */
	score: string | null;
	/** Bazarr's relative text ("2 hours ago") */
	when: string | null;
	/** Bazarr's absolute timestamp text, for a tooltip */
	at: string | null;
	upgradable: boolean;
};

export type HistoryPage = { kind: BazarrKind; items: HistoryItem[]; total: number; page: number; pageSize: number };

export type BazarrAction =
	| { action: 'search'; kind: BazarrKind; id: number }
	| { action: 'searchAll'; kind: BazarrKind | 'all' }
	| { action: 'resetProviders' };

export type ActionResult = { message: string };
