import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { env } from '$env/dynamic/private';
import { DATA_DIR } from './db';

/**
 * Credentials (API keys, passwords) are encrypted at rest with AES-256-GCM.
 * The key comes from BOSUNARR_SECRET, or is generated once into the data volume.
 * They are decrypted only inside the server process and never sent to browsers.
 */
function loadKey(): Buffer {
	if (env.BOSUNARR_SECRET) return createHash('sha256').update(env.BOSUNARR_SECRET).digest();
	const file = join(DATA_DIR, 'secret.key');
	if (!existsSync(file)) writeFileSync(file, randomBytes(32).toString('base64'), { mode: 0o600 });
	return Buffer.from(readFileSync(file, 'utf8').trim(), 'base64');
}

const KEY = loadKey();

export function encrypt(value: unknown): string {
	const iv = randomBytes(12);
	const cipher = createCipheriv('aes-256-gcm', KEY, iv);
	const data = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
	return ['v1', iv.toString('base64'), cipher.getAuthTag().toString('base64'), data.toString('base64')].join('.');
}

export function decrypt<T>(payload: string): T {
	const [version, iv, tag, data] = payload.split('.');
	if (version !== 'v1') throw new Error('Unsupported credential format');
	const decipher = createDecipheriv('aes-256-gcm', KEY, Buffer.from(iv, 'base64'));
	decipher.setAuthTag(Buffer.from(tag, 'base64'));
	const out = Buffer.concat([decipher.update(Buffer.from(data, 'base64')), decipher.final()]);
	return JSON.parse(out.toString('utf8')) as T;
}
