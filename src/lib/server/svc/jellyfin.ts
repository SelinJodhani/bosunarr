/**
 * Jellyfin adapter for the dedicated service screen (Jellyfin 10.10 – 12.x REST API).
 * Every call goes through `call()`, which injects the `Authorization: MediaBrowser … Token=` header.
 */
import { cached, call, invalidate, ServiceError } from '../services/client';
import type { ServiceRecord } from '../services/store';
import type {
	JfAction,
	JfActivity,
	JfAdminData,
	JfCounts,
	JfLatest,
	JfLibrary,
	JfPlayMethod,
	JfServer,
	JfSession,
	JfSummary,
	JfTask,
	JfUser
} from '$lib/servicepages/jellyfin/types';

const TICKS = 10_000_000;

/* ----------------------------- raw API shapes ----------------------------- */
type RawItem = {
	Id: string;
	Name?: string;
	Type?: string;
	SeriesName?: string;
	SeriesId?: string;
	ParentIndexNumber?: number;
	IndexNumber?: number;
	RunTimeTicks?: number;
	ProductionYear?: number;
	DateCreated?: string;
	ImageTags?: Record<string, string>;
	SeriesPrimaryImageTag?: string;
	AlbumId?: string;
	AlbumPrimaryImageTag?: string;
	UserData?: { UnplayedItemCount?: number };
};
type RawSession = {
	Id: string;
	UserName?: string;
	Client?: string;
	DeviceName?: string;
	SupportsRemoteControl?: boolean;
	NowPlayingItem?: RawItem;
	PlayState?: { PositionTicks?: number; IsPaused?: boolean; PlayMethod?: string };
	TranscodingInfo?: {
		IsVideoDirect?: boolean;
		IsAudioDirect?: boolean;
		VideoCodec?: string;
		AudioCodec?: string;
		Container?: string;
		Bitrate?: number;
		TranscodeReasons?: string[] | string;
		HardwareAccelerationType?: string;
	};
};
type RawInfo = {
	ServerName?: string;
	Version?: string;
	OperatingSystemDisplayName?: string;
	OperatingSystem?: string;
	HasPendingRestart?: boolean;
	HasUpdateAvailable?: boolean;
	IsShuttingDown?: boolean;
};
type RawFolder = { Name?: string; ItemId?: string; CollectionType?: string; Locations?: string[]; RefreshStatus?: string; RefreshProgress?: number };
type RawUser = { Id: string; Name?: string; LastActivityDate?: string; LastLoginDate?: string; Policy?: { IsAdministrator?: boolean; IsDisabled?: boolean } };
type RawTask = {
	Id: string;
	Name?: string;
	Category?: string;
	State?: string;
	IsHidden?: boolean;
	CurrentProgressPercentage?: number;
	LastExecutionResult?: { EndTimeUtc?: string; Status?: string; ErrorMessage?: string };
};
type RawActivity = { Id: number; Name?: string; ShortOverview?: string; Type?: string; Date?: string; Severity?: string };

/* -------------------------------- helpers -------------------------------- */
const img = (s: ServiceRecord, id: string | undefined, height = 300) =>
	id && ID.test(id) ? `/api/img/${s.id}?p=${encodeURIComponent(`/Items/${id}/Images/Primary?maxHeight=${height}`)}` : null;

/** Which item carries the poster: the item itself, else its series/album. */
function posterOf(s: ServiceRecord, it: RawItem): string | null {
	if (it.Type === 'Episode' || it.Type === 'Season') return img(s, it.SeriesPrimaryImageTag ? it.SeriesId : it.ImageTags?.Primary ? it.Id : it.SeriesId);
	if (it.ImageTags?.Primary) return img(s, it.Id);
	if (it.AlbumPrimaryImageTag) return img(s, it.AlbumId);
	return null;
}

const pad = (n: number | undefined) => String(n ?? 0).padStart(2, '0');

/** "VideoCodecNotSupported" → "Video codec not supported". */
export function humanize(reason: string): string {
	const words = reason.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').toLowerCase();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

const asArray = <T>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);

/** Jellyfin ids are 32 hex chars (GUID without dashes); accept dashed GUIDs too. */
const ID = /^[0-9a-f]{32}$|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Session ids are opaque strings Jellyfin generates; keep them to a safe charset. */
const SESSION_ID = /^[\w-]{1,128}$/;

const key = (s: ServiceRecord, what: string) => `jf:${s.id}:${what}`;

/* --------------------------------- reads --------------------------------- */

