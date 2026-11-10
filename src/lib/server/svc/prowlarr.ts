import { randomBytes } from 'node:crypto';
import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import {
	CATEGORIES,
	STAT_RANGES,
	type HealthIssue,
	type HistoryEntry,
	type HistoryResponse,
	type Indexer,
	type Overview,
	type Protocol,
	type SearchResponse,
	type SearchResult,
	type StatsResponse
} from '$lib/servicepages/prowlarr/types';

/* Prowlarr API (v1) shapes — only the fields we read. */
type PIndexer = {
	id: number;
	name: string;
	enable: boolean;
	protocol?: string;
	privacy?: string;
	priority?: number;
	implementationName?: string;
};
type PIndexerStatus = { indexerId: number; initialFailure?: string; mostRecentFailure?: string; disabledTill?: string };
type PHealth = { source?: string; type?: string; message?: string; wikiUrl?: string | { fullUri?: string } };
type PRelease = {
	guid: string;
	indexerId: number;
	indexer?: string;
	title?: string;
	size?: number;
	publishDate?: string;
	ageHours?: number;
	seeders?: number | null;
	leechers?: number | null;
	grabs?: number | null;
	protocol?: string;
	categories?: Array<{ id: number; name?: string }>;
	infoUrl?: string;
};
type PStat = {
	indexerId: number;
	indexerName?: string;
	averageResponseTime?: number;
	numberOfQueries?: number;
	numberOfGrabs?: number;
	numberOfRssQueries?: number;
	numberOfFailedQueries?: number;
	numberOfFailedGrabs?: number;
};
type PHistory = {
	id: number;
	indexerId: number;
	date: string;
	successful?: boolean;
	eventType?: string;
	data?: Record<string, string | null | undefined>;
};

const key = (s: ServiceRecord, part: string) => `svc:prowlarr:${s.id}:${part}`;
const protocol = (p?: string): Protocol => (p === 'torrent' || p === 'usenet' ? p : 'unknown');
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const int = (v: unknown) => {
	const n = Number(v);
	return Number.isFinite(n) ? Math.round(n) : null;
};

/** Only plain http(s) links without credentials in the query string are passed to the browser. */
function safeLink(u?: string): string | null {
	if (!u) return null;
	try {
		const url = new URL(u);
		if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
		if (url.username || url.password) return null;
		for (const k of url.searchParams.keys()) if (/key|pass|token|auth|rss|sid/i.test(k)) return null;
		return url.toString();
	} catch {
		return null;
	}
}

/* ---------------------------------------------------------------- */
/* Overview: version, health, indexers                               */
/* ---------------------------------------------------------------- */
const indexersRaw = (s: ServiceRecord) => cached(key(s, 'indexers'), 15_000, () => call<PIndexer[]>(s, '/api/v1/indexer'));

export function overview(s: ServiceRecord): Promise<Overview> {
	return cached(key(s, 'overview'), 15_000, async () => {
		const [status, health, list, statuses] = await Promise.all([
			call<{ version?: string }>(s, '/api/v1/system/status').catch(() => null),
			call<PHealth[]>(s, '/api/v1/health').catch(() => [] as PHealth[]),
			indexersRaw(s),
			call<PIndexerStatus[]>(s, '/api/v1/indexerstatus').catch(() => [] as PIndexerStatus[])
		]);
		if (!Array.isArray(list)) throw new ServiceError(`${s.name} sent an unexpected indexer list`, 502);
		const byIndexer = new Map((Array.isArray(statuses) ? statuses : []).map((x) => [x.indexerId, x]));
		const now = Date.now();
		const indexers = list
			.map((i): Indexer => {
				const st = byIndexer.get(i.id);
				const failing = st && (st.mostRecentFailure || (st.disabledTill && +new Date(st.disabledTill) > now));
				return {
					id: i.id,
					name: String(i.name ?? `Indexer ${i.id}`),
					enabled: Boolean(i.enable),
					protocol: protocol(i.protocol),
					privacy: String(i.privacy ?? ''),
					priority: num(i.priority) ?? 25,
					implementation: String(i.implementationName ?? ''),
					failure: failing
						? { since: st.initialFailure ?? null, last: st.mostRecentFailure ?? null, disabledTill: st.disabledTill ?? null }
						: null
				};
			})
			.sort((a, b) => a.name.localeCompare(b.name));
		const issues = (Array.isArray(health) ? health : [])
			.filter((h) => h.type === 'notice' || h.type === 'warning' || h.type === 'error')
			.map(
				(h): HealthIssue => ({
					source: String(h.source ?? ''),
					type: h.type as HealthIssue['type'],
					message: String(h.message ?? ''),
					wikiUrl: safeLink(typeof h.wikiUrl === 'string' ? h.wikiUrl : h.wikiUrl?.fullUri)
				})
			);
		return { version: status?.version ?? null, health: issues, indexers };
	});
}

