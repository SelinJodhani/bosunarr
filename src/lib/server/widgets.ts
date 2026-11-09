import { cached, call, testConnection, ServiceError } from './services/client';
import { listServices, servicesOfType, type ServiceRecord } from './services/store';
import { arrImage } from './library';
import type {
	DiskItem,
	DownloadsData,
	HealthItem,
	NowPlayingItem,
	QueueItem,
	RequestItem,
	UpcomingItem
} from '$lib/widgets/types';

const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Run fn for every service. Partial failures are tolerated (they show in Health),
 * but if every instance fails the widget gets the error instead of a misleading "empty".
 */
async function each<T>(services: ServiceRecord[], fn: (s: ServiceRecord) => Promise<T[]>): Promise<T[]> {
	const results = await Promise.allSettled(services.map(fn));
	const failed = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected');
	if (services.length > 0 && failed.length === services.length) {
		const e = failed[0].reason;
		throw e instanceof ServiceError ? e : new ServiceError(errMsg(e));
	}
	return results.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
}

const img = (s: ServiceRecord, path: string | undefined | null) =>
	path ? `/api/img/${s.id}?p=${encodeURIComponent(path)}` : null;

type ArrImage = { coverType: string; url?: string; remoteUrl?: string };
const poster = (s: ServiceRecord, images?: ArrImage[]) => arrImage(s, images, 'poster', 250);

export function health(): Promise<HealthItem[]> {
	return cached('w:health', 15_000, () =>
		Promise.all(
			listServices().map(async (s): Promise<HealthItem> => {
				try {
					const r = await testConnection(s);
					return { id: s.id, name: s.name, type: s.type, ok: true, ms: r.ms, version: r.version ?? null, error: null };
				} catch (e) {
					return { id: s.id, name: s.name, type: s.type, ok: false, ms: null, version: null, error: errMsg(e) };
				}
			})
		)
	);
}

export function downloads(): Promise<DownloadsData> {
	return cached('w:downloads', 2_000, async () => {
		const out: DownloadsData = { down: 0, up: 0, active: 0, torrents: [] };
		await each(servicesOfType('qbittorrent'), async (s) => {
			const [info, torrents] = await Promise.all([
				call<{ dl_info_speed: number; up_info_speed: number }>(s, '/api/v2/transfer/info'),
				call<Array<{ hash: string; name: string; progress: number; dlspeed: number; upspeed: number; eta: number; state: string; size: number }>>(
					s,
					'/api/v2/torrents/info?filter=active&sort=dlspeed&reverse=true'
				)
			]);
			out.down += info.dl_info_speed;
			out.up += info.up_info_speed;
			out.active += torrents.length;
			for (const t of torrents.slice(0, 8))
				out.torrents.push({ id: `${s.id}:${t.hash}`, name: t.name, progress: t.progress, dlspeed: t.dlspeed, eta: t.eta, state: t.state, size: t.size });
			return [];
		});
		out.torrents.sort((a, b) => b.dlspeed - a.dlspeed);
		return out;
	});
}

type ArrQueueRecord = {
	id: number;
	title: string;
	size: number;
	sizeleft: number;
	status: string;
	timeleft?: string;
	trackedDownloadState?: string;
	series?: { title: string; images?: ArrImage[] };
	episode?: { seasonNumber: number; episodeNumber: number; title: string };
	movie?: { title: string; year: number; images?: ArrImage[] };
};

