import type { ServiceRecord } from './store';

export class ServiceError extends Error {
	constructor(
		message: string,
		public status = 502
	) {
		super(message);
	}
}

const TIMEOUT_MS = 10_000;

/** qBittorrent session cookies ("name=value"), per service id. */
const qbitSids = new Map<number, string>();
/** Recent login failures, so a wrong password doesn't hammer qBittorrent into banning our IP. */
const qbitFailures = new Map<number, { at: number; message: string }>();
const LOGIN_BACKOFF_MS = 60_000;

/** qBittorrent ≤4.x names its cookie `SID`; 5.x uses `QBT_SID_<port>`. */
const SID_COOKIE = /(?:^|[\s,;])((?:QBT_)?SID(?:_\d+)?)=([^;,\s]+)/;

const qbitLogins = new Map<number, Promise<string>>();

/** One login at a time per service: parallel calls share it. */
function qbitLogin(s: ServiceRecord): Promise<string> {
	const running = qbitLogins.get(s.id);
	if (running) return running;
	const p = qbitLoginOnce(s).finally(() => qbitLogins.delete(s.id));
	qbitLogins.set(s.id, p);
	return p;
}

async function qbitLoginOnce(s: ServiceRecord): Promise<string> {
	const failed = qbitFailures.get(s.id);
	if (failed && Date.now() - failed.at < LOGIN_BACKOFF_MS) throw new ServiceError(failed.message, 401);
	let res: Response;
	try {
		res = await fetch(`${s.url}/api/v2/auth/login`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/x-www-form-urlencoded', Referer: s.url, Origin: s.url },
			body: new URLSearchParams({ username: s.credentials.username ?? '', password: s.credentials.password ?? '' }),
			signal: AbortSignal.timeout(TIMEOUT_MS)
		});
	} catch (e) {
		const reason = e instanceof Error && e.name === 'TimeoutError' ? 'timed out' : 'is unreachable';
		throw new ServiceError(`${s.name} ${reason}`, 504);
	}
	const text = (await res.text()).trim();
	const cookies = res.headers.getSetCookie?.() ?? [res.headers.get('set-cookie') ?? ''];
	const m = cookies.map((c) => SID_COOKIE.exec(c)).find(Boolean);
	if (res.ok && text === 'Ok.' && m) {
		qbitFailures.delete(s.id);
		const cookie = `${m[1]}=${m[2]}`;
		qbitSids.set(s.id, cookie);
		return cookie;
	}
	const message =
		res.status === 403 && /banned/i.test(text)
			? 'qBittorrent has temporarily banned this IP after failed logins — wait and try again'
			: res.ok && text === 'Ok.'
				? 'qBittorrent accepted the login but sent no session cookie'
				: 'qBittorrent login failed — check username and password';
	qbitFailures.set(s.id, { at: Date.now(), message });
	throw new ServiceError(message, 401);
}

/** Forget cached sessions/failures when a service's settings change. */
export function resetServiceSession(id: number) {
	qbitSids.delete(id);
	qbitFailures.delete(id);
}

function authHeaders(s: ServiceRecord): Record<string, string> {
	const key = s.credentials.apiKey ?? '';
	switch (s.type) {
		case 'bazarr':
			return { 'X-API-KEY': key };
		case 'jellyfin':
			return { Authorization: `MediaBrowser Client="Bosunarr", Device="Server", DeviceId="bosunarr", Version="0.1", Token="${key}"` };
		case 'jellystat':
			return { 'x-api-token': key };
		case 'qbittorrent':
			return {};
		default:
			return { 'X-Api-Key': key };
	}
}

/**
 * Call a service's API with its credentials injected. `path` is relative to the service URL.
 * Returns parsed JSON (or text for non-JSON responses).
 */
export async function call<T = unknown>(s: ServiceRecord, path: string, init: RequestInit = {}, retried = false): Promise<T> {
	const headers: Record<string, string> = { Accept: 'application/json', ...authHeaders(s), ...(init.headers as Record<string, string>) };
	if (s.type === 'qbittorrent') {
		headers.Cookie = qbitSids.get(s.id) ?? (await qbitLogin(s));
		headers.Referer = s.url;
	}
	let res: Response;
	try {
		res = await fetch(s.url + path, { ...init, headers, signal: init.signal ?? AbortSignal.timeout(TIMEOUT_MS) });
	} catch (e) {
		const reason = e instanceof Error && e.name === 'TimeoutError' ? 'timed out' : 'is unreachable';
		throw new ServiceError(`${s.name} ${reason}`, 504);
	}
	if (s.type === 'qbittorrent' && res.status === 403 && !retried) {
		qbitSids.delete(s.id);
		return call<T>(s, path, init, true);
	}
	if (res.status === 401 || res.status === 403) throw new ServiceError(`${s.name} rejected the credentials`, 401);
	if (!res.ok) throw new ServiceError(`${s.name} returned HTTP ${res.status}`, res.status >= 500 ? 502 : res.status);
	const type = res.headers.get('content-type') ?? '';
	return (type.includes('json') ? await res.json() : await res.text()) as T;
}

/** Endpoint used by "Test connection"; returns the reported version when available. */
export async function testConnection(s: ServiceRecord): Promise<{ ok: true; version?: string; ms: number }> {
	const started = performance.now();
	let version: string | undefined;
	switch (s.type) {
		case 'sonarr':
		case 'radarr':
			version = (await call<{ version: string }>(s, '/api/v3/system/status')).version;
			break;
		case 'lidarr':
		case 'prowlarr':
			version = (await call<{ version: string }>(s, '/api/v1/system/status')).version;
			break;
		case 'bazarr':
			version = (await call<{ data: { bazarr_version: string } }>(s, '/api/system/status')).data?.bazarr_version;
			break;
		case 'jellyseerr':
		case 'overseerr':
			version = (await call<{ version: string }>(s, '/api/v1/settings/about')).version;
			break;
		case 'qbittorrent':
			version = String(await call<string>(s, '/api/v2/app/version'));
			break;
		case 'jellyfin':
			version = (await call<{ Version: string }>(s, '/System/Info')).Version;
			break;
		case 'jellystat':
			await call(s, '/api/getLibraries');
			break;
		case 'wizarr':
			await call(s, '/api/status');
			break;
	}
	return { ok: true, version, ms: Math.round(performance.now() - started) };
}

/* ------------------------------------------------------------------ */
/* Tiny in-memory cache: dedupes concurrent requests and keeps widgets */
/* instant while data refreshes in the background.                     */
/* ------------------------------------------------------------------ */
const cache = new Map<string, { at: number; value: Promise<unknown> }>();

export function cached<T>(key: string, ttlMs: number, fn: () => Promise<T>): Promise<T> {
	const hit = cache.get(key);
	if (hit && Date.now() - hit.at < ttlMs) return hit.value as Promise<T>;
	const value = fn();
	cache.set(key, { at: Date.now(), value });
	value.catch(() => cache.delete(key));
	return value;
}

export function invalidate(prefix: string) {
	for (const k of cache.keys()) if (k.startsWith(prefix)) cache.delete(k);
}
