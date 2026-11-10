import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parsePage, parseWantedKind, wanted } from '$lib/server/svc/arr';

export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'sonarr', 'radarr');
		return wanted(s, parseWantedKind(url.searchParams.get('kind')), parsePage(url.searchParams.get('page')));
	});
