// Client helpers for Discover: instant detail paint, status labels, write actions.
import type { Availability, DiscoverItem, RequestStatus } from './types';

export const HOME_URL = '/api/discover';

/** Items seen in lists this session, so a detail page can paint before its fetch. */
const seen = new Map<string, DiscoverItem>();

export function remember(items: DiscoverItem[]) {
	for (const it of items) seen.set(`${it.serviceId}-${it.key}`, it);
	// Keep it bounded.
	if (seen.size > 2000) for (const k of [...seen.keys()].slice(0, 500)) seen.delete(k);
}

export const peek = (serviceId: string | number, type: string, id: string | number) => seen.get(`${serviceId}-${type}-${id}`);

export const detailHref = (i: Pick<DiscoverItem, 'serviceId' | 'mediaType' | 'tmdbId'>) => `/discover/${i.serviceId}/${i.mediaType}/${i.tmdbId}`;

export const AVAILABILITY_LABEL: Record<Availability, string> = {
	available: 'Available',
	partial: 'Partially available',
	processing: 'Requested',
	pending: 'Pending approval',
	none: 'Not requested'
};

/** Short badge text for posters; null = no badge. */
export const BADGE: Record<Availability, string | null> = {
	available: 'Available',
	partial: 'Partial',
	processing: 'Requested',
	pending: 'Pending',
	none: null
};

export const REQUEST_LABEL: Record<RequestStatus, string> = {
	pending: 'Pending approval',
	approved: 'Approved',
	declined: 'Declined',
	failed: 'Failed',
	completed: 'Completed'
};

/** De-duplicate by key (stale saved data or page overlaps must never produce a duplicate keyed-each entry). */
export function uniq<T extends { key: string }>(list: T[]): T[] {
	const keys = new Set<string>();
	return list.filter((x) => !keys.has(x.key) && (keys.add(x.key), true));
}

/** POST JSON; resolves to an error message or null on success. */
export async function post(url: string, body: unknown): Promise<string | null> {
	const res = await fetch(url, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body)
	}).catch(() => null);
	if (!res) return 'Network error';
	if (res.ok) return null;
	const b = await res.json().catch(() => ({}));
	return b?.error ?? b?.message ?? `HTTP ${res.status}`;
}
