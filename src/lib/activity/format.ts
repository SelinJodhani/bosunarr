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
