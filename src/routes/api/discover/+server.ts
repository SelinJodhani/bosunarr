import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { discoverHome } from '$lib/server/discover';

export const GET: RequestHandler = () => handleApi(() => discoverHome());