export function queue(): Promise<QueueItem[]> {
	return cached('w:queue', 5_000, () =>
		each(servicesOfType('sonarr', 'radarr'), async (s) => {
			const include = s.type === 'sonarr' ? 'includeSeries=true&includeEpisode=true' : 'includeMovie=true';
			const r = await call<{ records: ArrQueueRecord[] }>(s, `/api/v3/queue?pageSize=50&${include}`);
			return r.records.map((q): QueueItem => {
				const ep = q.episode ? `S${String(q.episode.seasonNumber).padStart(2, '0')}E${String(q.episode.episodeNumber).padStart(2, '0')} · ${q.episode.title}` : null;
				return {
					id: `${s.id}:${q.id}`,
					service: s.name,
					title: q.series?.title ?? (q.movie ? `${q.movie.title} (${q.movie.year})` : q.title),
					subtitle: ep,
					progress: q.size ? 1 - q.sizeleft / q.size : 0,
					status: q.trackedDownloadState ?? q.status,
					timeleft: q.timeleft ?? null,
					poster: poster(s, q.series?.images ?? q.movie?.images)
				};
			});
		})
	);
}

export function upcoming(days = 7): Promise<UpcomingItem[]> {
	return cached(`w:upcoming:${days}`, 60_000, async () => {
		const start = new Date();
		start.setHours(0, 0, 0, 0);
		const end = new Date(start.getTime() + days * 864e5);
		const range = `start=${start.toISOString()}&end=${end.toISOString()}`;
		const items = await each(servicesOfType('sonarr', 'radarr'), async (s): Promise<UpcomingItem[]> => {
			if (s.type === 'sonarr') {
				const eps = await call<
					Array<{ id: number; seasonNumber: number; episodeNumber: number; title: string; airDateUtc: string; hasFile: boolean; series: { title: string; images?: ArrImage[] } }>
				>(s, `/api/v3/calendar?${range}&includeSeries=true`);
				return eps.map((e) => ({
					id: `${s.id}:e${e.id}`,
					kind: 'episode',
					title: e.series.title,
					subtitle: `S${String(e.seasonNumber).padStart(2, '0')}E${String(e.episodeNumber).padStart(2, '0')} · ${e.title}`,
					date: e.airDateUtc,
					hasFile: e.hasFile,
					poster: poster(s, e.series.images),
					service: s.name
				}));
			}
			const movies = await call<
				Array<{ id: number; title: string; year: number; digitalRelease?: string; physicalRelease?: string; inCinemas?: string; hasFile: boolean; images?: ArrImage[] }>
			>(s, `/api/v3/calendar?${range}`);
			return movies.map((m) => {
				const dates = [m.digitalRelease, m.physicalRelease, m.inCinemas].filter(Boolean) as string[];
				const inRange = dates.filter((d) => +new Date(d) >= +start && +new Date(d) < +end).sort();
				return {
					id: `${s.id}:m${m.id}`,
					kind: 'movie',
					title: m.title,
					subtitle: m.digitalRelease && inRange[0] === m.digitalRelease ? 'Digital release' : m.inCinemas && inRange[0] === m.inCinemas ? 'In cinemas' : 'Physical release',
					date: inRange[0] ?? dates[0],
					hasFile: m.hasFile,
					poster: poster(s, m.images),
					service: s.name
				};
			});
		});
		return items.sort((a, b) => +new Date(a.date) - +new Date(b.date));
	});
}

type SeerrRequest = {
	id: number;
	status: number;
	is4k: boolean;
	createdAt: string;
	type: 'movie' | 'tv';
	requestedBy?: { displayName?: string; username?: string };
	media: { tmdbId: number; mediaType: 'movie' | 'tv' };
};

