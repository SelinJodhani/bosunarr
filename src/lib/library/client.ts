// Client helpers for the Library: instant detail paint and poster-derived theme colours.
import type { LibraryItem, LibraryStatus } from './types';
import { CACHE_PREFIX } from '$lib/http';

export const LIBRARY_URL = '/api/library';

/** Find a list item we already have (memory/localStorage) so the detail page can paint before its fetch. */
export function peekItem(key: string): LibraryItem | undefined {
	try {
		const raw = localStorage.getItem(CACHE_PREFIX + LIBRARY_URL);
		const data = raw ? (JSON.parse(raw) as { items?: LibraryItem[] }) : null;
		return data?.items?.find((i) => i.key === key);
	} catch {
		return undefined;
	}
}

export const detailHref = (i: Pick<LibraryItem, 'serviceId' | 'kind' | 'id'>) => `/library/${i.serviceId}/${i.kind}/${i.id}`;

export const STATUS_LABEL: Record<LibraryStatus, string> = {
	downloaded: 'Downloaded',
	partial: 'Partially downloaded',
	missing: 'Missing',
	upcoming: 'Not released yet',
	unmonitored: 'Unmonitored'
};

const SEED_STORE = 'posterSeeds';
const SEED_MAX = 400;
let seeds: Record<string, string> | null = null;
function seedMap(): Record<string, string> {
	if (seeds) return seeds;
	try {
		seeds = JSON.parse(localStorage.getItem(SEED_STORE) || '{}') as Record<string, string>;
	} catch {
		seeds = {};
	}
	return seeds;
}
function rememberSeed(src: string, seed: string | null) {
	const map = seedMap();
	delete map[src];
	map[src] = seed ?? 'none';
	const keys = Object.keys(map);
	for (const k of keys.slice(0, Math.max(0, keys.length - SEED_MAX))) delete map[k];
	try {
		localStorage.setItem(SEED_STORE, JSON.stringify(map));
	} catch {}
}

/**
 * Synchronous cache lookup so a detail page can apply the poster theme on its first frame.
 * `undefined` = not computed yet; `null` = computed, poster has no usable colour.
 */
export function cachedSeed(src: string | null | undefined): string | null | undefined {
	if (!src) return null;
	const hit = seedMap()[src];
	return hit === undefined ? undefined : hit === 'none' ? null : hit;
}

const pending = new Map<string, Promise<string | null>>();

/** Seed colour extracted from a poster with Material's quantizer + scorer; cached per image URL (localStorage). */
export function posterSeed(src: string): Promise<string | null> {
	const hit = cachedSeed(src);
	if (hit !== undefined) return Promise.resolve(hit);
	let p = pending.get(src);
	if (!p) {
		p = extractSeed(src).then((seed) => {
			rememberSeed(src, seed);
			pending.delete(src);
			return seed;
		});
		pending.set(src, p);
	}
	return p;
}

async function extractSeed(src: string): Promise<string | null> {
	const img = new Image();
	img.decoding = 'async';
	img.src = src;
	try {
		await img.decode();
	} catch {
		return null;
	}
	const w = 48;
	const h = 72;
	const canvas = new OffscreenCanvas(w, h);
	const ctx = canvas.getContext('2d', { willReadFrequently: true });
	if (!ctx) return null;
	ctx.drawImage(img, 0, 0, w, h);
	const { data } = ctx.getImageData(0, 0, w, h);
	const pixels: number[] = [];
	for (let i = 0; i < data.length; i += 4) {
		if (data[i + 3] < 255) continue;
		pixels.push(((255 << 24) | (data[i] << 16) | (data[i + 1] << 8) | data[i + 2]) >>> 0);
	}
	// Loaded lazily so it stays out of the initial bundle.
	const { QuantizerCelebi, Score, hexFromArgb } = await import('@material/material-color-utilities');
	const ranked = Score.score(QuantizerCelebi.quantize(pixels, 64), { fallbackColorARGB: 0, filter: true });
	return ranked[0] ? hexFromArgb(ranked[0]) : null;
}

/**
 * Apply a late-arriving seed with a cross-fade (View Transitions) instead of a hard colour jump.
 * Falls back to an instant swap where the API is missing or motion is reduced.
 */
export function fadeIn(apply: () => void) {
	const doc = document as Document & { startViewTransition?: (cb: () => void) => unknown };
	if (!doc.startViewTransition || matchMedia('(prefers-reduced-motion: reduce)').matches) apply();
	else doc.startViewTransition(apply);
}

/** Label for a detail page's back button, from the page the user came from. */
export function backLabel(fromPath: string | null | undefined): string {
	const seg = fromPath?.split('/')[1] ?? 'library';
	const names: Record<string, string> = {
		'': 'Dashboard',
		library: 'Library',
		calendar: 'Calendar',
		discover: 'Discover',
		activity: 'Activity',
		settings: 'Settings'
	};
	return `Back to ${names[seg] ?? 'previous page'}`;
}
