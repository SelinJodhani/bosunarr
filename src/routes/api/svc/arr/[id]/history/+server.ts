import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { history, parseHistoryFilter, parsePage } from '$lib/server/svc/arr';

export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'sonarr', 'radarr');
		return history(s, parseHistoryFilter(url.searchParams.get('event')), parsePage(url.searchParams.get('page')));
	});
