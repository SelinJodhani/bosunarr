/** Client-safe shapes for the Jellyfin screen (built by `$lib/server/svc/jellyfin`). */

export type JfServer = {
	name: string;
	version: string | null;
	os: string | null;
	restartPending: boolean;
	updateAvailable: boolean;
	shuttingDown: boolean;
};

export type JfCounts = { movies: number; series: number; episodes: number; albums: number; songs: number; books: number };

export type JfPlayMethod = 'direct-play' | 'direct-stream' | 'transcode';

export type JfSession = {
	id: string;
	user: string;
	client: string;
	device: string;
	title: string;
	subtitle: string | null;
	poster: string | null;
	positionSec: number;
	runtimeSec: number;
	paused: boolean;
	method: JfPlayMethod;
	/** e.g. "video: h264 · audio: direct" — only for direct stream / transcode. */
	detail: string | null;
	reasons: string[];
	hwAccel: string | null;
	canControl: boolean;
};

export type JfLibrary = {
	id: string;
	name: string;
	kind: string;
	count: number | null;
	paths: number;
	scanning: boolean;
	progress: number | null;
};

export type JfLatest = { id: string; title: string; year: number | null; kind: string; poster: string | null; added: string | null; unplayed: number | null };

export type JfUser = { id: string; name: string; admin: boolean; disabled: boolean; lastActivity: string | null; lastLogin: string | null };

export type JfTaskState = 'idle' | 'running' | 'cancelling';
export type JfTask = {
	id: string;
	name: string;
	category: string;
	state: JfTaskState;
	progress: number | null;
	lastStatus: string | null;
	lastEnded: string | null;
	lastError: string | null;
};

export type JfActivity = { id: number; name: string; overview: string | null; type: string; severity: 'info' | 'warning' | 'error'; date: string };

export type JfSummary = {
	server: JfServer;
	counts: JfCounts | null;
	libraries: JfLibrary[];
	latest: JfLatest[];
};

/** Users and the activity log are only sent to Bosunarr admins (`null` otherwise). */
export type JfAdminData = { users: JfUser[] | null; tasks: JfTask[]; activity: JfActivity[] | null };

export type JfAction =
	| { action: 'stop'; session: string }
	| { action: 'message'; session: string; header?: string; text: string }
	| { action: 'scan'; library: string }
	| { action: 'scanAll' }
	| { action: 'runTask'; task: string };
