import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { calendar, parseRange } from '$lib/server/calendar';

export const GET: RequestHandler = ({ url }) =>
	handleApi(() => {
		const { start, end } = parseRange(url.searchParams.get('start'), url.searchParams.get('end'));
		return calendar(start, end);
	});