export function requests(): Promise<RequestItem[]> {
	return cached('w:requests', 15_000, () =>
		each(servicesOfType('jellyseerr', 'overseerr'), async (s) => {
			const r = await call<{ results: SeerrRequest[] }>(s, '/api/v1/request?take=10&filter=pending&sort=added');
			return Promise.all(
				r.results.map(async (q): Promise<RequestItem> => {
					const details = await cached(`seerr:${s.id}:${q.type}:${q.media.tmdbId}`, 6 * 3600_000, () =>
						call<{ title?: string; name?: string; posterPath?: string; releaseDate?: string; firstAirDate?: string }>(
							s,
							`/api/v1/${q.type === 'movie' ? 'movie' : 'tv'}/${q.media.tmdbId}`
						)
					).catch(() => ({}) as { title?: string; name?: string; posterPath?: string; releaseDate?: string; firstAirDate?: string });
					const year = (details.releaseDate ?? details.firstAirDate ?? '').slice(0, 4);
					return {
						id: `${s.id}:${q.id}`,
						serviceId: s.id,
						requestId: q.id,
						title: details.title ?? details.name ?? `TMDB ${q.media.tmdbId}`,
						year: year || null,
						kind: q.type,
						is4k: q.is4k,
						requestedBy: q.requestedBy?.displayName ?? q.requestedBy?.username ?? 'Unknown',
						createdAt: q.createdAt,
						poster: details.posterPath ? img(s, `/imageproxy/tmdb/t/p/w300_and_h450_face${details.posterPath}`) : null
					};
				})
			);
		})
	);
}

export function disk(): Promise<DiskItem[]> {
	return cached('w:disk', 60_000, async () => {
		const disks: DiskItem[] = [];
		await each(servicesOfType('sonarr', 'radarr'), async (s) => {
			const spaces = await call<Array<{ path: string; label?: string; freeSpace: number; totalSpace: number }>>(s, '/api/v3/diskspace');
			for (const d of spaces) {
				if (!d.totalSpace || d.path.startsWith('/config') || d.path === '/app') continue;
				// Several arrs report the same disk a moment apart, so free space differs slightly between them.
				// Same path, or same size with nearly the same free space (one disk mounted at different paths) = one disk.
				const same = disks.find(
					(x) => x.path === d.path || (x.total === d.totalSpace && Math.abs(x.free - d.freeSpace) <= Math.max(1024 ** 3, d.totalSpace * 0.001))
				);
				if (same) same.free = Math.min(same.free, d.freeSpace);
				else disks.push({ path: d.path, free: d.freeSpace, total: d.totalSpace });
			}
			return [];
		});
		return disks.sort((a, b) => b.total - a.total);
	});
}

type JfSession = {
	Id: string;
	UserName?: string;
	DeviceName?: string;
	Client?: string;
	PlayState?: { PositionTicks?: number; IsPaused?: boolean };
	NowPlayingItem?: { Id: string; Name: string; SeriesName?: string; RunTimeTicks?: number; Type: string; ParentIndexNumber?: number; IndexNumber?: number; SeriesId?: string };
};

export function nowPlaying(): Promise<NowPlayingItem[]> {
	return cached('w:nowplaying', 5_000, () =>
		each(servicesOfType('jellyfin'), async (s) => {
			const sessions = await call<JfSession[]>(s, '/Sessions?activeWithinSeconds=960');
			return sessions
				.filter((x) => x.NowPlayingItem)
				.map((x): NowPlayingItem => {
					const it = x.NowPlayingItem!;
					const ep = it.Type === 'Episode' ? `S${String(it.ParentIndexNumber ?? 0).padStart(2, '0')}E${String(it.IndexNumber ?? 0).padStart(2, '0')} · ${it.Name}` : null;
					return {
						id: `${s.id}:${x.Id}`,
						user: x.UserName ?? 'Someone',
						title: it.SeriesName ?? it.Name,
						subtitle: ep,
						device: [x.Client, x.DeviceName].filter(Boolean).join(' · '),
						progress: it.RunTimeTicks ? (x.PlayState?.PositionTicks ?? 0) / it.RunTimeTicks : 0,
						paused: Boolean(x.PlayState?.IsPaused),
						poster: img(s, `/Items/${it.SeriesId ?? it.Id}/Images/Primary?maxHeight=300`)
					};
				});
		})
	);
}

export const WIDGET_LOADERS = {
	health,
	downloads,
	queue,
	upcoming: () => upcoming(7),
	requests,
	disk,
	nowplaying: nowPlaying
} as const;

export { ServiceError };
