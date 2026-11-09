import { error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { WIDGET_LOADERS } from '$lib/server/widgets';
import { handleApi } from '$lib/server/guard';

export const GET: RequestHandler = ({ params }) => {
	const loader = WIDGET_LOADERS[params.kind as keyof typeof WIDGET_LOADERS] as (() => Promise<unknown>) | undefined;
	if (!loader) error(404, 'Unknown widget');
	return handleApi(() => loader());
};
