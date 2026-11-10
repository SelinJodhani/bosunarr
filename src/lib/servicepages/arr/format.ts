// Human-readable labels for the arr service screen.
import { ago } from '$lib/format';

/** "3 days, 5 hours" style uptime from the arr's start time. */
export function uptime(startIso: string | null, now = Date.now()): string | null {
	if (!startIso) return null;
	const s = Math.max(0, (now - +new Date(startIso)) / 1000);
	if (!Number.isFinite(s)) return null;
	const d = Math.floor(s / 86400);
	const h = Math.floor((s % 86400) / 3600);
	const m = Math.floor((s % 3600) / 60);
	const part = (n: number, unit: string) => `${n} ${unit}${n === 1 ? '' : 's'}`;
	if (d) return h ? `${part(d, 'day')}, ${part(h, 'hour')}` : part(d, 'day');
	if (h) return m ? `${part(h, 'hour')}, ${part(m, 'minute')}` : part(h, 'hour');
	return m ? part(m, 'minute') : 'Just started';
}

export function dateTime(iso: string | null): string {
	if (!iso) return '';
	return new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/** "just now" / "5 minutes ago" — `ago()` from $lib/format rounds anything under a minute up to one. */
export function since(iso: string, now = Date.now()): string {
	return now - +new Date(iso) < 60_000 ? 'just now' : ago(iso);
}

/** Time only for today, else a short date and time. */
export function until(iso: string): string {
	const d = new Date(iso);
	const today = d.toDateString() === new Date().toDateString();
	return today
		? d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
		: d.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}

export function shortDate(iso: string | null): string {
	if (!iso) return '';
	const d = new Date(iso);
	const sameYear = d.getFullYear() === new Date().getFullYear();
	return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) });
}

/** "IndexerStatusCheck" → "Indexer status". */
export function healthSource(source: string): string {
	const s = source.replace(/Check$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
	return s ? s[0].toUpperCase() + s.slice(1) : 'Health check';
}

const TASK_STATUS: Record<string, string> = {
	queued: 'Queued',
	started: 'Running',
	completed: 'Done',
	failed: 'Failed',
	aborted: 'Aborted',
	cancelled: 'Cancelled',
	orphaned: 'Interrupted'
};
export const taskStatus = (s: string) => TASK_STATUS[s] ?? s.replace(/^./, (c) => c.toUpperCase());

export const protocol = (p: string) => (p === 'torrent' ? 'Torrent' : p === 'usenet' ? 'Usenet' : p || 'Unknown');
