import { cached, call, invalidate, ServiceError } from './services/client';
import { getService, servicesOfType, type ServiceRecord } from './services/store';
import { arrImage } from './library';
import type {
	ActionRequest,
	ActivitySnapshot,
	AddTorrentRequest,
	ClientSummary,
	QueueEntry,
	Torrent,
	TorrentGroup
} from '$lib/activity/types';

const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

/* ---------------------------------------------------------------- */
/* Arr queues                                                        */
/* ---------------------------------------------------------------- */
type ArrImage = { coverType: string; url?: string };
type ArrQueueRecord = {
	id: number;
	title: string;
	size: number;
	sizeleft: number;
	status: string;
	timeleft?: string;
	trackedDownloadState?: string;
	trackedDownloadStatus?: string;
	statusMessages?: Array<{ title?: string; messages?: string[] }>;
	errorMessage?: string;
	protocol?: string;
	downloadClient?: string;
	downloadId?: string;
	quality?: { quality?: { name?: string } };
	seriesId?: number;
	movieId?: number;
	series?: { title: string; images?: ArrImage[] };
	episode?: { seasonNumber: number; episodeNumber: number; title: string };
	movie?: { title: string; year: number; images?: ArrImage[] };
};

const pad = (n: number) => String(n).padStart(2, '0');

async function arrQueue(s: ServiceRecord): Promise<QueueEntry[]> {
	const include = s.type === 'sonarr' ? 'includeSeries=true&includeEpisode=true' : 'includeMovie=true';
	const r = await call<{ records: ArrQueueRecord[] }>(s, `/api/v3/queue?pageSize=500&${include}`);
	return r.records.map((q): QueueEntry => {
		const messages = [
			...(q.errorMessage ? [q.errorMessage] : []),
			...(q.statusMessages ?? []).flatMap((m) => (m.messages?.length ? m.messages : m.title ? [m.title] : []))
		];
		const health = q.trackedDownloadStatus === 'error' ? 'error' : q.trackedDownloadStatus === 'warning' ? 'warning' : 'ok';
		const episode = s.type === 'sonarr';
		return {
			id: `${s.id}:${q.id}`,
			serviceId: s.id,
			queueId: q.id,
			service: s.name,
			kind: episode ? 'episode' : 'movie',
			title: q.series?.title ?? (q.movie ? `${q.movie.title} (${q.movie.year})` : q.title),
			subtitle: q.episode ? `S${pad(q.episode.seasonNumber)}E${pad(q.episode.episodeNumber)} · ${q.episode.title}` : q.title,
			progress: q.size ? Math.max(0, Math.min(1, 1 - q.sizeleft / q.size)) : 0,
			size: q.size ?? 0,
			sizeleft: q.sizeleft ?? 0,
			state: q.trackedDownloadState ?? q.status ?? 'unknown',
			health,
			messages: [...new Set(messages)],
			timeleft: q.timeleft ?? null,
			protocol: q.protocol ?? null,
			client: q.downloadClient ?? null,
			quality: q.quality?.quality?.name ?? null,
			poster: arrImage(s, q.series?.images ?? q.movie?.images, 'poster', 250),
			href: episode ? (q.seriesId ? `/library/${s.id}/series/${q.seriesId}` : null) : q.movieId ? `/library/${s.id}/movie/${q.movieId}` : null,
			downloadId: q.downloadId?.toLowerCase() ?? null
		};
	});
}

/* ---------------------------------------------------------------- */
/* qBittorrent                                                       */
/* ---------------------------------------------------------------- */
type QbitTorrent = {
	hash: string;
	name: string;
	progress: number;
	dlspeed: number;
	upspeed: number;
	eta: number;
	state: string;
	size: number;
	total_size?: number;
	downloaded: number;
	uploaded: number;
	ratio: number;
	category: string;
	tags: string;
	added_on: number;
	completion_on: number;
	num_seeds: number;
	num_leechs: number;
	save_path: string;
};

const GROUPS: Record<string, TorrentGroup> = {
	error: 'errored',
	missingFiles: 'errored',
	uploading: 'seeding',
	forcedUP: 'seeding',
	stalledUP: 'seeding',
	pausedUP: 'completed',
	stoppedUP: 'completed',
	pausedDL: 'paused',
	stoppedDL: 'paused',
	queuedDL: 'queued',
	queuedUP: 'queued',
	checkingDL: 'checking',
	checkingUP: 'checking',
	checkingResumeData: 'checking',
	moving: 'checking',
	allocating: 'checking',
	stalledDL: 'stalled',
	metaDL: 'stalled',
	forcedMetaDL: 'stalled',
	downloading: 'downloading',
	forcedDL: 'downloading'
};

