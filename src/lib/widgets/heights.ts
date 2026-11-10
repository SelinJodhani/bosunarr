/**
 * Remembers each widget's rendered content height in a cookie so the server can render its loading
 * skeleton at the same height on the next visit — the dashboard then doesn't jump when data paints.
 * Only numbers keyed by widget id; nothing personal.
 */
export const HEIGHTS_COOKIE = 'bosunarr_wh';
const ID = /^[\w-]{1,40}$/;

export function parseHeights(raw: string | undefined): Record<string, number> {
	const out: Record<string, number> = {};
	if (!raw) return out;
	try {
		const v = JSON.parse(raw);
		if (v && typeof v === 'object')
			for (const [k, h] of Object.entries(v).slice(0, 40))
				if (ID.test(k) && typeof h === 'number' && h >= 0 && h <= 4000) out[k] = Math.round(h);
	} catch {
		/* ignore a malformed cookie */
	}
	return out;
}

const current: Record<string, number> = {};
let timer: ReturnType<typeof setTimeout> | undefined;

export function rememberHeight(id: string, h: number) {
	if (!ID.test(id) || !(h > 0) || current[id] === Math.round(h)) return;
	current[id] = Math.round(h);
	clearTimeout(timer);
	timer = setTimeout(() => {
		document.cookie = `${HEIGHTS_COOKIE}=${encodeURIComponent(JSON.stringify(current))}; path=/; max-age=31536000; samesite=lax`;
	}, 500);
}

/** Seed from what the server read, so ids not on screen yet keep their saved value. */
export function seedHeights(h: Record<string, number>) {
	for (const [k, v] of Object.entries(h)) if (!(k in current)) current[k] = v;
}

/** First-visit guesses (content px at phone width) until the real height has been remembered once. */
const DEFAULT_HEIGHTS: Record<string, Record<'s' | 'm' | 'l', number>> = {
	nowplaying: { s: 92, m: 140, l: 140 },
	requests: { s: 150, m: 230, l: 260 },
	downloads: { s: 70, m: 236, l: 380 },
	queue: { s: 160, m: 270, l: 400 },
	upcoming: { s: 150, m: 233, l: 250 },
	health: { s: 260, m: 260, l: 200 },
	disk: { s: 45, m: 45, l: 45 }
};

export function reserveFor(heights: Record<string, number>, w: { id: string; kind: string; size: 's' | 'm' | 'l' }): number | undefined {
	return heights[w.id] ?? DEFAULT_HEIGHTS[w.kind]?.[w.size];
}