export async function setIndexerEnabled(s: ServiceRecord, indexerId: unknown, enable: unknown): Promise<{ ok: true }> {
	const id = Number(indexerId);
	if (!Number.isInteger(id) || id <= 0) throw new ServiceError('Invalid indexer', 400);
	if (typeof enable !== 'boolean') throw new ServiceError('"enable" must be true or false', 400);
	// Prowlarr's PUT needs the full resource, so read it back and flip one field.
	const current = await call<Record<string, unknown> & { id: number }>(s, `/api/v1/indexer/${id}`);
	if (!current || typeof current !== 'object' || current.id !== id) throw new ServiceError('Indexer not found', 404);
	try {
		await putIndexer(s, id, { ...current, enable });
	} catch (e) {
		// Enabling makes Prowlarr test the indexer; its validation text is written for its own log view.
		if (enable && e instanceof ServiceError && e.status === 400) {
			const name = typeof current.name === 'string' && current.name ? current.name : 'The indexer';
			const raw = e.message.replace(/^[^:]*:\s*/, '');
			const reason = /unable to connect|connection|timed? ?out|unreachable|name or service/i.test(raw)
				? `Prowlarr couldn't connect to ${name}`
				: /captcha|cloudflare/i.test(raw)
					? `${name} is behind a CAPTCHA or Cloudflare check that Prowlarr couldn't pass`
					: /api ?key|credential|unauthori[sz]ed|login|password|cookie/i.test(raw)
						? `${name} rejected the login details saved in Prowlarr`
						: `${name} failed Prowlarr's test`;
			throw new ServiceError(`${reason}, so it stays disabled. Check the indexer's settings in Prowlarr.`, 400);
		}
		throw e;
	}
	invalidate(key(s, ''));
	return { ok: true };
}

/**
 * PUT that keeps Prowlarr's validation message. `call()` reduces a 400 to "returned HTTP 400", but when
 * enabling fails (the indexer test failed) the body says why: `[{ propertyName, errorMessage }]`.
 */
async function putIndexer(s: ServiceRecord, id: number, body: unknown) {
	let res: Response;
	try {
		res = await fetch(`${s.url}/api/v1/indexer/${id}`, {
			method: 'PUT',
			headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Api-Key': s.credentials.apiKey ?? '' },
			body: JSON.stringify(body),
			signal: AbortSignal.timeout(30_000) // enabling makes Prowlarr test the indexer
		});
	} catch (e) {
		const reason = e instanceof Error && e.name === 'TimeoutError' ? 'timed out' : 'is unreachable';
		throw new ServiceError(`${s.name} ${reason}`, 504);
	}
	if (res.ok) return;
	if (res.status === 401 || res.status === 403) throw new ServiceError(`${s.name} rejected the credentials`, 401);
	const data = (res.headers.get('content-type') ?? '').includes('json') ? await res.json().catch(() => null) : null;
	const messages = (Array.isArray(data) ? data : [data])
		.map((x) => (x && typeof x === 'object' ? (x as { errorMessage?: unknown; message?: unknown }).errorMessage ?? (x as { message?: unknown }).message : null))
		.filter((m): m is string => typeof m === 'string' && m.trim() !== '')
		.map((m) => m.trim().slice(0, 300));
	if (res.status === 400 && messages.length) throw new ServiceError(`${s.name}: ${[...new Set(messages)].join(' · ')}`, 400);
	throw new ServiceError(`${s.name} returned HTTP ${res.status}`, res.status >= 500 ? 502 : res.status);
}