export function serverInfo(s: ServiceRecord): Promise<JfServer> {
	return cached(key(s, 'info'), 30_000, async () => {
		const i = await call<RawInfo>(s, '/System/Info');
		if (!i || typeof i !== 'object') throw new ServiceError(`${s.name} sent an unexpected response`);
		return {
			name: i.ServerName || s.name,
			version: i.Version ?? null,
			os: i.OperatingSystemDisplayName || i.OperatingSystem || null,
			restartPending: Boolean(i.HasPendingRestart),
			updateAvailable: Boolean(i.HasUpdateAvailable),
			shuttingDown: Boolean(i.IsShuttingDown)
		};
	});
}

function playMethod(x: RawSession): JfPlayMethod {
	const m = x.PlayState?.PlayMethod;
	if (m === 'Transcode') return 'transcode';
	if (m === 'DirectStream') return 'direct-stream';
	if (m === 'DirectPlay') return 'direct-play';
	// Older servers sometimes omit PlayMethod: infer from TranscodingInfo.
	const t = x.TranscodingInfo;
	if (!t) return 'direct-play';
	return t.IsVideoDirect === false || t.IsAudioDirect === false ? 'transcode' : 'direct-stream';
}

function streamDetail(x: RawSession, method: JfPlayMethod): string | null {
	const t = x.TranscodingInfo;
	if (!t || method === 'direct-play') return null;
	const parts: string[] = [];
	parts.push(t.IsVideoDirect ? 'Video direct' : t.VideoCodec ? `Video → ${t.VideoCodec}` : 'Video converted');
	parts.push(t.IsAudioDirect ? 'Audio direct' : t.AudioCodec ? `Audio → ${t.AudioCodec}` : 'Audio converted');
	if (t.Container) parts.push(t.Container.toUpperCase());
	if (t.Bitrate) parts.push(`${(t.Bitrate / 1e6).toFixed(1)} Mbps`);
	return parts.join(' · ');
}

export function sessions(s: ServiceRecord): Promise<JfSession[]> {
	return cached(key(s, 'sessions'), 3_000, async () => {
		const raw = asArray<RawSession>(await call(s, '/Sessions?activeWithinSeconds=960'));
		return raw
			.filter((x) => x.NowPlayingItem && typeof x.Id === 'string')
			.map((x): JfSession => {
				const it = x.NowPlayingItem!;
				const method = playMethod(x);
				const reasons = x.TranscodingInfo?.TranscodeReasons;
				const reasonList = Array.isArray(reasons) ? reasons : typeof reasons === 'string' && reasons ? reasons.split(',') : [];
				const isEp = it.Type === 'Episode';
				return {
					id: x.Id,
					user: x.UserName ?? 'Someone',
					client: x.Client ?? '',
					device: x.DeviceName ?? '',
					title: (isEp ? it.SeriesName : null) ?? it.Name ?? 'Unknown',
					subtitle: isEp ? `S${pad(it.ParentIndexNumber)}E${pad(it.IndexNumber)} · ${it.Name ?? ''}` : it.ProductionYear ? String(it.ProductionYear) : null,
					poster: posterOf(s, it),
					positionSec: Math.max(0, (x.PlayState?.PositionTicks ?? 0) / TICKS),
					runtimeSec: Math.max(0, (it.RunTimeTicks ?? 0) / TICKS),
					paused: Boolean(x.PlayState?.IsPaused),
					method,
					detail: streamDetail(x, method),
					reasons: method === 'direct-play' ? [] : reasonList.map(humanize),
					hwAccel: x.TranscodingInfo?.HardwareAccelerationType && x.TranscodingInfo.HardwareAccelerationType !== 'none' ? x.TranscodingInfo.HardwareAccelerationType : null,
					canControl: x.SupportsRemoteControl !== false
				};
			});
	});
}

const LIB_TYPES: Record<string, string> = {
	movies: 'Movie',
	tvshows: 'Series',
	music: 'MusicAlbum',
	musicvideos: 'MusicVideo',
	books: 'Book',
	boxsets: 'BoxSet',
	playlists: 'Playlist'
};

async function libraryCount(s: ServiceRecord, f: RawFolder): Promise<number | null> {
	if (!f.ItemId || !ID.test(f.ItemId)) return null;
	const q = new URLSearchParams({ ParentId: f.ItemId, Recursive: 'true', Limit: '0', EnableTotalRecordCount: 'true' });
	const t = f.CollectionType && LIB_TYPES[f.CollectionType];
	if (t) q.set('IncludeItemTypes', t);
	else q.set('IsFolder', 'false');
	try {
		const r = await call<{ TotalRecordCount?: number }>(s, `/Items?${q}`);
		return typeof r?.TotalRecordCount === 'number' ? r.TotalRecordCount : null;
	} catch {
		return null; // a count is nice-to-have; don't fail the whole screen for it
	}
}

