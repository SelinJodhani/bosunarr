import type { ActionResult, BazarrAction } from './types';

/** POST an admin action; resolves to `{ ok, message }` and never throws. */
export async function bazarrAct(serviceId: number, body: BazarrAction): Promise<{ ok: boolean; message: string }> {
	const res = await fetch(`/api/svc/bazarr/${serviceId}/action`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body)
	}).catch(() => null);
	if (!res) return { ok: false, message: 'Network error' };
	const isJson = (res.headers.get('content-type') ?? '').includes('json');
	const data = isJson ? await res.json().catch(() => null) : null;
	if (!res.ok) return { ok: false, message: data?.error ?? data?.message ?? `HTTP ${res.status}` };
	return { ok: true, message: (data as ActionResult | null)?.message ?? 'Done' };
}

export function langLabel(l: { name: string; hi: boolean; forced: boolean }) {
	return `${l.name}${l.hi ? ' HI' : ''}${l.forced ? ' forced' : ''}`;
}
