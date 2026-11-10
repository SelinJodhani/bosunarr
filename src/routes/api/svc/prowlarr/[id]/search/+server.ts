import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { search } from '$lib/server/svc/prowlarr';

/** `?q=…&cat=2000&cat=5000&indexer=3` — categories and indexers are optional and repeatable. */
export const GET: RequestHandler = ({ params, url }) => handleApi(() => search(serviceOf(params.id, 'prowlarr'), url.searchParams));
