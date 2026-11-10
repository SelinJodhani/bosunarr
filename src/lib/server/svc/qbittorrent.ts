import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import type { QbitConnection, QbitOverview, QbitPrefs, QbitTransfer } from '$lib/servicepages/qbittorrent/types';

/* ---------------------------------------------------------------- */
/* Incremental /sync/maindata                                        */
/* qBittorrent sends only what changed since `rid`, so polling stays  */
/* cheap even with thousands of torrents. We keep the merged server   */
/* state, categories and tags per service and ignore the torrents     */
/* part (the torrent list comes from Activity's shared poller).       */
/* ---------------------------------------------------------------- */
type ServerState = Record<string, unknown>;
type MainData = {
	rid: number;
	full_update?: boolean;
	server_state?: ServerState;
	categories?: Record<string, unknown>;
	categories_removed?: string[];
	tags?: string[];
	tags_removed?: string[];
};
type SyncState = { rid: number; server: ServerState; categories: Set<string>; tags: Set<string> };

const syncs = new Map<number, SyncState>();

async function sync(s: ServiceRecord): Promise<SyncState> {
	const prev = syncs.get(s.id);
	const md = await call<MainData | string>(s, `/api/v2/sync/maindata?rid=${prev?.rid ?? 0}`);
	if (!md || typeof md !== 'object' || typeof md.rid !== 'number') throw new ServiceError(`${s.name} sent an unexpected response`, 502);
	const full = md.full_update || !prev;
	const st: SyncState = full
		? { rid: md.rid, server: {}, categories: new Set(), tags: new Set() }
		: { rid: md.rid, server: { ...prev.server }, categories: new Set(prev.categories), tags: new Set(prev.tags) };
	Object.assign(st.server, md.server_state ?? {});
	for (const c of Object.keys(md.categories ?? {})) st.categories.add(c);
	for (const c of md.categories_removed ?? []) st.categories.delete(c);
	for (const t of md.tags ?? []) if (typeof t === 'string') st.tags.add(t);
	for (const t of md.tags_removed ?? []) st.tags.delete(t);
	syncs.set(s.id, st);
	return st;
}

const num = (v: unknown, fallback = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : fallback);
const CONNECTIONS: QbitConnection[] = ['connected', 'firewalled', 'disconnected'];

function transfer(ss: ServerState): QbitTransfer {
	const ratio = Number(ss.global_ratio);
	return {
		down: num(ss.dl_info_speed),
		up: num(ss.up_info_speed),
		sessionDown: num(ss.dl_info_data),
		sessionUp: num(ss.up_info_data),
		allTimeDown: num(ss.alltime_dl),
		allTimeUp: num(ss.alltime_ul),
		ratio: ss.global_ratio !== undefined && Number.isFinite(ratio) ? ratio : null,
		freeSpace: typeof ss.free_space_on_disk === 'number' ? ss.free_space_on_disk : null,
		dhtNodes: num(ss.dht_nodes),
		peers: num(ss.total_peer_connections),
		connection: CONNECTIONS.includes(ss.connection_status as QbitConnection) ? (ss.connection_status as QbitConnection) : 'unknown',
		downLimit: Math.max(0, num(ss.dl_rate_limit)),
		upLimit: Math.max(0, num(ss.up_rate_limit)),
		altSpeed: ss.use_alt_speed_limits === true,
		queueing: ss.queueing === true
	};
}

/* ---------------------------------------------------------------- */
/* Preferences — whitelisted fields only. The raw response includes  */
/* usernames and proxy/mail passwords; none of that leaves here.      */
/* ---------------------------------------------------------------- */
type RawPrefs = Record<string, unknown>;
const RATIO_ACTIONS: Record<number, string> = { 0: 'Stop torrent', 1: 'Remove torrent', 2: 'Enable super seeding', 3: 'Remove torrent and files' };
const str = (v: unknown) => (typeof v === 'string' ? v : '');

