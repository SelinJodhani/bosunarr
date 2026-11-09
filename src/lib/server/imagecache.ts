import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { readdir, readFile, stat, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { DATA_DIR } from './db';

/**
 * Small on-disk cache for proxied posters/fanart so the arrs aren't hit for every scroll,
 * and images survive restarts. Keyed by service + path; arr URLs carry ?lastWrite so updates bust it.
 */
const DIR = join(DATA_DIR, 'cache', 'img');
mkdirSync(DIR, { recursive: true });

const TTL_MS = 7 * 864e5;
const MAX_BYTES = 512 * 1024 ** 2;
const TYPES: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif' };
const EXT_TYPE = Object.fromEntries(Object.entries(TYPES).map(([t, e]) => [e, t]));

type Img = { type: string; body: Uint8Array };
const inflight = new Map<string, Promise<Img | null>>();
let writes = 0;

async function readCached(hash: string): Promise<Img | null> {
	for (const ext of Object.keys(EXT_TYPE)) {
		const file = join(DIR, `${hash}.${ext}`);
		try {
			const st = await stat(file);
			if (Date.now() - st.mtimeMs > TTL_MS) return null;
			return { type: EXT_TYPE[ext], body: await readFile(file) };
		} catch {
			/* not this extension */
		}
	}
	return null;
}

async function prune() {
	const files = await Promise.all(
		(await readdir(DIR)).map(async (f) => {
			const st = await stat(join(DIR, f)).catch(() => null);
			return st ? { f, size: st.size, at: st.mtimeMs } : null;
		})
	);
	const list = files.filter((x) => x !== null).sort((a, b) => b.at - a.at);
	let total = 0;
	for (const x of list) {
		total += x.size;
		if (total > MAX_BYTES || Date.now() - x.at > TTL_MS) await unlink(join(DIR, x.f)).catch(() => {});
	}
}

export function cachedImage(key: string, fetcher: () => Promise<Img | null>): Promise<Img | null> {
	const hash = createHash('sha256').update(key).digest('hex').slice(0, 40);
	const running = inflight.get(hash);
	if (running) return running;
	const p = (async () => {
		const hit = await readCached(hash);
		if (hit) return hit;
		const img = await fetcher();
		const ext = img && TYPES[img.type.split(';')[0]];
		if (img && ext) {
			await writeFile(join(DIR, `${hash}.${ext}`), img.body).catch(() => {});
			if (++writes % 100 === 0) prune().catch(() => {});
		}
		return img;
	})();
	inflight.set(hash, p);
	p.finally(() => inflight.delete(hash));
	return p;
}
