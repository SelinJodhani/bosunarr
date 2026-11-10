/**
 * Sonarr v4 / Radarr v6 service screen: system status, health, disks, wanted lists, history,
 * indexer / download-client status, profiles, tags and a few whole-library commands.
 */
import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import { arrImage } from '../library';
import {
	ARR_COMMANDS,
	type ArrCommand,
	type ArrCommandInfo,
	type ArrDisk,
	type ArrDownloadClient,
	type ArrHealth,
	type ArrIndexer,
	type ArrKind,
	type ArrOverview,
	type ArrProfile,
	type ArrSystem,
	type ArrTag,
	type HistoryFilter,
	type HistoryItem,
	type Paged,
	type WantedItem,
	type WantedKind
} from '$lib/servicepages/arr/types';

/* ---------------------------------------------------------------- */
/* Arr API shapes (only the fields we use)                           */
/* ---------------------------------------------------------------- */
type ArrImage = { coverType: string; url?: string };
type Status = {
	appName?: string;
	version: string;
	branch?: string;
	startTime?: string;
	osName?: string;
	osVersion?: string;
	runtimeName?: string;
	runtimeVersion?: string;
	isDocker?: boolean;
	databaseType?: string;
	databaseVersion?: string;
	packageUpdateMechanism?: string;
};
type Health = { source?: string; type?: string; message?: string; wikiUrl?: string | { fullUri?: string } };
type DiskSpace = { path: string; freeSpace: number; totalSpace: number };
type RootFolder = { id: number; path: string; accessible?: boolean; freeSpace?: number; unmappedFolders?: unknown[] };
type QueueStatus = { totalCount?: number; count?: number; errors?: boolean; warnings?: boolean; unknownErrors?: boolean; unknownWarnings?: boolean };
type Update = { version: string; releaseDate?: string; installed?: boolean; latest?: boolean; installable?: boolean };
type Command = {
	id: number;
	name?: string;
	commandName?: string;
	status?: string;
	started?: string;
	ended?: string;
	queued?: string;
	trigger?: string;
};
type Indexer = {
	id: number;
	name: string;
	implementationName?: string;
	protocol?: string;
	enableRss?: boolean;
	enableAutomaticSearch?: boolean;
	enableInteractiveSearch?: boolean;
	priority?: number;
};
type ProviderStatus = { indexerId?: number; downloadClientId?: number; disabledTill?: string; mostRecentFailure?: string };
type DownloadClient = { id: number; name: string; implementationName?: string; protocol?: string; enable?: boolean; priority?: number };
type ProfileItem = { quality?: { id: number; name: string }; name?: string; id?: number; allowed?: boolean; items?: ProfileItem[] };
type Profile = { id: number; name: string; cutoff?: number; upgradeAllowed?: boolean; items?: ProfileItem[] };
type TagDetail = { id: number; label: string } & Record<string, unknown>;
type PageOf<T> = { page?: number; pageSize?: number; totalRecords?: number; records?: T[] };
type Series = { id: number; title: string; year?: number; images?: ArrImage[] };
type Movie = { id: number; title: string; year?: number; images?: ArrImage[]; status?: string; inCinemas?: string; digitalRelease?: string; physicalRelease?: string; movieFile?: { quality?: Quality } };
type Quality = { quality?: { name?: string } };
type Episode = {
	id: number;
	seriesId: number;
	seasonNumber: number;
	episodeNumber: number;
	title?: string;
	airDateUtc?: string;
	series?: Series;
	episodeFile?: { quality?: Quality };
};
type History = {
	id: number;
	eventType: string;
	date: string;
	sourceTitle?: string;
	quality?: Quality;
	data?: Record<string, string | undefined>;
	seriesId?: number;
	movieId?: number;
	series?: Series;
	episode?: { seasonNumber: number; episodeNumber: number; title?: string };
	movie?: Movie;
};

const kindOf = (s: ServiceRecord): ArrKind => (s.type === 'sonarr' ? 'series' : 'movie');
const pad = (n: number) => String(n).padStart(2, '0');
const PAGE_SIZE = 20;

/** Only absolute http(s) links from the arr are rendered as links. */
function safeUrl(u: string | undefined | null): string | null {
	if (!u) return null;
	try {
		const url = new URL(u);
		return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
	} catch {
		return null;
	}
}

function future(iso: string | undefined): string | null {
	return iso && +new Date(iso) > Date.now() ? iso : null;
}

