import { json, redirect, type Handle } from '@sveltejs/kit';
import { SESSION_COOKIE, userCount, validateSession } from '$lib/server/auth';

const PUBLIC_PATHS = ['/login', '/setup', '/manifest.webmanifest', '/icon.svg', '/health'];

export const handle: Handle = async ({ event, resolve }) => {
	const path = event.url.pathname;
	event.locals.user = validateSession(event.cookies.get(SESSION_COOKIE));

	const isPublic = PUBLIC_PATHS.some((p) => path === p || path.startsWith(p + '/')) || path.startsWith('/_app/');

	if (!isPublic) {
		if (userCount() === 0) {
			if (path.startsWith('/api/')) return json({ error: 'Setup required' }, { status: 401 });
			redirect(303, '/setup');
		}
		if (!event.locals.user) {
			if (path.startsWith('/api/')) return json({ error: 'Not signed in' }, { status: 401 });
			redirect(303, `/login?next=${encodeURIComponent(path + event.url.search)}`);
		}
	}

	const response = await resolve(event);
	response.headers.set('X-Content-Type-Options', 'nosniff');
	response.headers.set('Referrer-Policy', 'same-origin');
	response.headers.set('X-Frame-Options', 'SAMEORIGIN');
	return response;
};
