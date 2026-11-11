import { error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { getService, type ServiceRecord } from '$lib/server/services/store';
import { cachedImage } from '$lib/server/imagecache';

/** Only image paths are proxied — this is not a general-purpose proxy. */
// Cache-busting query the arrs append to cover URLs: `?lastWrite=<ticks>` (Sonarr, Radarr ≤ 6.3) or `?h=<hash>` (Radarr 6.4+).
const LAST_WRITE = String.raw`(\?(lastWrite=\d+|h=[0-9a-fA-F]+))?`;
const ALLOWED: Partial<Record<ServiceRecord['type'], RegExp>> = {
	sonarr: new RegExp(String.raw`^/(api/v3/)?MediaCover/[\w./-]+${LAST_WRITE}$`),
	radarr: new RegExp(String.raw`^/(api/v3/)?MediaCover/[\w./-]+${LAST_WRITE}$`),
	lidarr: new RegExp(String.raw`^/(api/v1/)?MediaCover/[\w./-]+${LAST_WRITE}$`),
	jellyseerr: /^\/imageproxy\/tmdb\/t\/p\/[\w]+\/[\w.-]+$/,
	overseerr: /^\/imageproxy\/tmdb\/t\/p\/[\w]+\/[\w.-]+$/,
	jellyfin: /^\/Items\/[\w-]+\/Images\/(Primary|Backdrop|Thumb)(\?maxHeight=\d+)?$/
};
const TMDB = /^\/t\/p\/w\d+\/[\w-]+\.(jpg|png)$/;

export const GET: RequestHandler = async ({ params, url }) => {
	const path = url.searchParams.get('p') ?? '';
	if (path.includes('..')) error(404, 'Not found');

	let target: string;
	const headers: Record<string, string> = {};
	if (params.id === 'tmdb') {
		if (!TMDB.test(path)) error(404, 'Not found');
		target = 'https://image.tmdb.org' + path;
	} else {
		const s = getService(Number(params.id));
		const allowed = s && ALLOWED[s.type];
		if (!s || !allowed || !allowed.test(path)) error(404, 'Not found');
		if (s.type === 'jellyfin') headers['Authorization'] = `MediaBrowser Token="${s.credentials.apiKey ?? ''}"`;
		else if (s.credentials.apiKey) headers['X-Api-Key'] = s.credentials.apiKey;
		target = s.url + path;
	}

	const img = await cachedImage(`${params.id}:${path}`, async () => {
		const res = await fetch(target, { headers, signal: AbortSignal.timeout(15_000) }).catch(() => null);
		const type = res?.headers.get('content-type') ?? '';
		if (!res?.ok || !type.startsWith('image/')) return null;
		return { type, body: new Uint8Array(await res.arrayBuffer()) };
	});
	if (!img) error(404, 'Image unavailable');
	return new Response(img.body as BodyInit, {
		headers: {
			'Content-Type': img.type,
			'Cache-Control': 'private, max-age=604800, stale-while-revalidate=2592000',
			'X-Content-Type-Options': 'nosniff'
		}
	});
};
