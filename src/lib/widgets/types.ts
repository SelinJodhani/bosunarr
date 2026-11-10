import type { ServiceTypeId } from '$lib/services/catalog';

export type HealthItem = {
	id: number;
	name: string;
	type: ServiceTypeId;
	ok: boolean;
	ms: number | null;
	version: string | null;
	error: string | null;
};

export type DownloadsData = {
	down: number;
	up: number;
	active: number;
	/** Torrents still downloading (incl. stalled / fetching metadata). Optional: older cached data lacks it. */
	downloading?: number;
	/** Complete torrents that are seeding. */
	seeding?: number;
	/** Downloading torrents only, fastest first. */
	torrents: Array<{ id: string; name: string; progress: number; dlspeed: number; eta: number; state: string; size: number }>;
};

export type QueueItem = {
	id: string;
	service: string;
	title: string;
	subtitle: string | null;
	progress: number;
	status: string;
	timeleft: string | null;
	poster: string | null;
};

export type UpcomingItem = {
	id: string;
	kind: 'episode' | 'movie';
	title: string;
	subtitle: string;
	date: string;
	hasFile: boolean;
	poster: string | null;
	service: string;
	/** Library detail page; optional because older cached data lacks it. */
	href?: string;
};

export type RequestItem = {
	id: string;
	serviceId: number;
	requestId: number;
	title: string;
	year: string | null;
	kind: 'movie' | 'tv';
	is4k: boolean;
	requestedBy: string;
	createdAt: string;
	poster: string | null;
};

export type DiskItem = { path: string; free: number; total: number };

export type NowPlayingItem = {
	id: string;
	user: string;
	title: string;
	subtitle: string | null;
	device: string;
	progress: number;
	paused: boolean;
	poster: string | null;
};

export type WidgetKind = 'health' | 'downloads' | 'queue' | 'upcoming' | 'requests' | 'disk' | 'nowplaying';
export type WidgetSize = 's' | 'm' | 'l';
export type WidgetInstance = { id: string; kind: WidgetKind; size: WidgetSize };

export const WIDGETS: Record<WidgetKind, { label: string; description: string; needs: ServiceTypeId[]; refreshMs: number; defaultSize: WidgetSize }> = {
	health: { label: 'Service health', description: 'Status and response time of every service', needs: [], refreshMs: 30_000, defaultSize: 'm' },
	downloads: { label: 'Downloads', description: 'Live qBittorrent speeds and active torrents', needs: ['qbittorrent'], refreshMs: 3_000, defaultSize: 'm' },
	queue: { label: 'Queue', description: 'What Sonarr and Radarr are grabbing', needs: ['sonarr', 'radarr'], refreshMs: 10_000, defaultSize: 'm' },
	upcoming: { label: 'Upcoming', description: 'Episodes and movies releasing this week', needs: ['sonarr', 'radarr'], refreshMs: 300_000, defaultSize: 'l' },
	requests: { label: 'Requests', description: 'Pending Jellyseerr / Overseerr requests', needs: ['jellyseerr', 'overseerr'], refreshMs: 30_000, defaultSize: 'm' },
	disk: { label: 'Disk space', description: 'Free space on your media drives', needs: ['sonarr', 'radarr'], refreshMs: 300_000, defaultSize: 's' },
	nowplaying: { label: 'Now playing', description: 'Who is watching on Jellyfin', needs: ['jellyfin'], refreshMs: 10_000, defaultSize: 'm' }
};


const SPAN = { 8: { s: 4, m: 8, l: 8 }, 12: { s: 3, m: 6, l: 12 } } as const;

/** Column spans for the 8- and 12-column grids. Widgets are packed in order and the last widget of a
 *  row that would leave a gap is widened to fill it, so rows always end flush (no ragged holes). */
export function spansFor(widgets: Array<Pick<WidgetInstance, 'size'>>): Array<{ span8: number; span12: number }> {
	const out = widgets.map(() => ({ span8: 0, span12: 0 }));
	for (const cols of [8, 12] as const) {
		const key = cols === 8 ? 'span8' : 'span12';
		let used = 0;
		widgets.forEach((w, i) => {
			const span = SPAN[cols][w.size] ?? cols;
			if (used > 0 && used + span > cols) {
				out[i - 1][key] += cols - used;
				used = 0;
			}
			out[i][key] = span;
			used = (used + span) % cols;
		});
		if (used > 0) out[widgets.length - 1][key] += cols - used;
	}
	return out;
}
