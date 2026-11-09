import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { requestAction } from '$lib/server/discover';

/** Approve, decline or delete a Seerr request. Admins only. */
export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => ({}));
	return handleApi(() => requestAction(body));
};
