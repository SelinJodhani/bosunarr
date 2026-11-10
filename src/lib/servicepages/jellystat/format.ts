/** Compact duration: 45s, 12m, 3h 20m, 4d 6h. */
export function duration(seconds: number): string {
	if (!Number.isFinite(seconds) || seconds <= 0) return '0m';
	const m = Math.floor(seconds / 60);
	if (m < 1) return `${Math.round(seconds)}s`;
	const h = Math.floor(m / 60);
	if (h < 1) return `${m}m`;
	const d = Math.floor(h / 24);
	if (d < 1) return m % 60 ? `${h}h ${m % 60}m` : `${h}h`;
	return h % 24 ? `${d}d ${h % 24}h` : `${d}d`;
}

/** Clock-style position: 1:02:03 or 12:34. */
export function clock(seconds: number): string {
	const s = Math.max(0, Math.round(seconds));
	const h = Math.floor(s / 3600);
	const mm = String(Math.floor((s % 3600) / 60)).padStart(h ? 2 : 1, '0');
	const ss = String(s % 60).padStart(2, '0');
	return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

const nf = new Intl.NumberFormat();
export const count = (n: number) => nf.format(n);

/** "DirectPlay" → "Direct play" */
export function method(m: string | null): string {
	if (!m) return '';
	const s = m.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
	return s[0].toUpperCase() + s.slice(1);
}

/** YYYY-MM-DD → "7 Nov" (local, no timezone shift). */
export function shortDate(ymd: string): string {
	const [y, m, d] = ymd.split('-').map(Number);
	return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export function when(iso: string | null): string {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(+d)) return '';
	const sameYear = d.getFullYear() === new Date().getFullYear();
	return d.toLocaleString(undefined, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }), hour: 'numeric', minute: '2-digit' });
}
