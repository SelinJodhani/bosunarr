import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { adminData } from '$lib/server/svc/jellyfin';
import type { JfAdminData } from '$lib/servicepages/jellyfin/types';

/**
 * Scheduled tasks for everyone; users, the activity log (names, IPs) and task error text
 * (server paths, stack traces) only for Bosunarr admins.
 */
export const GET: RequestHandler = ({ params, locals }) =>
	handleApi(async (): Promise<JfAdminData> => {
		const d = await adminData(serviceOf(params.id, 'jellyfin'));
		return locals.user?.is_admin ? d : { users: null, tasks: d.tasks.map((t) => ({ ...t, lastError: null })), activity: null };
	});
