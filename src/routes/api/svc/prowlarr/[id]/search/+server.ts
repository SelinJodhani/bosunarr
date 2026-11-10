import type { RequestHandler } from './$types';
import { handleApi, requireAdmin } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { search } from '$lib/server/svc/prowlarr';

/**
 * `?q=…&cat=2000&cat=5000&indexer=3` — categories and indexers are optional and repeatable.
 * Admin-only: a search fans out to every indexer (private-tracker API quotas, Prowlarr history).
 */
export const GET: RequestHandler = (event) => {
	requireAdmin(event);
	return handleApi(() => search(serviceOf(event.params.id, 'prowlarr'), event.locals.user!.id, event.url.searchParams));
};
