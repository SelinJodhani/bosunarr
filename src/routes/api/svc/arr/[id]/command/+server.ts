import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { parseCommand, runCommand } from '$lib/server/svc/arr';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json().catch(() => null);
	return handleApi(() => runCommand(serviceOf(event.params.id, 'sonarr', 'radarr'), parseCommand(body)));
};
