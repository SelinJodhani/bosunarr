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
	// 4.x answers 200 "Ok."; 5.2+ answers 204 with no body. Either way success = 2xx + a session cookie.
	if (res.ok && text !== 'Fails.' && m) {
		qbitFailures.delete(s.id);
		const cookie = `${m[1]}=${m[2]}`;
		qbitSids.set(s.id, cookie);
		return cookie;
	}
	const message =
		res.status === 403 && /banned/i.test(text)
			? 'qBittorrent has temporarily banned this IP after failed logins — wait and try again'
			: res.ok && text !== 'Fails.'
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
	// Only seen with `redirect: 'manual'` (the health probe): a redirect is usually a proxy/SSO login page.
	if (res.type === 'opaqueredirect' || (res.status >= 300 && res.status < 400))
		throw new ServiceError(`${s.name} redirected to another page (a login?) — check the URL`, 502);
	if (res.status === 401 || res.status === 403) throw new ServiceError(`${s.name} rejected the credentials`, 401);
	if (!res.ok) throw new ServiceError(`${s.name} returned HTTP ${res.status}`, res.status >= 500 ? 502 : res.status);
	const type = res.headers.get('content-type') ?? '';
	return (type.includes('json') ? await res.json() : await res.text()) as T;
}

type Probe = {
	/** Authenticated API endpoint that answers JSON (qBittorrent: plain-text version). */
	path: string;
	/** Must hold for the body, or the service counts as down (e.g. an HTML page or a proxy error served as 200). */
	valid: (body: unknown) => boolean;
	version?: (body: any) => string | undefined;
};

const isObj = (b: unknown): b is Record<string, unknown> => typeof b === 'object' && b !== null && !Array.isArray(b);
const hasVersion = (b: unknown) => isObj(b) && typeof b.version === 'string';

/**
 * How "Test connection" and the Health widget check each type: an endpoint that needs the credentials and returns
 * JSON of a known shape. Root paths or public pages would say "healthy" for a login redirect or a proxy error.
 */
const PROBES: Record<ServiceRecord['type'], Probe> = {
	sonarr: { path: '/api/v3/system/status', valid: hasVersion, version: (b) => b.version },
	radarr: { path: '/api/v3/system/status', valid: hasVersion, version: (b) => b.version },
	lidarr: { path: '/api/v1/system/status', valid: hasVersion, version: (b) => b.version },
	prowlarr: { path: '/api/v1/system/status', valid: hasVersion, version: (b) => b.version },
	bazarr: { path: '/api/system/status', valid: (b) => isObj(b) && isObj(b.data), version: (b) => b.data?.bazarr_version },
	// /settings/about needs an admin API key (/status is public).
	jellyseerr: { path: '/api/v1/settings/about', valid: hasVersion, version: (b) => b.version },
	overseerr: { path: '/api/v1/settings/about', valid: hasVersion, version: (b) => b.version },
	// Needs the session cookie; answers plain text like "v5.0.4".
	qbittorrent: { path: '/api/v2/app/version', valid: (b) => typeof b === 'string' && /^v?\d+\.\d+/.test(b.trim()), version: (b) => String(b).trim() },
	jellyfin: { path: '/System/Info', valid: (b) => isObj(b) && typeof b.Version === 'string', version: (b) => b.Version },
	// Needs x-api-token (unlike /proxy/*). Jellystat has no cheap version endpoint.
	jellystat: { path: '/api/getLibraries', valid: Array.isArray },
	// Needs X-Api-Key; returns counts {users, invites, pending, expired}.
	wizarr: { path: '/api/status', valid: (b) => isObj(b) && typeof b.users === 'number' }
};

/** Used by "Test connection" and the Health widget; returns the reported version when available. */
export async function testConnection(s: ServiceRecord): Promise<{ ok: true; version?: string; ms: number }> {
	const started = performance.now();
	const probe = PROBES[s.type];
	// No redirect following: a 3xx to a login page must not count as up. 5xx and 401/403 already throw in call().
	const body = await call<unknown>(s, probe.path, { redirect: 'manual' });
	if (!probe.valid(body))
		throw new ServiceError(
			typeof body === 'string' && /<html|<!doctype/i.test(body)
				? `${s.name} answered with a web page instead of its API — check the address`
				: `${s.name} gave an unexpected answer — is this really ${s.type}?`,
			502
		);
	const version = probe.version?.(body) || undefined;
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