async function libraries(s: ServiceRecord): Promise<JfLibrary[]> {
	const folders = asArray<RawFolder>(await call(s, '/Library/VirtualFolders'));
	const counts = await cached(key(s, 'libcounts'), 120_000, async () => {
		const out: Record<string, number | null> = {};
		await Promise.all(folders.map(async (f) => f.ItemId && (out[f.ItemId] = await libraryCount(s, f))));
		return out;
	});
	return folders
		.filter((f) => f.ItemId)
		.map((f) => ({
			id: f.ItemId!,
			name: f.Name ?? 'Library',
			kind: f.CollectionType ?? 'mixed',
			count: counts[f.ItemId!] ?? null,
			paths: f.Locations?.length ?? 0,
			scanning: f.RefreshStatus === 'Active',
			progress: typeof f.RefreshProgress === 'number' ? f.RefreshProgress : null
		}));
}

async function counts(s: ServiceRecord): Promise<JfCounts | null> {
	try {
		const c = await call<Record<string, number>>(s, '/Items/Counts');
		if (!c || typeof c !== 'object') return null;
		return { movies: c.MovieCount ?? 0, series: c.SeriesCount ?? 0, episodes: c.EpisodeCount ?? 0, albums: c.AlbumCount ?? 0, songs: c.SongCount ?? 0, books: c.BookCount ?? 0 };
	} catch {
		return null;
	}
}

function rawUsers(s: ServiceRecord): Promise<RawUser[]> {
	return cached(key(s, 'users'), 60_000, async () => asArray<RawUser>(await call(s, '/Users')));
}

/** `/Items/Latest` needs a user; use the first enabled administrator (what the Jellyfin dashboard shows). */
async function latest(s: ServiceRecord): Promise<JfLatest[]> {
	const users = await rawUsers(s);
	const u = users.find((x) => x.Policy?.IsAdministrator && !x.Policy?.IsDisabled) ?? users.find((x) => !x.Policy?.IsDisabled);
	if (!u || !ID.test(u.Id)) return [];
	const q = new URLSearchParams({ userId: u.Id, Limit: '24', Fields: 'DateCreated,PrimaryImageAspectRatio', EnableImageTypes: 'Primary', GroupItems: 'true' });
	const items = asArray<RawItem>(await call(s, `/Items/Latest?${q}`));
	return items
		.filter((it) => typeof it.Id === 'string')
		.map((it) => ({
			id: it.Id,
			title: it.Type === 'Episode' ? (it.SeriesName ?? it.Name ?? '') : (it.Name ?? ''),
			year: it.ProductionYear ?? null,
			kind: it.Type ?? 'Item',
			poster: posterOf(s, it),
			added: it.DateCreated ?? null,
			unplayed: it.UserData?.UnplayedItemCount ?? null
		}));
}

export function summary(s: ServiceRecord): Promise<JfSummary> {
	return cached(key(s, 'summary'), 10_000, async () => {
		const [server, c, libs, recent] = await Promise.all([serverInfo(s), counts(s), libraries(s), latest(s).catch(() => [])]);
		return { server, counts: c, libraries: libs, latest: recent };
	});
}

const TASK_STATE: Record<string, JfTask['state']> = { Running: 'running', Cancelling: 'cancelling' };
const SEVERITY: Record<string, JfActivity['severity']> = { Warning: 'warning', Error: 'error', Critical: 'error' };

export function adminData(s: ServiceRecord): Promise<JfAdminData> {
	return cached(key(s, 'admin'), 5_000, async () => {
		const [users, tasks, log] = await Promise.all([
			rawUsers(s),
			call(s, '/ScheduledTasks?isHidden=false').then((r) => asArray<RawTask>(r)),
			call<{ Items?: RawActivity[] }>(s, '/System/ActivityLog/Entries?startIndex=0&limit=50').then((r) => asArray<RawActivity>(r?.Items))
		]);
		return {
			users: users
				.map((u): JfUser => ({
					id: u.Id,
					name: u.Name ?? '',
					admin: Boolean(u.Policy?.IsAdministrator),
					disabled: Boolean(u.Policy?.IsDisabled),
					lastActivity: u.LastActivityDate ?? null,
					lastLogin: u.LastLoginDate ?? null
				}))
				.sort((a, b) => (b.lastActivity ?? '').localeCompare(a.lastActivity ?? '')),
			tasks: tasks
				.filter((t) => !t.IsHidden && typeof t.Id === 'string')
				.map((t): JfTask => ({
					id: t.Id,
					name: t.Name ?? 'Task',
					category: t.Category ?? 'Other',
					state: TASK_STATE[t.State ?? ''] ?? 'idle',
					progress: typeof t.CurrentProgressPercentage === 'number' ? t.CurrentProgressPercentage : null,
					lastStatus: t.LastExecutionResult?.Status ?? null,
					lastEnded: t.LastExecutionResult?.EndTimeUtc ?? null,
					lastError: t.LastExecutionResult?.ErrorMessage ?? null
				}))
				.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name)),
			activity: log
				.filter((e) => typeof e.Id === 'number' && e.Date)
				.map((e) => ({
					id: e.Id,
					name: e.Name ?? '',
					overview: e.ShortOverview ?? null,
					type: e.Type ?? '',
					severity: SEVERITY[e.Severity ?? ''] ?? 'info',
					date: e.Date!
				}))
		};
	});
}

