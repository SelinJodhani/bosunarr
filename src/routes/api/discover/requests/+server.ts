import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { requestList } from '$lib/server/discover';

export const GET: RequestHandler = ({ url }) =>
	handleApi(() => requestList(url.searchParams.get('filter') ?? 'pending', url.searchParams.get('page') ?? 1));
