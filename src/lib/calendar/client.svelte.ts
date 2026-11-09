// Calendar client helpers: local-time date maths and a stale-while-revalidate feed whose range can change.
import { untrack } from 'svelte';
import type { CalendarEntry, CalendarResult } from './types';

/* ---------------------------------------------------------------- */
/* Dates (always the device's local time zone)                       */
/* ---------------------------------------------------------------- */
const pad = (n: number) => String(n).padStart(2, '0');

/** Local calendar date as YYYY-MM-DD. */
export const dayKey = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/** Local midnight of a YYYY-MM-DD key. */
export function fromKey(k: string): Date {
	const [y, m, d] = k.split('-').map(Number);
	return new Date(y, m - 1, d);
}

/** Calendar-day arithmetic (safe across DST changes, unlike adding 24 h). */
export const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
export const addMonths = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth() + n, 1);

/** First day of the week for the user's locale, as a JS weekday (0 = Sunday). Monday when unknown. */
export function weekStartDay(): number {
	try {
		const loc = new Intl.Locale(navigator.language) as Intl.Locale & {
			getWeekInfo?: () => { firstDay: number };
			weekInfo?: { firstDay: number };
		};
		const first = loc.getWeekInfo?.().firstDay ?? loc.weekInfo?.firstDay;
		return first ? first % 7 : 1;
	} catch {
		return 1;
	}
}

export function startOfWeek(d: Date, first = weekStartDay()): Date {
	return addDays(d, -((d.getDay() - first + 7) % 7));
}

/** The 6-week grid that shows a month; also the data window for any week in that month. */
export function monthGrid(d: Date, first = weekStartDay()): { start: Date; end: Date; days: Date[] } {
	const start = startOfWeek(new Date(d.getFullYear(), d.getMonth(), 1), first);
	const days = Array.from({ length: 42 }, (_, i) => addDays(start, i));
	return { start, end: addDays(start, 42), days };
}

/** Movie releases are calendar dates (no time); episodes air at an instant shown in local time. */
export const entryDay = (e: CalendarEntry) => (e.allDay ? e.date.slice(0, 10) : dayKey(new Date(e.date)));

export function calendarUrl(start: Date, end: Date) {
	return `/api/calendar?start=${encodeURIComponent(start.toISOString())}&end=${encodeURIComponent(end.toISOString())}`;
}

/** Group entries by local day, dropping duplicate ids (stale cached data must never produce duplicate keys). */
export function byDay(entries: CalendarEntry[]): Map<string, CalendarEntry[]> {
	const seen = new Set<string>();
	const map = new Map<string, CalendarEntry[]>();
	for (const e of entries) {
		const k = entryDay(e);
		const id = `${k}|${e.id}`;
		if (seen.has(id)) continue;
		seen.add(id);
		const list = map.get(k);
		if (list) list.push(e);
		else map.set(k, [e]);
	}
	for (const list of map.values())
		list.sort((a, b) => Number(b.allDay) - Number(a.allDay) || a.date.localeCompare(b.date) || a.title.localeCompare(b.title));
	return map;
}

/* ---------------------------------------------------------------- */
/* Feed: memory + localStorage seed, background refresh, prefetch    */
/* ---------------------------------------------------------------- */
const STORE_PREFIX = 'swr:/api/calendar?';
const KEEP = 6; // calendar windows kept in localStorage
const memory = new Map<string, CalendarResult>();
const inflight = new Map<string, Promise<CalendarResult>>();

function readPersisted(url: string): CalendarResult | undefined {
	try {
		const raw = localStorage.getItem('swr:' + url);
		const data = raw ? (JSON.parse(raw) as CalendarResult) : undefined;
		return Array.isArray(data?.entries) ? data : undefined;
	} catch {
		return undefined;
	}
}

function persist(url: string, data: CalendarResult) {
	try {
		localStorage.setItem('swr:' + url, JSON.stringify(data));
		localStorage.setItem('swr:' + url + '#at', String(Date.now()));
		// Drop the oldest windows so browsing months doesn't grow storage forever.
		const keys: Array<[string, number]> = [];
		for (let i = 0; i < localStorage.length; i++) {
			const k = localStorage.key(i);
			if (k?.startsWith(STORE_PREFIX) && !k.endsWith('#at')) keys.push([k, Number(localStorage.getItem(k + '#at') ?? 0)]);
		}
		keys.sort((a, b) => b[1] - a[1]);
		for (const [k] of keys.slice(KEEP)) {
			localStorage.removeItem(k);
			localStorage.removeItem(k + '#at');
		}
	} catch {
		/* storage full or disabled — fine */
	}
}

function fetchWindow(url: string): Promise<CalendarResult> {
	let p = inflight.get(url);
	if (!p) {
		p = fetch(url).then(async (r) => {
			const body = await r.json().catch(() => ({}));
			if (!r.ok) throw new Error(body?.error ?? `HTTP ${r.status}`);
			memory.set(url, body);
			persist(url, body);
			return body as CalendarResult;
		});
		inflight.set(url, p);
		p.finally(() => inflight.delete(url)).catch(() => {});
	}
	return p;
}

/** Warm a window (e.g. the next month) so navigating to it paints instantly. */
export function prefetch(url: string) {
	if (!memory.has(url) && !inflight.has(url)) fetchWindow(url).catch(() => {});
}

/**
 * SWR store for the window `getUrl()` returns. Switching windows paints the cached copy at once (if any)
 * and refreshes it in the background; refreshes every `intervalMs` while the tab is visible.
 */
export function calendarFeed(getUrl: () => string | null, intervalMs = 5 * 60_000) {
	const state = $state<{ data: CalendarResult | undefined; error: string | null; loading: boolean }>({
		data: undefined,
		error: null,
		loading: true
	});
	let current: string | null = null;

	async function refresh() {
		const url = current;
		if (!url) return;
		state.loading = true;
		try {
			const data = await fetchWindow(url);
			if (url !== current) return; // the user moved on
			state.data = data;
			state.error = null;
		} catch (e) {
			if (url === current) state.error = e instanceof Error ? e.message : String(e);
		} finally {
			if (url === current) state.loading = false;
		}
	}

	$effect(() => {
		const url = getUrl();
		// untrack: switching windows writes state that must not re-trigger this effect.
		untrack(() => {
			if (url === current) return;
			current = url;
			if (!url) return;
			state.data = memory.get(url) ?? readPersisted(url);
			state.error = null;
			refresh();
		});
	});

	$effect(() => {
		const tick = () => document.visibilityState === 'visible' && refresh();
		const timer = setInterval(tick, intervalMs);
		document.addEventListener('visibilitychange', tick);
		return () => {
			clearInterval(timer);
			document.removeEventListener('visibilitychange', tick);
		};
	});

	return {
		get state() {
			return state;
		},
		refresh
	};
}
