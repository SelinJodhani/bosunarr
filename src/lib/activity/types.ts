// Client-safe shapes for the Activity page (arr queues + download clients).

export type QueueEntry = {
	/** `${serviceId}:${queueId}` */
	id: string;
	serviceId: number;
	queueId: number;
	service: string;
	kind: 'movie' | 'episode';
	title: string;
	subtitle: string | null;
	progress: number;
	size: number;
	sizeleft: number;
	/** Arr's tracked download state: downloading, importPending, importing, failedPending … */
	state: string;
	/** ok | warning | error */
	health: 'ok' | 'warning' | 'error';
	messages: string[];
	timeleft: string | null;
	protocol: string | null;
	client: string | null;
	quality: string | null;
	poster: string | null;
	/** Link to the item's library page. */
	href: string | null;
	/** qBittorrent hash when the arr reports one — lets the UI connect a queue row to its torrent. */
	downloadId: string | null;
};

export type TorrentGroup = 'downloading' | 'seeding' | 'paused' | 'completed' | 'queued' | 'checking' | 'stalled' | 'errored';

export type Torrent = {
	/** `${serviceId}:${hash}` */
	id: string;
	serviceId: number;
	hash: string;
	name: string;
	progress: number;
	dlspeed: number;
	upspeed: number;
	eta: number;
	state: string;
	group: TorrentGroup;
	size: number;
	downloaded: number;
	uploaded: number;
	ratio: number;
	category: string;
	tags: string[];
	addedOn: number;
	completedOn: number;
	seeds: number;
	peers: number;
	savePath: string;
};

export type ClientSummary = { id: number; name: string; down: number; up: number; count: number; error: string | null };

export type ActivitySnapshot = {
	at: number;
	queue: QueueEntry[];
	torrents: Torrent[];
	clients: ClientSummary[];
	speeds: { down: number; up: number };
	errors: Array<{ service: string; error: string }>;
};

export type TorrentAction = 'pause' | 'resume' | 'recheck' | 'reannounce' | 'delete' | 'pauseAll' | 'resumeAll';

export type ActionRequest =
	| { action: TorrentAction; ids: string[]; deleteFiles?: boolean }
	| { action: 'removeQueue'; ids: string[]; removeFromClient: boolean; blocklist: boolean };

export type AddTorrentRequest = { serviceId: number; urls: string; category?: string; paused?: boolean };
