import { untrack } from 'svelte';
import type { ActionRequest, ActivitySnapshot } from './types';

/** Last snapshot, kept across page visits so returning to Activity paints instantly. */
let lastSnapshot: ActivitySnapshot | undefined;

/**
 * Live activity over Server-Sent Events. Connects only while the tab is visible;
 * falls back to a one-off fetch for the first paint.
 */
export function liveActivity() {
	const state = $state<{ data: ActivitySnapshot | undefined; connected: boolean; error: string | null }>({
		data: lastSnapshot,
		connected: false,
		error: null
	});

	function set(data: ActivitySnapshot) {
		lastSnapshot = data;
		state.data = data;
		state.error = null;
	}

	// untrack: the connection must not depend on the data it delivers (that would reconnect on every message).
	$effect(() => untrack(() => {
		let es: EventSource | null = null;

		const open = () => {
			if (es) return;
			es = new EventSource('/api/activity/stream');
			es.onopen = () => (state.connected = true);
			es.onmessage = (e) => {
				try {
					set(JSON.parse(e.data));
				} catch {
					/* ignore partial frames */
				}
			};
			es.onerror = () => {
				state.connected = false;
				// EventSource retries on its own; a closed source (e.g. 401) needs a manual check.
				if (es?.readyState === EventSource.CLOSED) {
					es = null;
					fetch('/api/activity').then(async (r) => {
						if (r.ok) set(await r.json());
						else state.error = (await r.json().catch(() => ({})))?.error ?? `HTTP ${r.status}`;
					}, () => (state.error = 'Offline'));
				}
			};
		};
		const close = () => {
			es?.close();
			es = null;
			state.connected = false;
		};
		const onVisibility = () => (document.visibilityState === 'visible' ? open() : close());

		if (!state.data) fetch('/api/activity').then(async (r) => r.ok && !state.data && set(await r.json()), () => {});
		onVisibility();
		document.addEventListener('visibilitychange', onVisibility);
		return () => {
			document.removeEventListener('visibilitychange', onVisibility);
			close();
		};
	}));

	return {
		get state() {
			return state;
		}
	};
}

export async function act(req: ActionRequest | ({ add: true } & Record<string, unknown>)): Promise<string | null> {
	const isAdd = 'add' in req;
	const res = await fetch(isAdd ? '/api/activity/add' : '/api/activity/action', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(req)
	}).catch(() => null);
	if (!res) return 'Network error';
	if (res.ok) return null;
	const body = await res.json().catch(() => ({}));
	return body?.error ?? body?.message ?? `HTTP ${res.status}`;
}
