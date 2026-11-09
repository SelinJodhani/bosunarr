import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { addTorrent } from '$lib/server/activity';
import type { AddTorrentRequest } from '$lib/activity/types';

export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = (await event.request.json().catch(() => ({}))) as AddTorrentRequest;
	return handleApi(() => addTorrent(body));
};
