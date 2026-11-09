import { redirect } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { SESSION_COOKIE, deleteSession } from '$lib/server/auth';

export const POST: RequestHandler = ({ cookies }) => {
	deleteSession(cookies.get(SESSION_COOKIE), cookies);
	redirect(303, '/login');
};
