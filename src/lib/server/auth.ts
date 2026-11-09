import { hash, verify } from '@node-rs/argon2';
import { randomBytes, createHash } from 'node:crypto';
import type { Cookies } from '@sveltejs/kit';
import { db } from './db';

export const SESSION_COOKIE = 'bosunarr_session';
const SESSION_DAYS = 30;

export type User = { id: number; username: string; is_admin: number };

const sha = (s: string) => createHash('sha256').update(s).digest('hex');

export function userCount(): number {
	return (db.prepare('SELECT COUNT(*) AS n FROM users').get() as { n: number }).n;
}

export async function createUser(username: string, password: string, isAdmin: boolean): Promise<number> {
	const passwordHash = await hash(password, { memoryCost: 19456, timeCost: 2, parallelism: 1 });
	const r = db
		.prepare('INSERT INTO users (username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?)')
		.run(username.trim(), passwordHash, isAdmin ? 1 : 0, Date.now());
	return Number(r.lastInsertRowid);
}

export async function checkPassword(username: string, password: string): Promise<User | null> {
	const row = db.prepare('SELECT id, username, is_admin, password_hash FROM users WHERE username = ?').get(username.trim()) as
		| (User & { password_hash: string })
		| undefined;
	// Verify against a dummy hash when the user doesn't exist to keep timing uniform.
	const ok = await verify(
		row?.password_hash ?? '$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$Z0lBgYb9pQ5fPj2aZbm0pFzu1hXx0tQ2kQe1cLkzN0I',
		password
	).catch(() => false);
	if (!row || !ok) return null;
	return { id: row.id, username: row.username, is_admin: row.is_admin };
}

export function createSession(userId: number, cookies: Cookies, secure: boolean) {
	const token = randomBytes(32).toString('base64url');
	const expires = Date.now() + SESSION_DAYS * 864e5;
	db.prepare('INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)').run(sha(token), userId, expires);
	cookies.set(SESSION_COOKIE, token, {
		path: '/',
		httpOnly: true,
		sameSite: 'lax',
		secure,
		expires: new Date(expires)
	});
}

export function validateSession(token: string | undefined): User | null {
	if (!token) return null;
	const row = db
		.prepare(
			'SELECT u.id, u.username, u.is_admin, s.expires_at FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id = ?'
		)
		.get(sha(token)) as (User & { expires_at: number }) | undefined;
	if (!row || row.expires_at < Date.now()) return null;
	return { id: row.id, username: row.username, is_admin: row.is_admin };
}

export function deleteSession(token: string | undefined, cookies: Cookies) {
	if (token) db.prepare('DELETE FROM sessions WHERE id = ?').run(sha(token));
	cookies.delete(SESSION_COOKIE, { path: '/' });
	db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(Date.now());
}
