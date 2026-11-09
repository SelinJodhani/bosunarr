import { db } from '../db';
import { decrypt, encrypt } from '../crypto';
import { SERVICE_TYPES, type PublicService, type ServiceTypeId } from '$lib/services/catalog';

export type Credentials = { apiKey?: string; username?: string; password?: string };

export type ServiceRecord = {
	id: number;
	type: ServiceTypeId;
	name: string;
	url: string;
	externalUrl: string | null;
	credentials: Credentials;
};

type Row = {
	id: number;
	type: ServiceTypeId;
	name: string;
	url: string;
	external_url: string | null;
	credentials: string;
};

const toRecord = (r: Row): ServiceRecord => ({
	id: r.id,
	type: r.type,
	name: r.name,
	url: r.url,
	externalUrl: r.external_url,
	credentials: decrypt<Credentials>(r.credentials)
});

export const toPublic = (s: ServiceRecord): PublicService => ({
	id: s.id,
	type: s.type,
	name: s.name,
	url: s.url,
	externalUrl: s.externalUrl,
	hasCredentials: Boolean(s.credentials.apiKey || s.credentials.password)
});

export function normalizeUrl(raw: string): string {
	const u = new URL(raw.trim());
	if (!['http:', 'https:'].includes(u.protocol)) throw new Error('URL must start with http:// or https://');
	return u.toString().replace(/\/+$/, '');
}

export function listServices(): ServiceRecord[] {
	return (db.prepare('SELECT * FROM services ORDER BY sort, id').all() as Row[]).map(toRecord);
}

export function getService(id: number): ServiceRecord | undefined {
	const r = db.prepare('SELECT * FROM services WHERE id = ?').get(id) as Row | undefined;
	return r && toRecord(r);
}

export function servicesOfType(...types: ServiceTypeId[]): ServiceRecord[] {
	return listServices().filter((s) => types.includes(s.type));
}

export type ServiceInput = {
	type: ServiceTypeId;
	name: string;
	url: string;
	externalUrl?: string | null;
	credentials: Credentials;
};

export function validateInput(input: ServiceInput): ServiceInput {
	if (!(input.type in SERVICE_TYPES)) throw new Error('Unknown service type');
	const name = input.name?.trim();
	if (!name) throw new Error('Name is required');
	return {
		type: input.type,
		name: name.slice(0, 60),
		url: normalizeUrl(input.url),
		externalUrl: input.externalUrl ? normalizeUrl(input.externalUrl) : null,
		credentials: input.credentials ?? {}
	};
}

export function createService(input: ServiceInput): number {
	const v = validateInput(input);
	const r = db
		.prepare(
			'INSERT INTO services (type, name, url, external_url, credentials, sort, created_at) VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort),0)+1 FROM services), ?)'
		)
		.run(v.type, v.name, v.url, v.externalUrl, encrypt(v.credentials), Date.now());
	return Number(r.lastInsertRowid);
}

/** Empty credential fields keep the stored value, so the UI never needs to read secrets back. */
export function updateService(id: number, input: ServiceInput) {
	const existing = getService(id);
	if (!existing) throw new Error('Service not found');
	const v = validateInput(input);
	const creds: Credentials = { ...existing.credentials };
	for (const k of ['apiKey', 'username', 'password'] as const) if (v.credentials[k]) creds[k] = v.credentials[k];
	db.prepare('UPDATE services SET type=?, name=?, url=?, external_url=?, credentials=? WHERE id=?').run(
		v.type,
		v.name,
		v.url,
		v.externalUrl,
		encrypt(creds),
		id
	);
}

export function deleteService(id: number) {
	db.prepare('DELETE FROM services WHERE id = ?').run(id);
}
