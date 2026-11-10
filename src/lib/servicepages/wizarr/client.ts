/** JSON write to our own API. Returns `{ data }` on success or `{ error }` with a readable message. */
export async function send<T = unknown>(method: 'POST' | 'DELETE', url: string, body?: unknown): Promise<{ data?: T; error?: string }> {
	const res = await fetch(url, {
		method,
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	}).catch(() => null);
	if (!res) return { error: 'Network error' };
	const isJson = (res.headers.get('content-type') ?? '').includes('json');
	const json = isJson ? await res.json().catch(() => undefined) : undefined;
	if (!res.ok) return { error: json?.error ?? json?.message ?? `HTTP ${res.status}` };
	if (!json || typeof json !== 'object') return { error: 'Unexpected response from the server' };
	return { data: json as T };
}

/** Copy text; falls back to a hidden textarea where the async Clipboard API isn't available (plain-HTTP origins). */
export async function copyText(text: string): Promise<boolean> {
	try {
		if (navigator.clipboard && window.isSecureContext) {
			await navigator.clipboard.writeText(text);
			return true;
		}
	} catch {
		/* fall through */
	}
	try {
		const ta = document.createElement('textarea');
		ta.value = text;
		ta.setAttribute('readonly', '');
		ta.style.position = 'fixed';
		ta.style.opacity = '0';
		document.body.appendChild(ta);
		ta.select();
		const ok = document.execCommand('copy');
		ta.remove();
		return ok;
	} catch {
		return false;
	}
}

export function inviteLink(base: string, path: string): string {
	return base.replace(/\/+$/, '') + path;
}

export const fmtDate = (iso: string | null) =>
	iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—';

export const isPast = (iso: string | null) => Boolean(iso && +new Date(iso) <= Date.now());
