import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { setAltSpeed } from '$lib/server/svc/qbittorrent';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => null);
	return handleApi(() => setAltSpeed(serviceOf(event.params.id, 'qbittorrent'), body));
};
