// Client helpers for the Library: instant detail paint and poster-derived theme colours.
import type { LibraryItem, LibraryStatus } from './types';

export const LIBRARY_URL = '/api/library';

/** Find a list item we already have (memory/localStorage) so the detail page can paint before its fetch. */
export function peekItem(key: string): LibraryItem | undefined {
	try {
		const raw = localStorage.getItem('swr:' + LIBRARY_URL);
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

/** Seed colour extracted from a poster with Material's quantizer + scorer; cached per image URL. */
export async function posterSeed(src: string): Promise<string | null> {
	const cacheKey = 'seed:' + src;
	try {
		const hit = sessionStorage.getItem(cacheKey);
		if (hit) return hit === 'none' ? null : hit;
	} catch {}
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
	const seed = ranked[0] ? hexFromArgb(ranked[0]) : null;
	try {
		sessionStorage.setItem(cacheKey, seed ?? 'none');
	} catch {}
	return seed;
}
