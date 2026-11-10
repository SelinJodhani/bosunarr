/**
 * Stale-while-revalidate polling store.
 * - Paints instantly from the last value (memory, then localStorage) so the dashboard never flashes empty.
 * - Polls only while the tab is visible; refetches immediately when it becomes visible again.
 * - Dedupes: several widgets asking for the same URL share one request.
 */
import { CACHE_PREFIX, getJSON, purgeOldCache } from './http';

type Entry<T> = { data: T | undefined; error: string | null; loading: boolean; updated: number };

const memory = new Map<string, Entry<unknown>>();
if (typeof localStorage !== 'undefined') purgeOldCache();
const inflight = new Map<string, Promise<unknown>>();

function readPersisted<T>(url: string): T | undefined {
	try {
		const raw = localStorage.getItem(CACHE_PREFIX + url);
		const v = raw ? JSON.parse(raw) : undefined;
		// Never seed from an empty or non-object value: pages assume the full shape.
		return v && typeof v === 'object' && Object.keys(v).length ? (v as T) : undefined;
	} catch {
		return undefined;
	}
}

function persist(url: string, data: unknown) {
	try {
		localStorage.setItem(CACHE_PREFIX + url, JSON.stringify(data));
	} catch {
		/* storage full or disabled — fine */
	}
}

export function live<T>(url: string, intervalMs: number) {
	const seed = (memory.get(url) as Entry<T> | undefined) ?? {
		data: typeof localStorage !== 'undefined' ? readPersisted<T>(url) : undefined,
		error: null,
		loading: true,
		updated: 0
	};
	const state = $state<Entry<T>>({ ...seed });

	async function refresh() {
		state.loading = true;
		try {
			let p = inflight.get(url) as Promise<T> | undefined;
			if (!p) {
				p = getJSON<T>(url);
				inflight.set(url, p);
				p.finally(() => inflight.delete(url)).catch(() => {}); // the awaiting caller handles the error
			}
			const data = await p;
			state.data = data;
			state.error = null;
			state.updated = Date.now();
			persist(url, data);
		} catch (e) {
			state.error = e instanceof Error ? e.message : String(e);
		} finally {
			state.loading = false;
			memory.set(url, { ...state });
		}
	}

	$effect(() => {
		refresh();
		const timer = setInterval(() => document.visibilityState === 'visible' && refresh(), intervalMs);
		const onVisible = () => document.visibilityState === 'visible' && refresh();
		document.addEventListener('visibilitychange', onVisible);
		return () => {
			clearInterval(timer);
			document.removeEventListener('visibilitychange', onVisible);
		};
	});

	return { get state() { return state; }, refresh };
}
