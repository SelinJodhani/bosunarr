/** Small client helpers for the Prowlarr screen. */

export const base = (serviceId: number) => `/api/svc/prowlarr/${serviceId}`;

/** POST JSON; resolves to an error message, or null on success. */
export async function post(url: string, body: unknown): Promise<string | null> {
	try {
		const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
		if (r.ok) return null;
		const isJson = (r.headers.get('content-type') ?? '').includes('json');
		const data = isJson ? ((await r.json().catch(() => undefined)) as { error?: string; message?: string } | undefined) : undefined;
		return data?.error ?? data?.message ?? `HTTP ${r.status}`;
	} catch {
		return 'You seem to be offline. Try again.';
	}
}

/** Release age in words: "40 min", "5 h", "12 days", "1.5 years". */
export function ageLabel(hours: number | null): string {
	if (hours === null || !Number.isFinite(hours) || hours < 0) return '—';
	if (hours < 1) return `${Math.max(1, Math.round(hours * 60))} min`;
	if (hours < 48) return `${Math.round(hours)} h`;
	const days = hours / 24;
	if (days < 365) return `${Math.round(days)} days`;
	const years = Math.round((days / 365) * 10) / 10;
	return `${years} year${years === 1 ? '' : 's'}`;
}

/** Prowlarr's "Cardigann" is its generic definition engine — not something worth showing. */
export const implementationLabel = (impl: string) => (impl && impl !== 'Cardigann' ? impl : null);

export const compact = (n: number) => new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
