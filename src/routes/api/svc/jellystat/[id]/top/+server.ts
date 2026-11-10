import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parseDays, top } from '$lib/server/svc/jellystat';

/** ?days=7|30|90 */
export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'jellystat');
		return top(s, parseDays(url.searchParams.get('days')));
	});
