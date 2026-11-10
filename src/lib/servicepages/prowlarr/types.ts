/** Shapes shared by the Prowlarr API routes (`/api/svc/prowlarr/[id]/...`) and its page. */

export type Protocol = 'torrent' | 'usenet' | 'unknown';

export type HealthIssue = { source: string; type: 'notice' | 'warning' | 'error'; message: string; wikiUrl: string | null };

export type Indexer = {
	id: number;
	name: string;
	enabled: boolean;
	protocol: Protocol;
	privacy: string;
	priority: number;
	implementation: string;
	/** Failure info from /indexerstatus; null when healthy. */
	failure: { since: string | null; last: string | null; disabledTill: string | null } | null;
};

export type Overview = {
	version: string | null;
	health: HealthIssue[];
	indexers: Indexer[];
};

export type SearchResult = {
	/** Opaque server-side handle used to grab; the indexer's guid/links never reach the browser. */
	token: string;
	title: string;
	indexerId: number;
	indexer: string;
	size: number;
	publishDate: string | null;
	ageHours: number | null;
	seeders: number | null;
	leechers: number | null;
	grabs: number | null;
	protocol: Protocol;
	categories: string[];
	infoUrl: string | null;
};

export type SearchResponse = { results: SearchResult[]; truncated: boolean };

export type IndexerStat = {
	indexerId: number;
	name: string;
	queries: number;
	grabs: number;
	failedQueries: number;
	failedGrabs: number;
	rssQueries: number;
	avgResponseMs: number;
};

export type StatsResponse = { days: number; indexers: IndexerStat[] };

export type HistoryEntry = {
	id: number;
	date: string;
	indexerId: number;
	indexer: string | null;
	kind: 'query' | 'rss' | 'grab' | 'auth' | 'other';
	successful: boolean;
	/** Query text for searches, release title for grabs. */
	title: string;
	source: string | null;
	results: number | null;
	elapsedMs: number | null;
};

export type HistoryResponse = { records: HistoryEntry[]; total: number };

/** Newznab top-level categories offered in the search filter. */
export const CATEGORIES = [
	{ id: 2000, name: 'Movies' },
	{ id: 5000, name: 'TV' },
	{ id: 3000, name: 'Audio' },
	{ id: 7000, name: 'Books' },
	{ id: 4000, name: 'PC' },
	{ id: 1000, name: 'Console' },
	{ id: 8000, name: 'Other' }
] as const;

export const STAT_RANGES = [7, 30, 90] as const;
