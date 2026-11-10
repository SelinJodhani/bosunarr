import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { overview } from '$lib/server/svc/qbittorrent';

export const GET: RequestHandler = ({ params, locals }) =>
	handleApi(() => overview(serviceOf(params.id, 'qbittorrent'), Boolean(locals.user?.is_admin)));
