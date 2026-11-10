import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import {
	EXPIRY_DAYS,
	type CreatedInvite,
	type CreateInviteRequest,
	type InviteStatus,
	type WizarrInvite,
	type WizarrLibrary,
	type WizarrOverview,
	type WizarrServer,
	type WizarrUser
} from '$lib/servicepages/wizarr/types';

/*
 * Wizarr REST API (flask-restx, `/api`, header `X-API-Key`), shapes checked against
 * wizarrrr/wizarr app/blueprints/api/api_routes.py (2026-11):
 *   GET    /api/status            {users, invites, pending, expired}
 *   GET    /api/users             {users: [{id, username, email, server, server_type, expires, created_at}], count}
 *   DELETE /api/users/:id         {message}
 *   GET    /api/invitations       {invitations: [{id, code, url, status, created, expires, used_at, used_by,
 *                                   duration ("unlimited" | days), unlimited, specific_libraries: number[],
 *                                   display_name, server_names}], count}
 *   POST   /api/invitations       {server_ids, expires_in_days (1|7|30|omitted=never), duration, unlimited, library_ids}
 *                                 → 201 {message, invitation: {id, code, url, …}}
 *   DELETE /api/invitations/:id   {message}
 *   GET    /api/libraries         {libraries: [{id, name, external_id, server_id, server_name, enabled}], count}
 *   GET    /api/servers           {servers: [{id, name, server_type, server_url, verified, …}], count}
 */

type RawUser = { id: number; username?: string; email?: string | null; server?: string; server_type?: string; expires?: string | null; created_at?: string | null };
type RawInvite = {
	id: number;
	code: string;
	url?: string;
	status?: string;
	created?: string | null;
	expires?: string | null;
	used_at?: string | null;
	used_by?: string | null;
	duration?: string | null;
	unlimited?: boolean | null;
	specific_libraries?: number[] | null;
	server_names?: string[] | null;
	display_name?: string | null;
};
type RawLibrary = { id: number; name?: string; server_id?: number | null; server_name?: string; enabled?: boolean };
type RawServer = { id: number; name?: string; server_type?: string; verified?: boolean };

const TTL = 15_000;
const key = (s: ServiceRecord) => `svc:wizarr:${s.id}:`;

/** Wizarr returns naive UTC timestamps ("2026-11-10T12:00:00"); make them unambiguous. */
function iso(v: unknown): string | null {
	if (typeof v !== 'string' || !v) return null;
	const withZone = /[zZ]|[+-]\d\d:?\d\d$/.test(v) ? v : `${v}Z`;
	const d = new Date(withZone);
	return Number.isNaN(+d) ? null : d.toISOString();
}

const str = (v: unknown) => (typeof v === 'string' && v ? v : null);

function list<T>(body: unknown, field: string, label: string): T[] {
	const v = body && typeof body === 'object' ? (body as Record<string, unknown>)[field] : undefined;
	if (!Array.isArray(v)) throw new ServiceError(`Wizarr sent an unexpected ${label} response`, 502);
	return v.filter((x) => x && typeof x === 'object' && Number.isInteger((x as { id?: unknown }).id)) as T[];
}

/** Join-page path; only accept a plain relative path from Wizarr, else build the default `/j/<code>`. */
function joinPath(code: string, url: unknown): string {
	if (typeof url === 'string' && /^\/[A-Za-z0-9/_-]+$/.test(url)) return url;
	return `/j/${encodeURIComponent(code)}`;
}

function mapInvite(r: RawInvite, libNames: Map<number, string>): WizarrInvite {
	const status: InviteStatus = r.status === 'used' || r.status === 'expired' ? r.status : 'pending';
	const expires = iso(r.expires);
	const unlimited = r.unlimited === true;
	const notExpired = !expires || +new Date(expires) > Date.now();
	const days = r.duration && /^\d+$/.test(r.duration) ? Number(r.duration) : null;
	return {
		id: r.id,
		code: String(r.code),
		path: joinPath(String(r.code), r.url),
		status,
		active: status === 'pending' || (unlimited && status === 'used' && notExpired),
		unlimited,
		created: iso(r.created),
		expires,
		usedAt: iso(r.used_at),
		usedBy: str(r.used_by),
		durationDays: days,
		libraries: (r.specific_libraries ?? []).map((id) => libNames.get(id) ?? `Library ${id}`),
		servers: Array.isArray(r.server_names) && r.server_names.length ? r.server_names.map(String) : r.display_name ? [r.display_name] : []
	};
}

const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