function prefsOf(p: RawPrefs): QbitPrefs {
	const ratio = num(p.max_ratio, -1);
	const seeding = num(p.max_seeding_time, -1);
	return {
		savePath: str(p.save_path),
		tempPath: p.temp_path_enabled === true && str(p.temp_path) ? str(p.temp_path) : null,
		queueing: p.queueing_enabled === true,
		maxActiveDownloads: num(p.max_active_downloads, -1),
		maxActiveUploads: num(p.max_active_uploads, -1),
		maxActiveTorrents: num(p.max_active_torrents, -1),
		maxRatio: p.max_ratio_enabled === true && ratio >= 0 ? ratio : null,
		maxSeedingTime: p.max_seeding_time_enabled === true && seeding >= 0 ? seeding : null,
		maxRatioAction: RATIO_ACTIONS[num(p.max_ratio_act, 0)] ?? 'Stop torrent',
		downLimit: Math.max(0, num(p.dl_limit)),
		upLimit: Math.max(0, num(p.up_limit)),
		altDownLimit: Math.max(0, num(p.alt_dl_limit)),
		altUpLimit: Math.max(0, num(p.alt_up_limit)),
		altScheduled: p.scheduler_enabled === true,
		dht: p.dht === true,
		listenPort: typeof p.listen_port === 'number' ? p.listen_port : null
	};
}

const text = async (s: ServiceRecord, path: string) => {
	const v = await call<unknown>(s, path);
	return typeof v === 'string' && v.trim().length < 40 ? v.trim() : null;
};

function versions(s: ServiceRecord) {
	return cached(`svc:qb:${s.id}:ver`, 10 * 60_000, async () => {
		const [version, webApiVersion] = await Promise.all([text(s, '/api/v2/app/version'), text(s, '/api/v2/app/webapiVersion').catch(() => null)]);
		return { version, webApiVersion };
	});
}

function prefs(s: ServiceRecord) {
	return cached(`svc:qb:${s.id}:prefs`, 30_000, async () => prefsOf(await call<RawPrefs>(s, '/api/v2/app/preferences')));
}

const sortStr = (a: Iterable<string>) => [...a].sort((x, y) => x.localeCompare(y));

/** Host paths are only for admins; everything else is the same for every signed-in user. */
export async function overview(s: ServiceRecord, isAdmin: boolean): Promise<QbitOverview> {
	const o = await overviewCached(s);
	if (isAdmin || !o.prefs) return o;
	return { ...o, prefs: { ...o.prefs, savePath: null, tempPath: null } };
}

function overviewCached(s: ServiceRecord): Promise<QbitOverview> {
	return cached(`svc:qb:${s.id}:main`, 1_500, async () => {
		const [st, ver, p] = await Promise.all([sync(s), versions(s).catch(() => ({ version: null, webApiVersion: null })), prefs(s).catch(() => null)]);
		return {
			at: Date.now(),
			version: ver.version,
			webApiVersion: ver.webApiVersion,
			transfer: transfer(st.server),
			categories: sortStr(st.categories),
			tags: sortStr(st.tags),
			prefs: p
		};
	});
}

const form = (fields: Record<string, string>): RequestInit => ({
	method: 'POST',
	headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
	body: new URLSearchParams(fields)
});

/**
 * Turn alternative speed limits on or off.
 * qBittorrent 5+ has `setSpeedLimitsMode`, which sets the mode directly (two clicks can't cancel out).
 * Older versions only have a toggle, so read the mode first and toggle only when it differs.
 */
export async function setAltSpeed(s: ServiceRecord, body: unknown): Promise<{ ok: true; altSpeed: boolean }> {
	const enabled = (body as { enabled?: unknown } | null)?.enabled;
	if (typeof enabled !== 'boolean') throw new ServiceError('enabled must be true or false', 400);
	try {
		await call(s, '/api/v2/transfer/setSpeedLimitsMode', form({ mode: enabled ? '1' : '0' }));
	} catch (e) {
		if (!(e instanceof ServiceError && e.status === 404)) throw e;
		const mode = String(await call<unknown>(s, '/api/v2/transfer/speedLimitsMode')).trim();
		if (mode !== '0' && mode !== '1') throw new ServiceError(`${s.name} sent an unexpected response`, 502);
		if ((mode === '1') !== enabled) await call(s, '/api/v2/transfer/toggleSpeedLimitsMode', form({}));
	}
	invalidate(`svc:qb:${s.id}:`);
	return { ok: true, altSpeed: enabled };
}
