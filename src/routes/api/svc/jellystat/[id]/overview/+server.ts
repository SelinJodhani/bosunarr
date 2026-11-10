import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { overview } from '$lib/server/svc/jellystat';

export const GET: RequestHandler = ({ params }) => handleApi(() => overview(serviceOf(params.id, 'jellystat')));
