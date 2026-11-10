import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { createInvite, parseCreate } from '$lib/server/svc/wizarr';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => null);
	return handleApi(() => createInvite(serviceOf(event.params.id, 'wizarr'), parseCreate(body)));
};
