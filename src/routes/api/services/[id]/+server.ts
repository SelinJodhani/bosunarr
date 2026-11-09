import type { RequestHandler } from './$types';
import { deleteService, updateService } from '$lib/server/services/store';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { invalidate, resetServiceSession } from '$lib/server/services/client';

export const PUT: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json();
	return handleApi(() => {
		updateService(Number(event.params.id), body);
		resetServiceSession(Number(event.params.id));
		invalidate('w:');
		invalidate('act:');
		return { ok: true };
	});
};

export const DELETE: RequestHandler = (event) => {
	requireAdmin(event);
	return handleApi(() => {
		deleteService(Number(event.params.id));
		resetServiceSession(Number(event.params.id));
		invalidate('w:');
		invalidate('act:');
		return { ok: true };
	});
};
