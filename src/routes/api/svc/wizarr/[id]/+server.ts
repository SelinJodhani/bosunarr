import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { overview } from '$lib/server/svc/wizarr';

export const GET: RequestHandler = ({ params, locals }) =>
	handleApi(async () => {
		const data = await overview(serviceOf(params.id, 'wizarr'));
		if (locals.user?.is_admin) return data;
		// Non-admins: no email addresses (personal data) and no invite codes/links (they are sign-up passes).
		return {
			...data,
			users: data.users.map((u) => ({ ...u, email: null })),
			invitations: data.invitations.map((i) => ({ ...i, code: null, path: null }))
		};
	});
