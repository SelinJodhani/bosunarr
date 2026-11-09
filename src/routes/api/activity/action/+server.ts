import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { runAction } from '$lib/server/activity';
import type { ActionRequest } from '$lib/activity/types';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = (await event.request.json().catch(() => ({}))) as ActionRequest;
	return handleApi(() => runAction(body));
};
