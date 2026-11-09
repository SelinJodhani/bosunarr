import type { RequestHandler } from './$types';
import { getService, validateInput, type ServiceRecord } from '$lib/server/services/store';
import { testConnection } from '$lib/server/services/client';
import { handleApi, requireAdmin } from '$lib/server/guard';

/** Tests unsaved form values. When editing, blank credential fields fall back to the stored ones. */
export const POST: RequestHandler = async (event) => {
	requireAdmin(event);
	const body = await event.request.json();
	return handleApi(async () => {
		const v = validateInput(body);
		const stored = body.id ? getService(Number(body.id)) : undefined;
		const creds = { ...(stored?.credentials ?? {}) };
		for (const k of ['apiKey', 'username', 'password'] as const) if (v.credentials[k]) creds[k] = v.credentials[k];
		const record: ServiceRecord = { id: -Date.now(), type: v.type, name: v.name, url: v.url, externalUrl: null, credentials: creds };
		return testConnection(record);
	});
};
