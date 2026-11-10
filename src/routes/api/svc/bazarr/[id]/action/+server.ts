import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parseAction, runAction } from '$lib/server/svc/bazarr';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => null);
	return handleApi(() => runAction(serviceOf(event.params.id, 'bazarr'), parseAction(body)));
};
