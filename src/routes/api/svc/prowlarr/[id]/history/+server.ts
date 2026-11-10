import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { history } from '$lib/server/svc/prowlarr';

export const GET: RequestHandler = ({ params }) => handleApi(() => history(serviceOf(params.id, 'prowlarr')));
