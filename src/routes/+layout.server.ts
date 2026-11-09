import type { LayoutServerLoad } from './$types';
import { listServices, toPublic } from '$lib/server/services/store';

export const load: LayoutServerLoad = ({ locals }) => ({
	user: locals.user ? { username: locals.user.username, isAdmin: Boolean(locals.user.is_admin) } : null,
	services: locals.user ? listServices().map(toPublic) : []
});
