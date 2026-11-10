import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { stats } from '$lib/server/svc/prowlarr';

/** `?days=7|30|90` */
export const GET: RequestHandler = ({ params, url }) => handleApi(() => stats(serviceOf(params.id, 'prowlarr'), url.searchParams.get('days')));