/* ---------------------------------------------------------------- */
/* Overview                                                          */
/* ---------------------------------------------------------------- */

function system(st: Status, updates: Update[] | null): ArrSystem {
	const latest = updates?.find((u) => u.latest);
	const installed = updates?.find((u) => u.installed);
	const available = latest && !latest.installed && (!installed || latest.version !== installed.version) ? latest : null;
	return {
		app: st.appName ?? '',
		version: st.version,
		branch: st.branch ?? null,
		startTime: st.startTime ?? null,
		os: st.osName ? `${st.osName.replace(/^./, (c) => c.toUpperCase())}${st.osVersion ? ' ' + st.osVersion : ''}` : null,
		runtime: st.runtimeVersion ? `${st.runtimeName ?? '.NET'} ${st.runtimeVersion}` : null,
		docker: Boolean(st.isDocker),
		database: st.databaseType ? `${st.databaseType.toLowerCase() === 'sqlite' ? 'SQLite' : st.databaseType.toLowerCase() === 'postgresql' ? 'PostgreSQL' : st.databaseType}${st.databaseVersion ? ' ' + st.databaseVersion : ''}` : null,
		updateMechanism: st.packageUpdateMechanism ?? null,
		update: available ? { version: available.version, releaseDate: available.releaseDate ?? null } : null
	};
}

function health(rows: Health[]): ArrHealth[] {
	const order = { error: 0, warning: 1, notice: 2, ok: 3 } as const;
	return rows
		.map((h): ArrHealth => {
			const t = String(h.type ?? '').toLowerCase();
			const type = t === 'error' || t === 'warning' || t === 'notice' ? t : 'ok';
			const wiki = typeof h.wikiUrl === 'string' ? h.wikiUrl : h.wikiUrl?.fullUri;
			return { source: h.source ?? '', type, message: h.message ?? '', wikiUrl: safeUrl(wiki) };
		})
		.filter((h) => h.message)
		.sort((a, b) => order[a.type] - order[b.type]);
}

/** Root folders first (matched to the drive they live on for the total), then drives that hold no root folder. */
function disks(space: DiskSpace[], roots: RootFolder[]): ArrDisk[] {
	// Windows arrs report paths like "D:\\Media\\TV": compare with forward slashes, case-insensitively.
	const norm = (p: string) => p.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
	const driveOf = (p: string) => {
		const np = norm(p);
		return space
			.filter((d) => {
				const nd = norm(d.path);
				return np === nd || np.startsWith(nd + '/') || nd === '';
			})
			.sort((a, b) => norm(b.path).length - norm(a.path).length)[0];
	};
	const used = new Set<string>();
	const out: ArrDisk[] = roots.map((r) => {
		const d = driveOf(r.path);
		if (d) used.add(d.path);
		const accessible = r.accessible !== false;
		return {
			path: r.path,
			free: accessible ? (r.freeSpace ?? d?.freeSpace ?? 0) : 0,
			total: accessible ? (d?.totalSpace ?? null) : null,
			rootFolder: true,
			accessible,
			unmapped: r.unmappedFolders?.length ?? 0
		};
	});
	// Drives: skip ones already shown through a root folder and duplicates of the same filesystem.
	const seen = new Set<string>();
	for (const d of space) {
		const sig = `${d.freeSpace}:${d.totalSpace}`;
		if (used.has(d.path) || seen.has(sig) || !d.totalSpace) continue;
		seen.add(sig);
		out.push({ path: d.path, free: d.freeSpace, total: d.totalSpace, rootFolder: false, accessible: true, unmapped: 0 });
	}
	return out;
}

function profileQualities(items: ProfileItem[] = []): string[] {
	return items.filter((i) => i.allowed).map((i) => i.quality?.name ?? i.name ?? '').filter(Boolean).reverse();
}

function cutoffName(p: Profile): string | null {
	for (const i of p.items ?? []) {
		if (i.quality && i.quality.id === p.cutoff) return i.quality.name;
		if (!i.quality && i.id === p.cutoff) return i.name ?? null;
	}
	return null;
}

