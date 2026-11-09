import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { discoverList } from '$lib/server/discover';

const serviceParam = (u: URL) => (u.searchParams.has('service') ? Number(u.searchParams.get('service')) : undefined);

export const GET: RequestHandler = ({ url }) =>
	handleApi(() => discoverList(url.searchParams.get('section') ?? '', url.searchParams.get('page') ?? 1, serviceParam(url)));
