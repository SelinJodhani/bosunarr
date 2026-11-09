import { error, json, type RequestEvent } from '@sveltejs/kit';
import { ServiceError } from './services/client';

export function requireAdmin(event: RequestEvent) {
	if (!event.locals.user?.is_admin) error(403, 'Admins only');
}

/** Wraps an API handler so service/validation errors become clean JSON responses. */
export async function handleApi<T>(fn: () => Promise<T> | T) {
	try {
		return json(await fn());
	} catch (e) {
		if (e instanceof ServiceError) return json({ error: e.message }, { status: e.status });
		if (e && typeof e === 'object' && 'status' in e && 'body' in e) throw e; // SvelteKit error()
		return json({ error: e instanceof Error ? e.message : 'Unexpected error' }, { status: 400 });
	}
}