const COMMAND_LABELS: Record<string, string> = {
	RssSync: 'RSS sync',
	MissingEpisodeSearch: 'Search missing episodes',
	MissingMoviesSearch: 'Search missing movies',
	CutoffUnmetEpisodeSearch: 'Search cutoff unmet',
	CutoffUnmetMoviesSearch: 'Search cutoff unmet',
	RefreshSeries: 'Refresh & scan series',
	RefreshMovie: 'Refresh & scan movies',
	RefreshMonitoredDownloads: 'Refresh monitored downloads',
	ApplicationUpdateCheck: 'Check for updates',
	CheckHealth: 'Health check',
	Housekeeping: 'Housekeeping',
	Backup: 'Backup',
	DownloadedEpisodesScan: 'Scan downloaded episodes',
	DownloadedMoviesScan: 'Scan downloaded movies',
	ImportListSync: 'Import list sync',
	MessagingCleanup: 'Clean up messages'
};
/** Commands the arr runs on its own every few minutes; hidden when the arr doesn't report a trigger. */
const SCHEDULED = new Set(['RefreshMonitoredDownloads', 'MessagingCleanup', 'Housekeeping', 'CheckHealth', 'ApplicationUpdateCheck', 'ImportListSync']);
const isManual = (c: Command) => (c.trigger ? c.trigger === 'manual' : !SCHEDULED.has(c.name ?? ''));
const commandTime = (c: Command) => +new Date(c.queued ?? c.started ?? 0) || 0;

/** Resolves to null if `p` takes longer than `ms` (the promise keeps running and fills its cache). */
const within = <T>(p: Promise<T>, ms: number): Promise<T | null> =>
	Promise.race([p, new Promise<null>((res) => setTimeout(() => res(null), ms))]);

const commandLabel = (c: Command) =>
	COMMAND_LABELS[c.name ?? ''] ?? c.commandName ?? (c.name ?? 'Task').replace(/([a-z])([A-Z])/g, '$1 $2');

