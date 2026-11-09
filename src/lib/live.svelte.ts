/**
 * Stale-while-revalidate polling store.
 * - Paints instantly from the last value (memory, then localStorage) so the dashboard never flashes empty.
 * - Polls only while the tab is visible; refetches immediately when it becomes visible again.
 * - Dedupes: several widgets asking for the same URL share one request.
 */
type Entry<T> = { data: T | undefined; error: string | null; loading: boolean; updated: number };

const memory = new Map<string, Entry<unknown>>();
const inflight = new Map<string, Promise<unknown>>();

function readPersisted<T>(url: string): T | undefined {
	try {
		const raw = localStorage.getItem('swr:' + url);
		return raw ? (JSON.parse(raw) as T) : undefined;
	} catch {
		return undefined;
	}
}

function persist(url: string, data: unknown) {
	try {
		localStorage.setItem('swr:' + url, JSON.stringify(data));
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
				p = fetch(url).then(async (r) => {
					const body = await r.json().catch(() => ({}));
					if (!r.ok) throw new Error(body?.error ?? `HTTP ${r.status}`);
					return body as T;
				});
				inflight.set(url, p);
				p.finally(() => inflight.delete(url));
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