/* --------------------------------- writes -------------------------------- */

const str = (v: unknown, max: number): string | null => (typeof v === 'string' && v.trim() && v.length <= max ? v.trim() : null);

/** Validate an untrusted action body. Throws a 400 for anything malformed. */
export function parseAction(body: unknown): JfAction {
	const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
	const bad = (m: string) => new ServiceError(m, 400);
	switch (b.action) {
		case 'stop': {
			const session = str(b.session, 128);
			if (!session || !SESSION_ID.test(session)) throw bad('Invalid session');
			return { action: 'stop', session };
		}
		case 'message': {
			const session = str(b.session, 128);
			if (!session || !SESSION_ID.test(session)) throw bad('Invalid session');
			const text = str(b.text, 500);
			if (!text) throw bad('Message must be 1–500 characters');
			const header = b.header === undefined || b.header === '' ? undefined : str(b.header, 80);
			if (header === null) throw bad('Title must be at most 80 characters');
			return { action: 'message', session, text, header };
		}
		case 'scan': {
			const library = str(b.library, 64);
			if (!library || !ID.test(library)) throw bad('Invalid library');
			return { action: 'scan', library };
		}
		case 'scanAll':
			return { action: 'scanAll' };
		case 'runTask': {
			const task = str(b.task, 64);
			if (!task || !ID.test(task)) throw bad('Invalid task');
			return { action: 'runTask', task };
		}
		default:
			throw bad('Unknown action');
	}
}

const post = (s: ServiceRecord, path: string, body?: unknown) =>
	call(s, path, body === undefined ? { method: 'POST' } : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

/** Run a validated action. Targets are checked against what the server reports, so only real ids are touched. */
export async function runAction(s: ServiceRecord, a: JfAction): Promise<{ ok: true }> {
	switch (a.action) {
		case 'stop':
		case 'message': {
			const live = asArray<RawSession>(await call(s, '/Sessions?activeWithinSeconds=960'));
			if (!live.some((x) => x.Id === a.session)) throw new ServiceError('That session has ended', 404);
			const id = encodeURIComponent(a.session);
			if (a.action === 'stop') await post(s, `/Sessions/${id}/Playing/Stop`);
			else await post(s, `/Sessions/${id}/Message`, { Header: a.header ?? 'Message from Bosunarr', Text: a.text, TimeoutMs: 10_000 });
			invalidate(key(s, 'sessions'));
			break;
		}
		case 'scan': {
			const folders = asArray<RawFolder>(await call(s, '/Library/VirtualFolders'));
			if (!folders.some((f) => f.ItemId === a.library)) throw new ServiceError('Library not found', 404);
			const q = new URLSearchParams({ Recursive: 'true', MetadataRefreshMode: 'Default', ImageRefreshMode: 'Default', ReplaceAllMetadata: 'false', ReplaceAllImages: 'false' });
			await post(s, `/Items/${a.library}/Refresh?${q}`);
			invalidate(key(s, 'summary'));
			invalidate(key(s, 'libcounts'));
			break;
		}
		case 'scanAll':
			await post(s, '/Library/Refresh');
			invalidate(key(s, 'summary'));
			invalidate(key(s, 'libcounts'));
			invalidate(key(s, 'admin'));
			break;
		case 'runTask': {
			const tasks = asArray<RawTask>(await call(s, '/ScheduledTasks?isHidden=false'));
			if (!tasks.some((t) => t.Id === a.task)) throw new ServiceError('Task not found', 404);
			await post(s, `/ScheduledTasks/Running/${a.task}`);
			invalidate(key(s, 'admin'));
			break;
		}
	}
	return { ok: true };
}