export function overview(s: ServiceRecord): Promise<ArrOverview> {
	return cached(`svc-arr:${s.id}:overview`, 15_000, async () => {
		const get = <T>(path: string) => call<T>(s, path);
		// Slow-changing or expensive lookups get their own longer cache: /update asks the arr's update server and
		// /rootfolder scans disk, so they must not run on every 15-30 s poll.
		const slow = <T>(key: string, path: string, ttlMs: number) => cached(`svc-arr:${s.id}:${key}`, ttlMs, () => get<T>(path));
		const kind = kindOf(s);
		const wantedCount = (which: WantedKind) =>
			get<PageOf<unknown>>(`/api/v3/wanted/${which}?page=1&pageSize=1&monitored=true`).then((r) => Number(r?.totalRecords));
		const [st, hl, space, roots, queue, updates, cmds, idx, idxSt, dc, dcSt, profiles, tags, missing, cutoff] = await Promise.allSettled([
			get<Status>('/api/v3/system/status'),
			get<Health[]>('/api/v3/health'),
			get<DiskSpace[]>('/api/v3/diskspace'),
			slow<RootFolder[]>('rootfolder', '/api/v3/rootfolder', 5 * 60_000),
			get<QueueStatus>('/api/v3/queue/status'),
			within(slow<Update[]>('update', '/api/v3/update', 10 * 60_000), 3000),
			get<Command[]>('/api/v3/command'),
			get<Indexer[]>('/api/v3/indexer'),
			get<ProviderStatus[]>('/api/v3/indexerstatus'),
			get<DownloadClient[]>('/api/v3/downloadclient'),
			get<ProviderStatus[]>('/api/v3/downloadclientstatus'),
			slow<Profile[]>('qualityprofile', '/api/v3/qualityprofile', 10 * 60_000),
			slow<TagDetail[]>('tags', '/api/v3/tag/detail', 10 * 60_000),
			wantedCount('missing'),
			wantedCount('cutoff')
		]);
		// The page is useless without system status: surface its error (e.g. bad key) as the request error.
		if (st.status === 'rejected') throw st.reason;
		if (!st.value || typeof st.value !== 'object' || typeof st.value.version !== 'string') {
			throw new ServiceError(`${s.name} sent an unexpected response — is this really ${s.type === 'sonarr' ? 'Sonarr' : 'Radarr'}?`, 502);
		}
		const partial: string[] = [];
		// call() returns text for non-JSON bodies (proxy error pages): never treat that as data.
		const arr = <T>(r: PromiseSettledResult<T[]>, name: string | null): T[] => {
			if (r.status === 'fulfilled' && Array.isArray(r.value)) return r.value;
			if (name) partial.push(name);
			return [];
		};
		const obj = <T extends object>(r: PromiseSettledResult<T>, name: string): T | null => {
			if (r.status === 'fulfilled' && r.value && typeof r.value === 'object' && !Array.isArray(r.value)) return r.value;
			partial.push(name);
			return null;
		};
		const num = (r: PromiseSettledResult<number>) => (r.status === 'fulfilled' && Number.isFinite(r.value) ? r.value : null);

		const idxStatus = new Map(arr(idxSt, null).map((x) => [x.indexerId, x]));
		const dcStatus = new Map(arr(dcSt, null).map((x) => [x.downloadClientId, x]));
		const q = obj(queue, 'queue');

		return {
			kind,
			system: system(st.value, updates.status === 'fulfilled' && Array.isArray(updates.value) ? updates.value : null),
			health: health(arr(hl, 'health')),
			disks: disks(arr(space, 'disks'), arr(roots, 'root folders')),
			queue: q ? { count: q.totalCount ?? q.count ?? 0, errors: Boolean(q.errors || q.unknownErrors), warnings: Boolean(q.warnings || q.unknownWarnings) } : null,
			missing: num(missing),
			cutoff: num(cutoff),
			commands: arr(cmds, null)
				.filter(isManual)
				.filter((c) => c.status === 'started' || c.status === 'queued' || (c.ended && Date.now() - +new Date(c.ended) < 10 * 60_000))
				.sort((a, b) => commandTime(b) - commandTime(a))
				.slice(0, 8)
				.map((c): ArrCommandInfo => ({
					id: c.id,
					name: commandLabel(c),
					command: commandKey(c.name),
					status: c.status ?? 'unknown',
					started: c.started ?? c.queued ?? null,
					ended: c.ended ?? null
				})),
			indexers: arr(idx, 'indexers')
				.map((i): ArrIndexer => {
					const stx = idxStatus.get(i.id);
					return {
						id: i.id,
						name: i.name,
						implementation: i.implementationName ?? '',
						protocol: i.protocol ?? '',
						rss: Boolean(i.enableRss),
						autoSearch: Boolean(i.enableAutomaticSearch),
						interactiveSearch: Boolean(i.enableInteractiveSearch),
						priority: i.priority ?? 25,
						disabledTill: future(stx?.disabledTill),
						lastFailure: stx?.mostRecentFailure ?? null
					};
				})
				.sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name)),
			downloadClients: arr(dc, 'download clients')
				.map((d): ArrDownloadClient => ({
					id: d.id,
					name: d.name,
					implementation: d.implementationName ?? '',
					protocol: d.protocol ?? '',
					enabled: Boolean(d.enable),
					priority: d.priority ?? 1,
					disabledTill: future(dcStatus.get(d.id)?.disabledTill)
				}))
				.sort((a, b) => Number(b.enabled) - Number(a.enabled) || a.priority - b.priority || a.name.localeCompare(b.name)),
			profiles: arr(profiles, 'quality profiles').map(
				(p): ArrProfile => ({ id: p.id, name: p.name, cutoff: cutoffName(p), qualities: profileQualities(p.items), upgradeAllowed: Boolean(p.upgradeAllowed) })
			),
			tags: arr(tags, 'tags')
				.map((t): ArrTag => ({
					id: t.id,
					label: t.label,
					uses: Object.entries(t).reduce((n, [k, v]) => n + (k.endsWith('Ids') && Array.isArray(v) ? v.length : 0), 0)
				}))
				.sort((a, b) => a.label.localeCompare(b.label)),
			partial
		};
	});
}

/* ---------------------------------------------------------------- */
/* Wanted                                                            */
/* ---------------------------------------------------------------- */

export function parseWantedKind(v: string | null): WantedKind {
	if (v === null || v === 'missing') return 'missing';
	if (v === 'cutoff') return 'cutoff';
	throw new ServiceError('kind must be "missing" or "cutoff"', 400);
}

export function parsePage(v: string | null): number {
	if (v === null || v === '') return 1;
	const n = Number(v);
	if (!Number.isInteger(n) || n < 1 || n > 10_000) throw new ServiceError('page must be a positive whole number', 400);
	return n;
}

function paged<T, U>(r: PageOf<T>, page: number, map: (x: T) => U): Paged<U> {
	if (!r || typeof r !== 'object' || !Array.isArray(r.records)) throw new ServiceError('Unexpected response from the service', 502);
	const total = r.totalRecords ?? 0;
	return { page, pages: Math.max(1, Math.ceil(total / PAGE_SIZE)), total, records: (r.records ?? []).map(map) };
}

const isAhead = (iso: string) => +new Date(iso) > Date.now();

