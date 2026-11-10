import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { setIndexerEnabled } from '$lib/server/svc/prowlarr';

/** Body: `{ enable: boolean }`. */
export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = (await event.request.json().catch(() => ({}))) as { enable?: unknown };
	return handleApi(() => setIndexerEnabled(serviceOf(event.params.id, 'prowlarr'), event.params.indexerId, body?.enable));
};
