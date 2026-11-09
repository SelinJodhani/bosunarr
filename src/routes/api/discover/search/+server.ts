import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { discoverSearch } from '$lib/server/discover';

export const GET: RequestHandler = ({ url }) =>
	handleApi(() =>
		discoverSearch(
			url.searchParams.get('q') ?? '',
			url.searchParams.get('page') ?? 1,
			url.searchParams.has('service') ? Number(url.searchParams.get('service')) : undefined
		)
	);
