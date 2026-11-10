import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { summary } from '$lib/server/svc/jellyfin';

export const GET: RequestHandler = ({ params }) => handleApi(() => summary(serviceOf(params.id, 'jellyfin')));
