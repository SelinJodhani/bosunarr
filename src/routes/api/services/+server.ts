import type { RequestHandler } from './$types';
import { createService, listServices, toPublic } from '$lib/server/services/store';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { invalidate } from '$lib/server/services/client';

export const GET: RequestHandler = () => handleApi(() => listServices().map(toPublic));

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json();
	return handleApi(() => {
		const id = createService(body);
		invalidate(''); // every feature caches per service: drop all of it
		return { id };
	});
};
