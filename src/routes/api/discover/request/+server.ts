import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { createRequest } from '$lib/server/discover';

/** Create a Seerr request. Admins only for now (Bosunarr has no per-user Seerr mapping yet). */
export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => ({}));
	return handleApi(() => createRequest(body));
};