function movieDate(m: Movie): Pick<WantedItem, 'date' | 'dateLabel'> {
	const release = m.digitalRelease ?? m.physicalRelease;
	if (release) return { date: release, dateLabel: isAhead(release) ? 'Releases' : 'Released' };
	if (m.inCinemas) return { date: m.inCinemas, dateLabel: 'In cinemas' };
	return { date: null, dateLabel: null };
}

export function wanted(s: ServiceRecord, which: WantedKind, page: number): Promise<Paged<WantedItem>> {
	return cached(`svc-arr:${s.id}:wanted:${which}:${page}`, 30_000, async () => {
		const base = `/api/v3/wanted/${which}?page=${page}&pageSize=${PAGE_SIZE}&monitored=true`;
		if (s.type === 'sonarr') {
			const r = await call<PageOf<Episode>>(
				s,
				`${base}&sortKey=episodes.airDateUtc&sortDirection=descending&includeSeries=true&includeEpisodeFile=true`
			);
			return paged(r, page, (e): WantedItem => ({
				id: e.id,
				title: e.series?.title ?? 'Unknown series',
				subtitle: `S${pad(e.seasonNumber)}E${pad(e.episodeNumber)}${e.title ? ` · ${e.title}` : ''}`,
				date: e.airDateUtc ?? null,
				dateLabel: e.airDateUtc ? (isAhead(e.airDateUtc) ? 'Airs' : 'Aired') : null,
				quality: e.episodeFile?.quality?.quality?.name ?? null,
				poster: arrImage(s, e.series?.images, 'poster', 250),
				href: `/library/${s.id}/series/${e.seriesId}`
			}));
		}
		const r = await call<PageOf<Movie>>(s, `${base}&sortKey=movieMetadata.sortTitle&sortDirection=ascending`);
		return paged(r, page, (m): WantedItem => ({
			id: m.id,
			title: m.title,
			subtitle: m.year ? String(m.year) : null,
			...movieDate(m),
			quality: m.movieFile?.quality?.quality?.name ?? null,
			poster: arrImage(s, m.images, 'poster', 250),
			href: `/library/${s.id}/movie/${m.id}`
		}));
	});
}

/* ---------------------------------------------------------------- */
/* History                                                           */
/* ---------------------------------------------------------------- */

export function parseHistoryFilter(v: string | null): HistoryFilter {
	if (v === null || v === '' || v === 'all') return 'all';
	if (v === 'grabbed' || v === 'imported' || v === 'failed') return v;
	throw new ServiceError('event must be all, grabbed, imported or failed', 400);
}

/** Arr history event type ids (identical in Sonarr v4 and Radarr v6). */
/**
 * Arr history event type ids. Grabbed/DownloadFolderImported/DownloadFailed are shared; folder imports differ
 * (Sonarr EpisodeHistoryEventType.SeriesFolderImported = 2, Radarr MovieHistoryEventType.MovieFolderImported = 7).
 */
function eventIds(s: ServiceRecord, filter: Exclude<HistoryFilter, 'all'>): number[] {
	if (filter === 'grabbed') return [1];
	if (filter === 'failed') return [4];
	return [3, s.type === 'sonarr' ? 2 : 7];
}

const EVENTS: Record<string, { label: string; tone: HistoryItem['tone'] }> = {
	grabbed: { label: 'Grabbed', tone: 'grab' },
	downloadFolderImported: { label: 'Imported', tone: 'ok' },
	seriesFolderImported: { label: 'Imported from folder', tone: 'ok' },
	movieFolderImported: { label: 'Imported from folder', tone: 'ok' },
	downloadFailed: { label: 'Download failed', tone: 'error' },
	episodeFileDeleted: { label: 'File deleted', tone: 'other' },
	movieFileDeleted: { label: 'File deleted', tone: 'other' },
	episodeFileRenamed: { label: 'File renamed', tone: 'other' },
	movieFileRenamed: { label: 'File renamed', tone: 'other' },
	downloadIgnored: { label: 'Ignored', tone: 'other' }
};

const DELETE_REASONS: Record<string, string> = {
	Manual: 'Deleted manually',
	MissingFromDisk: 'Missing from disk',
	Upgrade: 'Replaced by an upgrade'
};

