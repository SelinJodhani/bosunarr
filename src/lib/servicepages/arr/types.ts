// Client-safe shapes for the Sonarr / Radarr service screen (built by $lib/server/svc/arr).

export type ArrKind = 'series' | 'movie';

export type ArrSystem = {
	app: string;
	version: string;
	branch: string | null;
	startTime: string | null;
	os: string | null;
	runtime: string | null;
	docker: boolean;
	database: string | null;
	updateMechanism: string | null;
	update: { version: string; releaseDate: string | null } | null;
};

export type ArrHealth = { source: string; type: 'ok' | 'notice' | 'warning' | 'error'; message: string; wikiUrl: string | null };

export type ArrDisk = {
	path: string;
	free: number;
	total: number | null;
	rootFolder: boolean;
	accessible: boolean;
	unmapped: number;
};

export type ArrQueueSummary = { count: number; errors: boolean; warnings: boolean };

export type ArrCommandInfo = { id: number; name: string; status: string; started: string | null; ended: string | null };

export type ArrIndexer = {
	id: number;
	name: string;
	implementation: string;
	protocol: string;
	rss: boolean;
	autoSearch: boolean;
	interactiveSearch: boolean;
	priority: number;
	disabledTill: string | null;
	lastFailure: string | null;
};

export type ArrDownloadClient = {
	id: number;
	name: string;
	implementation: string;
	protocol: string;
	enabled: boolean;
	priority: number;
	disabledTill: string | null;
};

export type ArrProfile = { id: number; name: string; cutoff: string | null; qualities: string[]; upgradeAllowed: boolean };

export type ArrTag = { id: number; label: string; uses: number };

export type ArrOverview = {
	kind: ArrKind;
	system: ArrSystem;
	health: ArrHealth[];
	disks: ArrDisk[];
	queue: ArrQueueSummary | null;
	missing: number | null;
	cutoff: number | null;
	commands: ArrCommandInfo[];
	indexers: ArrIndexer[];
	downloadClients: ArrDownloadClient[];
	profiles: ArrProfile[];
	tags: ArrTag[];
	/** Sections that failed to load, e.g. ["indexers"] (the rest of the page still renders). */
	partial: string[];
};

export type WantedKind = 'missing' | 'cutoff';

export type WantedItem = {
	id: number;
	title: string;
	subtitle: string | null;
	date: string | null;
	dateLabel: 'Aired' | 'Released' | 'In cinemas' | null;
	quality: string | null;
	poster: string | null;
	href: string | null;
};

export type Paged<T> = { page: number; pages: number; total: number; records: T[] };

export type HistoryFilter = 'all' | 'grabbed' | 'imported' | 'failed';

export type HistoryItem = {
	id: number;
	event: string;
	eventLabel: string;
	tone: 'grab' | 'ok' | 'error' | 'other';
	title: string;
	subtitle: string | null;
	source: string | null;
	quality: string | null;
	detail: string | null;
	date: string;
	href: string | null;
};

export const ARR_COMMANDS = ['rss', 'missing', 'cutoff', 'refresh'] as const;
export type ArrCommand = (typeof ARR_COMMANDS)[number];
