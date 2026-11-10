import type { JfAction } from './types';

/** POST an admin action; resolves to an error message, or null on success. */
export async function act(serviceId: number, body: JfAction): Promise<string | null> {
	try {
		const r = await fetch(`/api/svc/jellyfin/${serviceId}/action`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body)
		});
		if (r.ok) return null;
		const isJson = (r.headers.get('content-type') ?? '').includes('json');
		const j = isJson ? await r.json().catch(() => undefined) : undefined;
		return (j as { error?: string; message?: string } | undefined)?.error ?? (j as { message?: string } | undefined)?.message ?? `HTTP ${r.status}`;
	} catch {
		return 'Offline — try again';
	}
}

/** "1:02:03" / "4:05" from seconds. */
export function clock(sec: number): string {
	const s = Math.max(0, Math.floor(sec));
	const h = Math.floor(s / 3600);
	const m = Math.floor((s % 3600) / 60);
	const r = String(s % 60).padStart(2, '0');
	return h ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`;
}
