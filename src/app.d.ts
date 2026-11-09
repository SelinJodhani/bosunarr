// See https://svelte.dev/docs/kit/types#app.d.ts
import type { User } from '$lib/server/auth';

declare global {
	namespace App {
		interface Locals {
			user: User | null;
		}
	}
}

export {};
