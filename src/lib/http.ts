/**
 * Strict JSON GET for anything that gets cached or rendered as data.
 * A 2xx response that isn't JSON (an HTML login page after a redirect, a proxy error page during a
 * restart) used to become `{}` and was saved as real data, which then crashed pages on next paint.
 */
export async function getJSON<T>(url: string, init?: RequestInit): Promise<T> {
	const r = await fetch(url, init);
	const isJson = (r.headers.get('content-type') ?? '').includes('json');
	const body = isJson ? await r.json().catch(() => undefined) : undefined;
	if (!r.ok) throw new Error((body as { error?: string } | undefined)?.error ?? `HTTP ${r.status}`);
	if (body === undefined || body === null || typeof body !== 'object') throw new Error('Unexpected response from the server');
	return body as T;
}

/** Version of the client cache. Bump to make every device drop data saved by older builds. */
export const CACHE_PREFIX = 'swr2:';

/** Remove cache entries written by older builds (once per page load). */
export function purgeOldCache() {
	try {
		const old: string[] = [];
		for (let i = 0; i < localStorage.length; i++) {
			const k = localStorage.key(i);
			if (k && k.startsWith('swr:')) old.push(k);
		}
		for (const k of old) localStorage.removeItem(k);
	} catch {
		/* storage disabled */
	}
}

/** Remove every cached response (this build's and older ones). Called on the sign-in page so the next
 *  account never sees data cached for the previous one. */
export function clearCache() {
	try {
		const keys: string[] = [];
		for (let i = 0; i < localStorage.length; i++) {
			const k = localStorage.key(i);
			if (k && (k.startsWith('swr:') || k.startsWith(CACHE_PREFIX))) keys.push(k);
		}
		for (const k of keys) localStorage.removeItem(k);
	} catch {
		/* storage disabled */
	}
}
