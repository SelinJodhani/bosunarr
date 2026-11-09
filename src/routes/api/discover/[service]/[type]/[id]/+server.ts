import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { discoverDetail } from '$lib/server/discover';

export const GET: RequestHandler = ({ params }) => handleApi(() => discoverDetail(params.service, params.type, params.id));