async function qbit(s: ServiceRecord): Promise<{ torrents: Torrent[]; summary: ClientSummary }> {
	const [info, list] = await Promise.all([
		call<{ dl_info_speed: number; up_info_speed: number }>(s, '/api/v2/transfer/info'),
		call<QbitTorrent[]>(s, '/api/v2/torrents/info')
	]);
	const torrents = list.map(
		(t): Torrent => ({
			id: `${s.id}:${t.hash}`,
			serviceId: s.id,
			hash: t.hash,
			name: t.name,
			progress: t.progress,
			dlspeed: t.dlspeed,
			upspeed: t.upspeed,
			eta: t.eta,
			state: t.state,
			group: GROUPS[t.state] ?? (t.progress >= 1 ? 'completed' : 'downloading'),
			size: t.size || t.total_size || 0,
			downloaded: t.downloaded,
			uploaded: t.uploaded,
			ratio: t.ratio,
			category: t.category ?? '',
			tags: t.tags ? t.tags.split(',').map((x) => x.trim()).filter(Boolean) : [],
			addedOn: t.added_on,
			completedOn: t.completion_on > 0 ? t.completion_on : 0,
			seeds: t.num_seeds,
			peers: t.num_leechs,
			savePath: t.save_path
		})
	);
	return {
		torrents,
		summary: { id: s.id, name: s.name, down: info.dl_info_speed, up: info.up_info_speed, count: torrents.length, error: null }
	};
}

/* ---------------------------------------------------------------- */
/* Snapshot (shared by SSE subscribers and the plain GET)            */
/* ---------------------------------------------------------------- */
const QUEUE_TTL = 5_000;
const TORRENT_TTL = 1_500;

function queueAll() {
	return cached('act:queue', QUEUE_TTL, async () => {
		const services = servicesOfType('sonarr', 'radarr');
		const results = await Promise.allSettled(services.map(arrQueue));
		const errors: ActivitySnapshot['errors'] = [];
		const queue = results.flatMap((r, i) => {
			if (r.status === 'fulfilled') return r.value;
			errors.push({ service: services[i].name, error: errMsg(r.reason) });
			return [];
		});
		return { queue, errors };
	});
}

function torrentsAll() {
	return cached('act:torrents', TORRENT_TTL, async () => {
		const services = servicesOfType('qbittorrent');
		const results = await Promise.allSettled(services.map(qbit));
		const errors: ActivitySnapshot['errors'] = [];
		const torrents: Torrent[] = [];
		const clients: ClientSummary[] = [];
		results.forEach((r, i) => {
			if (r.status === 'fulfilled') {
				torrents.push(...r.value.torrents);
				clients.push(r.value.summary);
			} else {
				const error = errMsg(r.reason);
				errors.push({ service: services[i].name, error });
				clients.push({ id: services[i].id, name: services[i].name, down: 0, up: 0, count: 0, error });
			}
		});
		return { torrents, clients, errors };
	});
}

export async function activity(): Promise<ActivitySnapshot> {
	const [q, t] = await Promise.all([queueAll(), torrentsAll()]);
	return {
		at: Date.now(),
		queue: q.queue,
		torrents: t.torrents,
		clients: t.clients,
		speeds: t.clients.reduce((a, c) => ({ down: a.down + c.down, up: a.up + c.up }), { down: 0, up: 0 }),
		errors: [...q.errors, ...t.errors]
	};
}

/* ---------------------------------------------------------------- */
/* Live hub: one poller shared by every open Activity page           */
/* ---------------------------------------------------------------- */
type Listener = (json: string) => void;
const listeners = new Set<Listener>();
let timer: ReturnType<typeof setTimeout> | undefined;
let last = '';

async function tick() {
	try {
		const snap = await activity();
		const { at: _at, ...rest } = snap;
		const key = JSON.stringify(rest);
		if (key !== last) {
			last = key;
			const json = JSON.stringify(snap);
			for (const l of listeners) l(json);
		}
	} catch {
		/* individual service errors are inside the snapshot; anything else retries next tick */
	}
	if (listeners.size) timer = setTimeout(tick, 2_000);
	else timer = undefined;
}

/** Subscribe to snapshot changes. The newest snapshot is delivered immediately. */
export function subscribe(listener: Listener): () => void {
	listeners.add(listener);
	if (last) activity().then((s) => listeners.has(listener) && listener(JSON.stringify(s)), () => {});
	if (!timer) timer = setTimeout(tick, 0);
	return () => {
		listeners.delete(listener);
	};
}

/** Force the next tick to fetch fresh data and push it even if the poller is mid-sleep. */
function refreshSoon() {
	invalidate('act:');
	invalidate('w:');
	last = '';
	if (listeners.size) {
		clearTimeout(timer);
		timer = setTimeout(tick, 300);
	}
}

/* ---------------------------------------------------------------- */
/* Actions                                                           */
/* ---------------------------------------------------------------- */
const form = (fields: Record<string, string>): RequestInit => ({
	method: 'POST',
	headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
	body: new URLSearchParams(fields)
});

/** qBittorrent 5 renamed pause/resume to stop/start; try the new name, fall back to the old one. */
async function qbitPost(s: ServiceRecord, names: [string, string?], fields: Record<string, string>) {
	try {
		await call(s, `/api/v2/torrents/${names[0]}`, form(fields));
	} catch (e) {
		if (names[1] && e instanceof ServiceError && e.status === 404) await call(s, `/api/v2/torrents/${names[1]}`, form(fields));
		else throw e;
	}
}

