// Human-readable labels for arr queue data, shared by the Activity page and the dashboard Queue widget.

const QUEUE_STATE: Record<string, string> = {
	downloading: 'Downloading',
	importPending: 'Waiting to import',
	importBlocked: 'Import blocked',
	importing: 'Importing',
	imported: 'Imported',
	failedPending: 'Failed',
	failed: 'Failed',
	ignored: 'Ignored',
	delay: 'Delayed',
	queued: 'Queued',
	paused: 'Paused',
	completed: 'Completed',
	warning: 'Warning'
};

export function queueState(state: string): string {
	return QUEUE_STATE[state] ?? state.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase());
}

/** Arr time spans look like "1.02:03:04" (days.hours:minutes:seconds) or "00:12:34". */
export function arrTimeleft(t: string | null | undefined): string | null {
	const m = t?.match(/^(?:(\d+)\.)?(\d+):(\d+):(\d+)/);
	if (!m) return null;
	const [d, h, min] = [Number(m[1] ?? 0), Number(m[2]), Number(m[3])];
	if (d) return `${d}d ${h}h`;
	if (h) return `${h}h ${min}m`;
	return min ? `${min}m` : '<1m';
}

/* qBittorrent's raw torrent states, in words. */
const TORRENT_STATE: Record<string, string> = {
	downloading: 'Downloading',
	forcedDL: 'Downloading (forced)',
	metaDL: 'Fetching metadata',
	forcedMetaDL: 'Fetching metadata (forced)',
	stalledDL: 'Stalled, waiting for peers',
	queuedDL: 'Queued to download',
	pausedDL: 'Paused',
	stoppedDL: 'Paused',
	uploading: 'Seeding',
	forcedUP: 'Seeding (forced)',
	stalledUP: 'Seeding, no one downloading',
	queuedUP: 'Queued to seed',
	pausedUP: 'Completed, not seeding',
	stoppedUP: 'Completed, not seeding',
	checkingDL: 'Checking files',
	checkingUP: 'Checking files',
	checkingResumeData: 'Checking resume data',
	moving: 'Moving files',
	allocating: 'Allocating disk space',
	error: 'Error',
	missingFiles: 'Files missing'
};

export function torrentState(state: string): string {
	return TORRENT_STATE[state] ?? state.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase());
}

/** Torrent is stopped (paused before completion, or completed and not seeding). */
export const isStopped = (state: string) => /^(paused|stopped)/.test(state);
/** qBittorrent is busy with the files (hash check, move, allocation). */
export const isCheckingState = (state: string) => /^checking/.test(state) || state === 'moving' || state === 'allocating';

const WORDS: Record<string, string> = { tv: 'TV', '4k': '4K', uhd: 'UHD', hd: 'HD', sonarr: 'Sonarr', radarr: 'Radarr', lidarr: 'Lidarr', readarr: 'Readarr', whisparr: 'Whisparr', anime: 'Anime' };

/** "radarr-4k" → "Radarr 4K", "tv-sonarr" → "TV Sonarr". Only for display; the raw value is what gets sent. */
export function categoryLabel(c: string): string {
	if (!c) return 'None';
	if (/[A-Z]/.test(c) || c.includes('/')) return c; // already human-styled or a path-like category: leave it alone
	return c
		.split(/[-_\s.]+/)
		.filter(Boolean)
		.map((w) => WORDS[w] ?? w.charAt(0).toUpperCase() + w.slice(1))
		.join(' ');
}
