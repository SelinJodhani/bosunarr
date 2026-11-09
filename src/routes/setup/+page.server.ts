import { fail, redirect } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';
import { createSession, createUser, userCount } from '$lib/server/auth';

export const load: PageServerLoad = () => {
	if (userCount() > 0) redirect(303, '/login');
};

export const actions: Actions = {
	default: async ({ request, cookies, url }) => {
		if (userCount() > 0) redirect(303, '/login');
		const form = await request.formData();
		const username = String(form.get('username') ?? '').trim();
		const password = String(form.get('password') ?? '');
		if (!/^[\w.@-]{3,32}$/.test(username)) return fail(400, { username, error: 'Username: 3–32 letters, numbers, . _ - @' });
		if (password.length < 10) return fail(400, { username, error: 'Password must be at least 10 characters' });
		const id = await createUser(username, password, true);
		createSession(id, cookies, url.protocol === 'https:');
		redirect(303, '/settings?welcome=1');
	}
};
