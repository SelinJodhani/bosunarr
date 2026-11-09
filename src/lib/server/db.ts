import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { env } from '$env/dynamic/private';

export const DATA_DIR = env.BOSUNARR_DATA_DIR || join(process.cwd(), 'data');
mkdirSync(DATA_DIR, { recursive: true });

export const db = new Database(join(DATA_DIR, 'bosunarr.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');
db.pragma('busy_timeout = 5000');

const migrations: string[] = [
	`CREATE TABLE users (
		id INTEGER PRIMARY KEY,
		username TEXT NOT NULL UNIQUE COLLATE NOCASE,
		password_hash TEXT NOT NULL,
		is_admin INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	);
	CREATE TABLE sessions (
		id TEXT PRIMARY KEY,
		user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		expires_at INTEGER NOT NULL
	);
	CREATE TABLE services (
		id INTEGER PRIMARY KEY,
		type TEXT NOT NULL,
		name TEXT NOT NULL,
		url TEXT NOT NULL,
		external_url TEXT,
		credentials TEXT NOT NULL,
		options TEXT NOT NULL DEFAULT '{}',
		sort INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	);
	CREATE TABLE dashboards (
		user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
		layout TEXT NOT NULL
	);`
];

db.exec('CREATE TABLE IF NOT EXISTS _migrations (version INTEGER PRIMARY KEY)');
const applied = (db.prepare('SELECT MAX(version) AS v FROM _migrations').get() as { v: number | null }).v ?? 0;
for (let i = applied; i < migrations.length; i++) {
	db.transaction(() => {
		db.exec(migrations[i]);
		db.prepare('INSERT INTO _migrations (version) VALUES (?)').run(i + 1);
	})();
}
