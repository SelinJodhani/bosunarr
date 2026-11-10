import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { grab } from '$lib/server/svc/prowlarr';

/** Body: `{ token }` from the same user's search. Sends the release to Prowlarr's default download client. */
export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = (await event.request.json().catch(() => ({}))) as { token?: unknown };
	return handleApi(() => grab(serviceOf(event.params.id, 'prowlarr'), event.locals.user!.id, body?.token));
};
