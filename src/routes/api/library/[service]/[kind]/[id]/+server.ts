import { error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { libraryDetail } from '$lib/server/library';

export const GET: RequestHandler = ({ params }) => {
	if (params.kind !== 'movie' && params.kind !== 'series') error(404, 'Not found');
	return handleApi(() => libraryDetail(Number(params.service), params.kind as 'movie' | 'series', Number(params.id)));
};
