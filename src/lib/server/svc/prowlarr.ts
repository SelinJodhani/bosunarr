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
	await call(s, `/api/v1/indexer/${id}`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ ...current, enable }),
		signal: AbortSignal.timeout(30_000) // enabling makes Prowlarr test the indexer
	});
	invalidate(key(s, ''));
	return { ok: true };
}

/* ---------------------------------------------------------------- */
/* Search + grab                                                     */
/* ---------------------------------------------------------------- */
const RELEASE_TTL_MS = 25 * 60_000; // Prowlarr keeps releases grabbable for 30 minutes
const MAX_RELEASES = 5_000;
const MAX_RESULTS = 500;
/** token → what Prowlarr needs to grab it. Kept server-side so guids (often with passkeys) never leave. */
const releases = new Map<string, { serviceId: number; guid: string; indexerId: number; title: string; at: number }>();

function remember(serviceId: number, r: PRelease): string {
	const now = Date.now();
	if (releases.size >= MAX_RELEASES) {
		for (const [k, v] of releases) if (now - v.at > RELEASE_TTL_MS || releases.size >= MAX_RELEASES) releases.delete(k);
	}
	const token = randomBytes(12).toString('base64url');
	releases.set(token, { serviceId, guid: r.guid, indexerId: r.indexerId, title: r.title ?? '', at: now });
	return token;
}

const CATEGORY_IDS = new Set<number>(CATEGORIES.map((c) => c.id));

export async function search(s: ServiceRecord, params: URLSearchParams): Promise<SearchResponse> {
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
	const results = valid.slice(0, MAX_RESULTS).map(
		(r): SearchResult => ({
			token: remember(s.id, r),
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

export async function grab(s: ServiceRecord, token: unknown): Promise<{ ok: true; title: string }> {
	if (typeof token !== 'string' || !/^[\w-]{8,32}$/.test(token)) throw new ServiceError('Invalid release', 400);
	const r = releases.get(token);
	if (!r || r.serviceId !== s.id || Date.now() - r.at > RELEASE_TTL_MS) {
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
