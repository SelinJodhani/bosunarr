import { fail, redirect } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';
import { checkPassword, createSession, userCount } from '$lib/server/auth';

export const load: PageServerLoad = ({ locals }) => {
	if (userCount() === 0) redirect(303, '/setup');
	if (locals.user) redirect(303, '/');
};

const attempts = new Map<string, { n: number; until: number }>();

export const actions: Actions = {
	default: async ({ request, cookies, url, getClientAddress }) => {
		const ip = getClientAddress();
		const a = attempts.get(ip);
		if (a && a.until > Date.now()) return fail(429, { username: '', error: 'Too many attempts — wait a minute' });

		const form = await request.formData();
		const username = String(form.get('username') ?? '');
		const user = await checkPassword(username, String(form.get('password') ?? ''));
		if (!user) {
			const n = (a?.n ?? 0) + 1;
			attempts.set(ip, { n, until: n >= 5 ? Date.now() + 60_000 : 0 });
			return fail(400, { username, error: 'Wrong username or password' });
		}
		attempts.delete(ip);
		createSession(user.id, cookies, url.protocol === 'https:');
		const next = url.searchParams.get('next');
		redirect(303, next && next.startsWith('/') && !next.startsWith('//') ? next : '/');
	}
};