/* ---------------------------------------------------------------- */
/* Search + grab                                                     */
/* ---------------------------------------------------------------- */
const RELEASE_TTL_MS = 25 * 60_000; // Prowlarr keeps releases grabbable for 30 minutes
const SWEEP_EVERY_MS = 60_000;
const MAX_RESULTS = 500;
/** Per (service, user) bucket cap: a few recent searches. Oldest tokens go first when exceeded. */
const MAX_PER_BUCKET = MAX_RESULTS * 4;

type Release = { guid: string; indexerId: number; title: string; at: number };
/**
 * `${serviceId}:${userId}` → token → what Prowlarr needs to grab it. Kept server-side so guids (often
 * with passkeys) never leave, and a token only works for the user and service that searched.
 * Maps keep insertion order, so the first entries are always the oldest.
 */
const releases = new Map<string, Map<string, Release>>();
let lastSweep = 0;

function sweep(now: number) {
	if (now - lastSweep < SWEEP_EVERY_MS) return;
	lastSweep = now;
	for (const [b, bucket] of releases) {
		for (const [t, r] of bucket) {
			if (now - r.at <= RELEASE_TTL_MS) break; // the rest are newer
			bucket.delete(t);
		}
		if (!bucket.size) releases.delete(b);
	}
}

const bucketKey = (serviceId: number, userId: number) => `${serviceId}:${userId}`;

function remember(bucket: Map<string, Release>, r: PRelease, now: number): string {
	const token = randomBytes(12).toString('base64url');
	bucket.set(token, { guid: r.guid, indexerId: r.indexerId, title: r.title ?? '', at: now });
	if (bucket.size > MAX_PER_BUCKET) bucket.delete(bucket.keys().next().value!);
	return token;
}

const CATEGORY_IDS = new Set<number>(CATEGORIES.map((c) => c.id));

export async function search(s: ServiceRecord, userId: number, params: URLSearchParams): Promise<SearchResponse> {
	const query = (params.get('q') ?? '').trim();
	if (!query) throw new ServiceError('Enter something to search for', 400);
	if (query.length > 200) throw new ServiceError('Search is too long', 400);
	const cats = params.getAll('cat').map(Number);
	if (cats.length > CATEGORY_IDS.size || cats.some((c) => !CATEGORY_IDS.has(c))) throw new ServiceError('Invalid category', 400);
	const ids = params.getAll('indexer').map(Number);
	if (ids.length > 200 || ids.some((i) => !Number.isInteger(i) || i <= 0)) throw new ServiceError('Invalid indexer', 400);

	const qs = new URLSearchParams({ query, type: 'search', limit: '100', offset: '0' });
	for (const c of new Set(cats)) qs.append('categories', String(c));
	for (const i of new Set(ids)) qs.append('indexerIds', String(i));
	const list = await call<PRelease[]>(s, `/api/v1/search?${qs}`, { signal: AbortSignal.timeout(90_000) });
	if (!Array.isArray(list)) throw new ServiceError(`${s.name} sent an unexpected search response`, 502);

	const valid = list.filter((r) => r && typeof r.guid === 'string' && Number.isInteger(r.indexerId));
	const now = Date.now();
	sweep(now);
	const b = bucketKey(s.id, userId);
	const bucket = releases.get(b) ?? new Map<string, Release>();
	releases.set(b, bucket);
	const results = valid.slice(0, MAX_RESULTS).map(
		(r): SearchResult => ({
			token: remember(bucket, r, now),
			title: String(r.title ?? '(untitled)'),
			indexerId: r.indexerId,
			indexer: String(r.indexer ?? ''),
			size: num(r.size) ?? 0,
			publishDate: r.publishDate ?? null,
			ageHours: num(r.ageHours),
			seeders: num(r.seeders),
			leechers: num(r.leechers),
			grabs: num(r.grabs),
			protocol: protocol(r.protocol),
			categories: [...new Set((r.categories ?? []).filter((c) => c.id < 100000).map((c) => String(c.name ?? c.id)))],
			infoUrl: safeLink(r.infoUrl)
		})
	);
	return { results, truncated: valid.length > MAX_RESULTS };
}

