import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { overview } from '$lib/server/svc/wizarr';

export const GET: RequestHandler = ({ params, locals }) =>
	handleApi(async () => {
		const data = await overview(serviceOf(params.id, 'wizarr'));
		// Email addresses are personal data: only admins see them.
		return locals.user?.is_admin ? data : { ...data, users: data.users.map((u) => ({ ...u, email: null })) };
	});