function historyDetail(h: History): string | null {
	const d = h.data ?? {};
	// downloadClientName is the user's name for the client; downloadClient is often just its type.
	const client = d.downloadClientName || d.downloadClient;
	if (h.eventType === 'downloadFailed' || h.eventType === 'downloadIgnored') {
		// The event label already says "Download failed": drop that prefix from the arr's message.
		const m = (d.message || '').replace(/^\s*download (failed|ignored)\s*[:.-]\s*/i, '').trim();
		return m ? m[0].toUpperCase() + m.slice(1) : null;
	}
	if (h.eventType.endsWith('FileDeleted')) return (d.reason && DELETE_REASONS[d.reason]) || d.reason || null;
	if (h.eventType === 'grabbed') return [d.indexer, client].filter(Boolean).join(' → ') || null;
	if (h.eventType.endsWith('Imported')) return client ? `From ${client}` : null;
	return null;
}

export function history(s: ServiceRecord, filter: HistoryFilter, page: number): Promise<Paged<HistoryItem>> {
	return cached(`svc-arr:${s.id}:history:${filter}:${page}`, 15_000, async () => {
		const include = s.type === 'sonarr' ? 'includeSeries=true&includeEpisode=true' : 'includeMovie=true';
		const ev = filter === 'all' ? '' : eventIds(s, filter).map((id) => `&eventType=${id}`).join('');
		const r = await call<PageOf<History>>(
			s,
			`/api/v3/history?page=${page}&pageSize=${PAGE_SIZE}&sortKey=date&sortDirection=descending&${include}${ev}`
		);
		return paged(r, page, (h): HistoryItem => {
			const e = EVENTS[h.eventType] ?? { label: h.eventType.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase()), tone: 'other' as const };
			const series = s.type === 'sonarr';
			return {
				id: h.id,
				event: h.eventType,
				eventLabel: e.label,
				tone: e.tone,
				title: series ? (h.series?.title ?? 'Unknown series') : h.movie ? `${h.movie.title}${h.movie.year ? ` (${h.movie.year})` : ''}` : 'Unknown movie',
				subtitle: h.episode ? `S${pad(h.episode.seasonNumber)}E${pad(h.episode.episodeNumber)}${h.episode.title ? ` · ${h.episode.title}` : ''}` : null,
				source: h.sourceTitle ?? null,
				quality: h.quality?.quality?.name ?? null,
				detail: historyDetail(h),
				date: h.date,
				href: series ? (h.seriesId ? `/library/${s.id}/series/${h.seriesId}` : null) : h.movieId ? `/library/${s.id}/movie/${h.movieId}` : null
			};
		});
	});
}

/* ---------------------------------------------------------------- */
/* Commands                                                          */
/* ---------------------------------------------------------------- */

const COMMANDS: Record<ArrCommand, { sonarr: string; radarr: string; done: string }> = {
	rss: { sonarr: 'RssSync', radarr: 'RssSync', done: 'RSS sync started' },
	missing: { sonarr: 'MissingEpisodeSearch', radarr: 'MissingMoviesSearch', done: 'Searching for everything missing' },
	cutoff: { sonarr: 'CutoffUnmetEpisodeSearch', radarr: 'CutoffUnmetMoviesSearch', done: 'Searching for cutoff upgrades' },
	refresh: { sonarr: 'RefreshSeries', radarr: 'RefreshMovie', done: 'Refresh & scan started' }
};

/** Which of our task buttons started this arr command (so the button can stay disabled while it runs). */
function commandKey(name: string | undefined): ArrCommand | null {
	for (const [key, def] of Object.entries(COMMANDS) as Array<[ArrCommand, (typeof COMMANDS)[ArrCommand]]>) {
		if (name === def.sonarr || name === def.radarr) return key;
	}
	return null;
}

export function parseCommand(body: unknown): ArrCommand {
	const c = body && typeof body === 'object' ? (body as { command?: unknown }).command : undefined;
	if (typeof c !== 'string' || !(ARR_COMMANDS as readonly string[]).includes(c)) {
		throw new ServiceError(`command must be one of: ${ARR_COMMANDS.join(', ')}`, 400);
	}
	return c as ArrCommand;
}

export async function runCommand(s: ServiceRecord, command: ArrCommand): Promise<{ ok: true; message: string; id: number | null }> {
	const def = COMMANDS[command];
	const name = s.type === 'sonarr' ? def.sonarr : def.radarr;
	const r = await call<Command>(s, '/api/v3/command', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ name })
	});
	invalidate(`svc-arr:${s.id}:overview`);
	return { ok: true, message: def.done, id: r && typeof r === 'object' && typeof r.id === 'number' ? r.id : null };
}