export function overview(s: ServiceRecord): Promise<WizarrOverview> {
	return cached(`${key(s)}overview`, TTL, async () => {
		const [status, users, invites, libraries, servers] = await Promise.allSettled([
			call(s, '/api/status'),
			call(s, '/api/users'),
			call(s, '/api/invitations'),
			call(s, '/api/libraries'),
			call(s, '/api/servers')
		]);
		// Users and invitations are the screen; fail loudly if either is missing.
		if (users.status === 'rejected') throw users.reason;
		if (invites.status === 'rejected') throw invites.reason;
		const warnings: string[] = [];

		let libs: WizarrLibrary[] = [];
		if (libraries.status === 'fulfilled') {
			try {
				libs = list<RawLibrary>(libraries.value, 'libraries', 'libraries').map((l) => ({
					id: l.id,
					name: str(l.name) ?? `Library ${l.id}`,
					serverId: Number.isInteger(l.server_id) ? (l.server_id as number) : null,
					serverName: str(l.server_name) ?? '',
					enabled: l.enabled !== false
				}));
			} catch (e) {
				warnings.push(errMsg(e));
			}
		} else warnings.push(`Libraries: ${errMsg(libraries.reason)}`);

		let srvs: WizarrServer[] = [];
		if (servers.status === 'fulfilled') {
			try {
				srvs = list<RawServer>(servers.value, 'servers', 'servers').map((v) => ({
					id: v.id,
					name: str(v.name) ?? `Server ${v.id}`,
					type: str(v.server_type) ?? '',
					verified: v.verified !== false
				}));
			} catch (e) {
				warnings.push(errMsg(e));
			}
		} else warnings.push(`Servers: ${errMsg(servers.reason)}`);

		const libNames = new Map(libs.map((l) => [l.id, l.name]));
		const userList: WizarrUser[] = list<RawUser>(users.value, 'users', 'users').map((u) => ({
			id: u.id,
			username: str(u.username) ?? `User ${u.id}`,
			email: str(u.email),
			server: str(u.server),
			serverType: str(u.server_type),
			expires: iso(u.expires),
			created: iso(u.created_at)
		}));
		const inviteList = list<RawInvite>(invites.value, 'invitations', 'invitations')
			.filter((i) => typeof i.code === 'string' || typeof i.code === 'number')
			.map((i) => mapInvite(i, libNames))
			.sort((a, b) => +new Date(b.created ?? 0) - +new Date(a.created ?? 0));

		const by = (st: InviteStatus) => inviteList.filter((i) => i.status === st).length;
		const st = status.status === 'fulfilled' && status.value && typeof status.value === 'object' ? (status.value as Record<string, unknown>) : {};
		const num = (v: unknown, fallback: number) => (typeof v === 'number' && Number.isFinite(v) ? v : fallback);
		const total = num(st.invites, inviteList.length);
		const pending = num(st.pending, by('pending'));
		const expired = num(st.expired, by('expired'));
		return {
			counts: { users: num(st.users, userList.length), invites: total, pending, expired, used: Math.max(0, total - pending - expired) },
			users: userList,
			invitations: inviteList,
			servers: srvs,
			libraries: libs,
			warnings
		};
	});
}

/* ---------------------------------------------------------------- */
/* Writes (admin-only at the route)                                  */
/* ---------------------------------------------------------------- */

function idList(v: unknown, label: string, max = 50): number[] {
	if (v === undefined || v === null) return [];
	if (!Array.isArray(v) || v.length > max || !v.every((x) => Number.isInteger(x) && x > 0)) throw new ServiceError(`Invalid ${label}`, 400);
	return [...new Set(v as number[])];
}

export function parseCreate(body: unknown): CreateInviteRequest {
	if (!body || typeof body !== 'object') throw new ServiceError('Invalid request', 400);
	const b = body as Record<string, unknown>;
	const serverIds = idList(b.serverIds, 'servers');
	if (!serverIds.length) throw new ServiceError('Choose at least one server', 400);
	const libraryIds = idList(b.libraryIds, 'libraries', 500);
	const exp = b.expiresInDays;
	if (exp !== null && !EXPIRY_DAYS.includes(exp as (typeof EXPIRY_DAYS)[number])) throw new ServiceError('Expiry must be 1, 7 or 30 days, or never', 400);
	if (typeof b.unlimited !== 'boolean') throw new ServiceError('Invalid uses setting', 400);
	const dur = b.durationDays;
	if (dur !== null && !(Number.isInteger(dur) && (dur as number) >= 1 && (dur as number) <= 3650))
		throw new ServiceError('Access duration must be 1–3650 days, or unlimited', 400);
	return {
		serverIds,
		libraryIds,
		expiresInDays: exp as CreateInviteRequest['expiresInDays'],
		unlimited: b.unlimited,
		durationDays: dur as number | null
	};
}

export async function createInvite(s: ServiceRecord, req: CreateInviteRequest): Promise<CreatedInvite> {
	// Re-check ids against what this Wizarr actually has, so we never forward arbitrary ids.
	const fresh = await overview(s);
	const servers = new Set(fresh.servers.filter((v) => v.verified).map((v) => v.id));
	if (!req.serverIds.every((id) => servers.has(id))) throw new ServiceError('Unknown or unverified server', 400);
	const allowedLibs = new Set(fresh.libraries.filter((l) => l.serverId === null || req.serverIds.includes(l.serverId)).map((l) => l.id));
	if (!req.libraryIds.every((id) => allowedLibs.has(id))) throw new ServiceError('A selected library is not on the chosen servers', 400);

	const payload: Record<string, unknown> = {
		server_ids: req.serverIds,
		duration: req.durationDays === null ? 'unlimited' : String(req.durationDays),
		unlimited: req.unlimited,
		library_ids: req.libraryIds
	};
	if (req.expiresInDays !== null) payload.expires_in_days = req.expiresInDays;

	const res = await call<{ invitation?: { id?: number; code?: string; url?: string } }>(s, '/api/invitations', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(payload)
	});
	invalidate(key(s));
	const inv = res && typeof res === 'object' ? res.invitation : undefined;
	if (!inv || typeof inv.code !== 'string') throw new ServiceError('Wizarr did not return the new invite', 502);
	return { id: Number(inv.id) || 0, code: inv.code, path: joinPath(inv.code, inv.url) };
}

export function parseId(v: string | undefined): number {
	const n = Number(v);
	if (!v || !/^\d+$/.test(v) || !Number.isSafeInteger(n) || n < 1) throw new ServiceError('Invalid id', 400);
	return n;
}

export async function deleteInvite(s: ServiceRecord, id: number): Promise<{ ok: true }> {
	await call(s, `/api/invitations/${id}`, { method: 'DELETE' });
	invalidate(key(s));
	return { ok: true };
}

export async function deleteUser(s: ServiceRecord, id: number): Promise<{ ok: true }> {
	await call(s, `/api/users/${id}`, { method: 'DELETE' });
	invalidate(key(s));
	return { ok: true };
}
