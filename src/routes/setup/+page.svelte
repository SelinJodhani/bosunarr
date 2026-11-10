<script lang="ts">
	import { tick, untrack } from 'svelte';
	import { enhance } from '$app/forms';
	import { Button, TextField } from 'm3-svelte';
	import AuthCard from '$lib/components/AuthCard.svelte';

	let { form } = $props();
	let busy = $state(false);
	let username = $state('');
	let password = $state('');
	let errors = $state<{ username?: string; password?: string }>({});
	let formEl: HTMLFormElement;

	const USERNAME = /^[\w.@-]{3,32}$/;
	const check = () => ({
		username: USERNAME.test(username.trim()) ? undefined : username.trim() ? 'Use 3–32 letters, numbers or . _ - @' : 'Choose a username',
		password: password.length >= 10 ? undefined : password ? 'Use at least 10 characters' : 'Choose a password'
	});

	// Without JS the server re-renders the page: show its error and keep the username.
	$effect.pre(() => {
		const f = form;
		if (f?.error)
			untrack(() => {
				username = String(f.username || username);
				errors = { [f.field ?? 'password']: f.error };
			});
	});

	// An error goes away as soon as the field becomes valid.
	function revalidate(field: 'username' | 'password') {
		if (errors[field] && !check()[field]) errors[field] = undefined;
	}

	const focus = (name: string) => (formEl.elements.namedItem(name) as HTMLInputElement | null)?.focus();
</script>

<svelte:head><title>Welcome · Bosunarr</title></svelte:head>

<AuthCard title="Welcome aboard" subtitle="Create the admin account. You'll add your services next.">
	<form
		method="POST"
		novalidate
		bind:this={formEl}
		use:enhance={({ cancel }) => {
			const e = check();
			if (e.username || e.password) {
				errors = e;
				focus(e.username ? 'username' : 'password');
				return cancel();
			}
			errors = {};
			busy = true;
			return async ({ result, update }) => {
				await update({ reset: false });
				busy = false;
				if (result.type === 'failure') {
					await tick();
					focus(String(result.data?.field ?? 'password'));
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
				oninput={() => revalidate('username')}
			/>
			<p class="support" class:err={errors.username} aria-live="polite">{errors.username ?? ''}</p>
		</div>
		<div class="field">
			<TextField
				label="Password"
				name="password"
				type="password"
				autocomplete="new-password"
				bind:value={password}
				error={!!errors.password}
				aria-invalid={!!errors.password}
				oninput={() => revalidate('password')}
			/>
			<p class="support" class:err={errors.password} aria-live="polite">{errors.password ?? 'At least 10 characters'}</p>
		</div>
		<Button variant="filled" type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create admin'}</Button>
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
