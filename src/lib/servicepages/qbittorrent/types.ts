// Client-safe shapes for the qBittorrent service screen.

export type QbitConnection = 'connected' | 'firewalled' | 'disconnected' | 'unknown';

export type QbitTransfer = {
	down: number;
	up: number;
	sessionDown: number;
	sessionUp: number;
	allTimeDown: number;
	allTimeUp: number;
	/** null when qBittorrent doesn't report it */
	ratio: number | null;
	freeSpace: number | null;
	dhtNodes: number;
	peers: number;
	connection: QbitConnection;
	/** Effective global limits right now, bytes/s; 0 = unlimited. */
	downLimit: number;
	upLimit: number;
	altSpeed: boolean;
	queueing: boolean;
};

export type QbitPrefs = {
	/** null for non-admins (host paths stay private) */
	savePath: string | null;
	/** null when disabled, or for non-admins */
	tempPath: string | null;
	queueing: boolean;
	maxActiveDownloads: number;
	maxActiveUploads: number;
	maxActiveTorrents: number;
	/** null = no limit */
	maxRatio: number | null;
	/** minutes; null = no limit */
	maxSeedingTime: number | null;
	maxRatioAction: string;
	/** Normal and alternative global limits, bytes/s; 0 = unlimited. */
	downLimit: number;
	upLimit: number;
	altDownLimit: number;
	altUpLimit: number;
	altScheduled: boolean;
	dht: boolean;
	listenPort: number | null;
};

export type QbitOverview = {
	at: number;
	version: string | null;
	webApiVersion: string | null;
	transfer: QbitTransfer;
	categories: string[];
	tags: string[];
	/** null if preferences couldn't be read */
	prefs: QbitPrefs | null;
};
