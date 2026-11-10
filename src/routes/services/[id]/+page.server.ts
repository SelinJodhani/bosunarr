import { error, redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { getService, toPublic } from '$lib/server/services/store';

export const load: PageServerLoad = ({ params }) => {
	const s = getService(Number(params.id));
	if (!s) error(404, 'Service not found');
	// Requests are managed in Discover; a separate Seerr screen would only duplicate it.
	if (s.type === 'jellyseerr' || s.type === 'overseerr') redirect(307, '/discover?tab=requests');
	return { service: toPublic(s) };
};
