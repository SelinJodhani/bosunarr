import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parseKind, parsePage, history } from '$lib/server/svc/bazarr';

export const GET: RequestHandler = ({ params, url }) =>
	handleApi(() => {
		const s = serviceOf(params.id, 'bazarr');
		return history(s, parseKind(url.searchParams.get('kind')), parsePage(url.searchParams.get('page')));
	});
