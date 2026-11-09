<script lang="ts">
	import { enhance } from '$app/forms';
	import { Button, TextField } from 'm3-svelte';
	import AuthCard from '$lib/components/AuthCard.svelte';

	let { form } = $props();
	let busy = $state(false);
</script>

<svelte:head><title>Sign in · Bosunarr</title></svelte:head>

<AuthCard title="Sign in" subtitle="Bosunarr runs your *arr crew.">
	<form
		method="POST"
		use:enhance={() => {
			busy = true;
			return async ({ update }) => {
				await update();
				busy = false;
			};
		}}
	>
		<TextField label="Username" name="username" autocomplete="username" required value={form && 'username' in form ? String(form.username ?? '') : ''} />
		<TextField label="Password" name="password" type="password" autocomplete="current-password" required />
		{#if form?.error}<p class="error" role="alert">{form.error}</p>{/if}
		<Button variant="filled" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</Button>
	</form>
</AuthCard>

<style>
	form {
		display: grid;
		gap: 1rem;
	}
	.error {
		margin: 0;
		color: var(--m3c-error);
	}
</style>
