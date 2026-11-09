import { error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { getService, type ServiceRecord } from '$lib/server/services/store';

/** Only image paths are proxied — this is not a general-purpose proxy. */
const ALLOWED: Partial<Record<ServiceRecord['type'], RegExp>> = {
	sonarr: /^\/(api\/v3\/)?MediaCover\/[\w./-]+$/,
	radarr: /^\/(api\/v3\/)?MediaCover\/[\w./-]+$/,
	lidarr: /^\/(api\/v1\/)?MediaCover\/[\w./-]+$/,
	jellyseerr: /^\/imageproxy\/tmdb\/t\/p\/[\w]+\/[\w.-]+$/,
	overseerr: /^\/imageproxy\/tmdb\/t\/p\/[\w]+\/[\w.-]+$/,
	jellyfin: /^\/Items\/[\w-]+\/Images\/(Primary|Backdrop|Thumb)(\?maxHeight=\d+)?$/
};

export const GET: RequestHandler = async ({ params, url, fetch: _f }) => {
	const s = getService(Number(params.id));
	const path = url.searchParams.get('p') ?? '';
	const allowed = s && ALLOWED[s.type];
	if (!s || !allowed || !allowed.test(path)) error(404, 'Not found');

	const headers: Record<string, string> = {};
	if (s.type === 'jellyfin') headers['Authorization'] = `MediaBrowser Token="${s.credentials.apiKey ?? ''}"`;
	else if (s.credentials.apiKey) headers['X-Api-Key'] = s.credentials.apiKey;

	const res = await fetch(s.url + path, { headers, signal: AbortSignal.timeout(15_000) }).catch(() => null);
	if (!res?.ok || !res.body) error(404, 'Image unavailable');
	return new Response(res.body, {
		headers: {
			'Content-Type': res.headers.get('content-type') ?? 'image/jpeg',
			'Cache-Control': 'private, max-age=86400, stale-while-revalidate=604800'
		}
	});
};
