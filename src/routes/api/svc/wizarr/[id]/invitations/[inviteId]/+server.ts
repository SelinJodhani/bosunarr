import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { deleteInvite, parseId } from '$lib/server/svc/wizarr';

export const DELETE: RequestHandler = (event) => {
	requireAdmin(event);
	return handleApi(() => deleteInvite(serviceOf(event.params.id, 'wizarr'), parseId(event.params.inviteId)));
};
