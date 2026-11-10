/** Shapes returned by /api/svc/jellystat/[id]/... (client-safe). */

export type JsSession = {
	id: string;
	user: string;
	title: string;
	subtitle: string | null;
	device: string;
	/** 0..1 */
	progress: number;
	paused: boolean;
	method: string | null;
	positionSec: number;
	runtimeSec: number;
};

export type JsDay = {
	/** YYYY-MM-DD */
	date: string;
	movies: number;
	episodes: number;
	other: number;
};

export type JsPeriod = {
	days: number;
	plays: number;
	/** watch time, minutes */
	minutes: number;
	activeUsers: number;
};

export type JsOverview = {
	periods: JsPeriod[];
	/** oldest first, last 90 days (+ today) */
	daily: JsDay[];
};

export type JsTopItem = { name: string; plays: number; seconds?: number };

export type JsTop = {
	days: number;
	movies: JsTopItem[];
	shows: JsTopItem[];
	users: JsTopItem[];
	clients: JsTopItem[];
};

export type JsHistoryRow = {
	id: string;
	user: string;
	title: string;
	subtitle: string | null;
	client: string;
	device: string;
	seconds: number;
	method: string | null;
	at: string | null;
	plays: number;
};

export type JsHistory = { page: number; pages: number; size: number; rows: JsHistoryRow[] };

export type JsLibrary = {
	id: string;
	name: string;
	kind: 'movies' | 'tvshows' | 'music' | 'other';
	items: number;
	seasons: number;
	episodes: number;
	/** total runtime of the library's content, seconds */
	contentSec: number;
	plays: number;
	/** total watched, seconds */
	watchedSec: number;
	lastPlayed: string | null;
	lastItem: string | null;
};

export const PERIODS = [7, 30, 90] as const;
export type PeriodDays = (typeof PERIODS)[number];
export const PAGE_SIZES = [10, 25, 50] as const;
