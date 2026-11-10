import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { history, parseHistoryQuery } from '$lib/server/svc/jellystat';

/** ?page=1..&size=10|25|50&q=<title search, ≤100 chars> */
export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'jellystat');
		const { page, size, q } = parseHistoryQuery(url.searchParams);
		return history(s, page, size, q);
	});
