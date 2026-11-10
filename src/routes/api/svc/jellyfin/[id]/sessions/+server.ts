import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { sessions } from '$lib/server/svc/jellyfin';

export const GET: RequestHandler = ({ params }) =>
	handleApi(async () => ({ sessions: await sessions(serviceOf(params.id, 'jellyfin')) }));
