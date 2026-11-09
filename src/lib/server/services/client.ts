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

/** qBittorrent session cookies, per service id. */
const qbitSids = new Map<number, string>();

async function qbitLogin(s: ServiceRecord): Promise<string> {
	const res = await fetch(`${s.url}/api/v2/auth/login`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded', Referer: s.url, Origin: s.url },
		body: new URLSearchParams({ username: s.credentials.username ?? '', password: s.credentials.password ?? '' }),
		signal: AbortSignal.timeout(TIMEOUT_MS)
	});
	const text = await res.text();
	const sid = res.headers.get('set-cookie')?.match(/SID=([^;]+)/)?.[1];
	if (!res.ok || text.trim() !== 'Ok.' || !sid) throw new ServiceError('qBittorrent login failed — check username and password', 401);
	qbitSids.set(s.id, sid);
	return sid;
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
		const sid = qbitSids.get(s.id) ?? (await qbitLogin(s));
		headers.Cookie = `SID=${sid}`;
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