function groupIds(ids: string[], ...types: ServiceRecord['type'][]): Map<ServiceRecord, string[]> {
	const out = new Map<number, { s: ServiceRecord; keys: string[] }>();
	for (const raw of ids) {
		const m = /^(\d+):([\w]+)$/.exec(raw);
		if (!m) throw new ServiceError('Invalid id', 400);
		const s = getService(Number(m[1]));
		if (!s || !types.includes(s.type)) throw new ServiceError('Unknown service', 400);
		const entry = out.get(s.id) ?? { s, keys: [] };
		entry.keys.push(m[2]);
		out.set(s.id, entry);
	}
	return new Map([...out.values()].map((v) => [v.s, v.keys]));
}

export async function runAction(req: ActionRequest): Promise<{ ok: true }> {
	if (!Array.isArray(req.ids) || req.ids.length === 0 || req.ids.length > 1000) throw new ServiceError('Nothing selected', 400);

	if (req.action === 'removeQueue') {
		for (const [s, keys] of groupIds(req.ids, 'sonarr', 'radarr')) await removeQueue(s, keys, req);
		refreshSoon();
		return { ok: true };
	}

	const all = req.action === 'pauseAll' || req.action === 'resumeAll';
	const groups = groupIds(all ? req.ids.map((i) => `${i.split(':')[0]}:all`) : req.ids, 'qbittorrent');
	for (const [s, keys] of groups) {
		if (!all && keys.some((k) => !/^[a-f0-9]{40}$|^[a-f0-9]{64}$/i.test(k))) throw new ServiceError('Invalid torrent hash', 400);
		const hashes = all ? 'all' : keys.join('|');
		switch (req.action) {
			case 'pause':
			case 'pauseAll':
				await qbitPost(s, ['stop', 'pause'], { hashes });
				break;
			case 'resume':
			case 'resumeAll':
				await qbitPost(s, ['start', 'resume'], { hashes });
				break;
			case 'recheck':
				await qbitPost(s, ['recheck'], { hashes });
				break;
			case 'reannounce':
				await qbitPost(s, ['reannounce'], { hashes });
				break;
			case 'delete':
				await qbitPost(s, ['delete'], { hashes, deleteFiles: String(Boolean(req.deleteFiles)) });
				break;
			default:
				throw new ServiceError('Unknown action', 400);
		}
	}
	refreshSoon();
	return { ok: true };
}

async function removeQueue(s: ServiceRecord, keys: string[], req: { removeFromClient: boolean; blocklist: boolean }) {
	const ids = keys.map(Number);
	if (ids.some((n) => !Number.isInteger(n) || n <= 0)) throw new ServiceError('Invalid queue id', 400);
	const qs = `removeFromClient=${Boolean(req.removeFromClient)}&blocklist=${Boolean(req.blocklist)}`;
	await call(s, `/api/v3/queue/bulk?${qs}`, {
		method: 'DELETE',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ ids })
	});
}

export async function addTorrent(req: AddTorrentRequest): Promise<{ ok: true; count: number }> {
	const s = getService(Number(req.serviceId));
	if (!s || s.type !== 'qbittorrent') throw new ServiceError('Choose a qBittorrent service', 400);
	const urls = String(req.urls ?? '')
		.split(/\s*\n\s*/)
		.map((u) => u.trim())
		.filter(Boolean);
	if (!urls.length) throw new ServiceError('Paste a magnet link or a .torrent URL', 400);
	if (urls.length > 50) throw new ServiceError('Add at most 50 at a time', 400);
	for (const u of urls) {
		if (!/^magnet:\?xt=urn:bt(ih|mh):/i.test(u) && !/^https?:\/\/\S+$/i.test(u)) throw new ServiceError(`Not a magnet link or http(s) URL: ${u.slice(0, 60)}`, 400);
	}
	const fields: Record<string, string> = { urls: urls.join('\n') };
	if (req.category) fields.category = String(req.category).slice(0, 100);
	if (req.paused) {
		fields.paused = 'true'; // qBittorrent 4.x
		fields.stopped = 'true'; // qBittorrent 5.x
	}
	const res = await call<string>(s, '/api/v2/torrents/add', form(fields));
	if (typeof res === 'string' && /fail/i.test(res)) throw new ServiceError('qBittorrent rejected the torrent', 400);
	refreshSoon();
	return { ok: true, count: urls.length };
}

/** Categories for the add dialog. */
export function categories(serviceId: number): Promise<string[]> {
	const s = getService(serviceId);
	if (!s || s.type !== 'qbittorrent') return Promise.reject(new ServiceError('Not found', 404));
	return cached(`act:cats:${s.id}`, 60_000, async () => Object.keys(await call<Record<string, unknown>>(s, '/api/v2/torrents/categories')).sort());
}
