import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parseKind, parsePage, wanted } from '$lib/server/svc/bazarr';

export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'bazarr');
		return wanted(s, parseKind(url.searchParams.get('kind')), parsePage(url.searchParams.get('page')));
	});
