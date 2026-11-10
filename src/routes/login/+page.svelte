<script lang="ts">
	import { onMount, tick, untrack } from 'svelte';
	import { clearCache } from '$lib/http';
	import { enhance } from '$app/forms';
	import { Button, TextField } from 'm3-svelte';
	import AuthCard from '$lib/components/AuthCard.svelte';

	// Signed out (or about to sign in as someone else): drop everything cached for the previous account.
	onMount(clearCache);

	let { form } = $props();
	let busy = $state(false);
	let username = $state('');
	let password = $state('');
	let errors = $state<{ username?: string; password?: string }>({});
	let formEl: HTMLFormElement;

	// Without JS the server re-renders the page: show its error and keep the username.
	$effect.pre(() => {
		const f = form;
		if (f?.error)
			untrack(() => {
				username = String(f.username || username);
				errors = { [f.field ?? 'password']: f.error };
			});
	});

	const focus = (name: string) => (formEl.elements.namedItem(name) as HTMLInputElement | null)?.focus();
</script>

<svelte:head><title>Sign in · Bosunarr</title></svelte:head>

<AuthCard title="Sign in" subtitle="Bosunarr runs your *arr crew.">
	<form
		method="POST"
		novalidate
		bind:this={formEl}
		use:enhance={({ cancel }) => {
			const missing = { username: username.trim() ? undefined : 'Enter your username', password: password ? undefined : 'Enter your password' };
			if (missing.username || missing.password) {
				errors = missing;
				focus(missing.username ? 'username' : 'password');
				return cancel();
			}
			errors = {};
			busy = true;
			return async ({ result, update }) => {
				await update({ reset: false });
				busy = false;
				if (result.type === 'failure') {
					password = '';
					await tick();
					focus('password');
				}
			};
		}}
	>
		<div class="field">
			<TextField
				label="Username"
				name="username"
				autocomplete="username"
				autocapitalize="off"
				spellcheck="false"
				bind:value={username}
				error={!!errors.username}
				aria-invalid={!!errors.username}
				oninput={() => (errors.username = undefined)}
			/>
			<p class="support" class:err={errors.username} aria-live="polite">{errors.username ?? ''}</p>
		</div>
		<div class="field">
			<TextField
				label="Password"
				name="password"
				type="password"
				autocomplete="current-password"
				bind:value={password}
				error={!!errors.password}
				aria-invalid={!!errors.password}
				oninput={() => (errors.password = undefined)}
			/>
			<p class="support" class:err={errors.password} aria-live="polite">{errors.password ?? ''}</p>
		</div>
		<Button variant="filled" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</Button>
	</form>
</AuthCard>

<style>
	form {
		display: grid;
		gap: 0.25rem;
	}
	.field {
		display: grid;
		gap: 0.25rem;
	}
	.field :global(.m3-container) {
		min-width: 0;
		width: 100%;
	}
	/* M3 supporting text: always reserves its line, so errors never move the card. */
	.support {
		min-height: 1rem;
		margin: 0 0 0.5rem;
		padding-inline: 1rem;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.support.err {
		color: var(--m3c-error);
	}
</style>
