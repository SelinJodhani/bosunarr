import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { getService, toPublic } from '$lib/server/services/store';

export const load: PageServerLoad = ({ params }) => {
	const s = getService(Number(params.id));
	if (!s) error(404, 'Service not found');
	return { service: toPublic(s) };
};
