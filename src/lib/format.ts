export function bytes(n: number, digits = 1): string {
	if (!Number.isFinite(n) || n <= 0) return '0 B';
	const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
	const i = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
	return `${(n / 1024 ** i).toFixed(i === 0 ? 0 : digits)} ${units[i]}`;
}

export const speed = (n: number) => `${bytes(n)}/s`;

export function eta(seconds: number): string {
	if (!Number.isFinite(seconds) || seconds <= 0 || seconds >= 8640000) return '∞';
	const h = Math.floor(seconds / 3600);
	const m = Math.floor((seconds % 3600) / 60);
	if (h > 48) return `${Math.round(h / 24)}d`;
	if (h) return `${h}h ${m}m`;
	return m ? `${m}m` : `${Math.round(seconds)}s`;
}

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

export function dayLabel(iso: string): string {
	const d = new Date(iso);
	const today = new Date();
	today.setHours(0, 0, 0, 0);
	const day = new Date(d);
	day.setHours(0, 0, 0, 0);
	const diff = Math.round((+day - +today) / 864e5);
	if (Math.abs(diff) <= 1) return rtf.format(diff, 'day').replace(/^./, (c) => c.toUpperCase());
	return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
}

export function timeLabel(iso: string): string {
	return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

export function ago(iso: string): string {
	const s = (Date.now() - +new Date(iso)) / 1000;
	if (s < 3600) return rtf.format(-Math.max(1, Math.round(s / 60)), 'minute');
	if (s < 86400) return rtf.format(-Math.round(s / 3600), 'hour');
	return rtf.format(-Math.round(s / 86400), 'day');
}
