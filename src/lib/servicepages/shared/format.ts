// Small label helpers shared by the service screens.

/** "torrent" → "Torrent", "usenet" → "Usenet". */
export const protocolLabel = (p: string | null | undefined) =>
	p === 'torrent' ? 'Torrent' : p === 'usenet' ? 'Usenet' : p ? p[0].toUpperCase() + p.slice(1) : 'Unknown';

/** Joins a number and its unit with a no-break space so "43.3 GB" never wraps between them. */
export const nb = (s: string) => s.replace(/ /g, ' ');

/** Title-cases a lowercase id ("jellyfin" → "Jellyfin"), leaves mixed-case names alone. */
export function niceName(s: string | null | undefined): string {
	if (!s) return '';
	return s === s.toLowerCase() ? s[0].toUpperCase() + s.slice(1) : s;
}

/** Turns an error from our API into a sentence (adds a full stop, drops "Error:" prefixes). */
export function sentence(msg: string): string {
	const m = msg.replace(/^(Error|ServiceError):\s*/i, '').trim();
	return /[.!?…]$/.test(m) ? m : `${m}.`;
}