export async function grab(s: ServiceRecord, userId: number, token: unknown): Promise<{ ok: true; title: string }> {
	if (typeof token !== 'string' || !/^[\w-]{8,32}$/.test(token)) throw new ServiceError('Invalid release', 400);
	const r = releases.get(bucketKey(s.id, userId))?.get(token);
	if (!r || Date.now() - r.at > RELEASE_TTL_MS) {
		throw new ServiceError('This result has expired — search again', 410);
	}
	try {
		await call(s, '/api/v1/search', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ guid: r.guid, indexerId: r.indexerId }),
			signal: AbortSignal.timeout(60_000)
		});
	} catch (e) {
		if (e instanceof ServiceError && e.status === 404) throw new ServiceError(`${s.name} no longer has this release cached — search again`, 410);
		if (e instanceof ServiceError && e.status === 409) throw new ServiceError('The indexer refused the download', 409);
		throw e;
	}
	invalidate(key(s, 'history'));
	return { ok: true, title: r.title };
}

/* ---------------------------------------------------------------- */
/* Stats + history                                                   */
/* ---------------------------------------------------------------- */
export function stats(s: ServiceRecord, daysParam: string | null): Promise<StatsResponse> {
	const days = Number(daysParam ?? 30);
	if (!(STAT_RANGES as readonly number[]).includes(days)) throw new ServiceError('Invalid range', 400);
	return cached(key(s, `stats:${days}`), 60_000, async () => {
		const end = new Date();
		const start = new Date(end.getTime() - days * 864e5);
		const qs = new URLSearchParams({ startDate: start.toISOString(), endDate: end.toISOString() });
		const r = await call<{ indexers?: PStat[] }>(s, `/api/v1/indexerstats?${qs}`);
		const list = Array.isArray(r?.indexers) ? r.indexers : [];
		return {
			days,
			indexers: list.map((x) => ({
				indexerId: x.indexerId,
				name: String(x.indexerName ?? `Indexer ${x.indexerId}`),
				queries: num(x.numberOfQueries) ?? 0,
				grabs: num(x.numberOfGrabs) ?? 0,
				failedQueries: num(x.numberOfFailedQueries) ?? 0,
				failedGrabs: num(x.numberOfFailedGrabs) ?? 0,
				rssQueries: num(x.numberOfRssQueries) ?? 0,
				avgResponseMs: Math.round(num(x.averageResponseTime) ?? 0)
			}))
		};
	});
}

const KIND: Record<string, HistoryEntry['kind']> = {
	indexerQuery: 'query',
	indexerRss: 'rss',
	releaseGrabbed: 'grab',
	indexerAuth: 'auth'
};

export function history(s: ServiceRecord): Promise<HistoryResponse> {
	return cached(key(s, 'history'), 15_000, async () => {
		const qs = new URLSearchParams({ page: '1', pageSize: '100', sortKey: 'date', sortDirection: 'descending' });
		const [r, list] = await Promise.all([
			call<{ totalRecords?: number; records?: PHistory[] }>(s, `/api/v1/history?${qs}`),
			indexersRaw(s).catch(() => [] as PIndexer[])
		]);
		const names = new Map(list.map((i) => [i.id, i.name]));
		const records = (Array.isArray(r?.records) ? r.records : []).map((h): HistoryEntry => {
			const d = h.data ?? {};
			const kind = KIND[h.eventType ?? ''] ?? 'other';
			return {
				id: h.id,
				date: h.date,
				indexerId: h.indexerId,
				indexer: names.get(h.indexerId) ?? null,
				kind,
				successful: h.successful !== false,
				title: String((kind === 'grab' ? d.grabTitle : d.query) ?? ''),
				source: d.source ? String(d.source) : null,
				results: int(d.queryResults),
				elapsedMs: int(d.elapsedTime)
			};
		});
		return { records, total: num(r?.totalRecords) ?? records.length };
	});
}
