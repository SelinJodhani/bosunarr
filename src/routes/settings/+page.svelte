<script lang="ts">
	import { untrack } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import { page } from '$app/state';
	import { Button, Dialog, ConnectedButtons, snackbar } from 'm3-svelte';
	import { Icon } from 'm3-svelte';
	import ServiceDialog from '$lib/components/ServiceDialog.svelte';
	import { SERVICE_TYPES, type PublicService } from '$lib/services/catalog';
	import { iAdd, iDelete, iEdit, iLogout } from '$lib/icons';

	let { data } = $props();

	/** Host (and port) only: full URLs wrap badly on phones. */
	const host = (u: string) => {
		try {
			const x = new URL(u);
			return x.host + (x.pathname === '/' ? '' : x.pathname);
		} catch {
			return u;
		}
	};

	let dialogOpen = $state(false);
	let editing = $state<PublicService | null>(null);
	let confirmDelete = $state<PublicService | null>(null);
	let confirmOpen = $state(false);

	const welcome = $derived(page.url.searchParams.has('welcome') && data.services.length === 0);

	function add() {
		editing = null;
		dialogOpen = true;
	}
	function edit(s: PublicService) {
		editing = s;
		dialogOpen = true;
	}
	async function remove() {
		if (!confirmDelete) return;
		const name = confirmDelete.name;
		await fetch(`/api/services/${confirmDelete.id}`, { method: 'DELETE' });
		confirmOpen = false;
		await invalidateAll();
		snackbar(`Removed ${name}`, {}, true);
	}

	/* Appearance — stored per device. */
	type Appearance = { scheme: 'system' | 'light' | 'dark'; seed: string };
	const SEEDS = ['#6750a4', '#0061a4', '#006e1c', '#9c4146', '#8b5000', '#006a6a', '#7d5260'];
	let appearance = $state<Appearance>({ scheme: 'system', seed: SEEDS[0] });
	// Load the saved choice once. untrack: reading `appearance` here must not re-run this effect,
	// or it writes and re-triggers itself forever (effect_update_depth_exceeded).
	$effect(() => {
		untrack(() => {
			try {
				appearance = { ...appearance, ...JSON.parse(localStorage.getItem('appearance') || '{}') };
			} catch {}
		});
	});
	function setAppearance(patch: Partial<Appearance>) {
		appearance = { ...appearance, ...patch };
		localStorage.setItem('appearance', JSON.stringify(appearance));
		const root = document.documentElement;
		if (appearance.scheme === 'system') delete root.dataset.scheme;
		else root.dataset.scheme = appearance.scheme;
		root.style.setProperty('--m3v-source', appearance.seed);
	}
</script>

<svelte:head><title>Settings · Bosunarr</title></svelte:head>

<header><h1 class="title-large">Settings</h1></header>

{#if welcome}
	<section class="welcome">
		<h2 class="title-medium">Add your first service</h2>
		<p>Bosunarr talks to your apps with their API keys. Keys are encrypted on this server and never sent to your browser.</p>
		<Button variant="filled" onclick={add}><Icon icon={iAdd} /> Add service</Button>
	</section>
{/if}

<section class="card">
	<div class="row">
		<h2 class="title-medium">Services</h2>
		{#if data.user?.isAdmin}<Button variant="tonal" onclick={add}><Icon icon={iAdd} /> Add</Button>{/if}
	</div>
	{#if data.services.length === 0}
		<p class="body-small">No services yet.</p>
	{:else}
		<ul>
			{#each data.services as s (s.id)}
				<li class="svc">
					<a class="svc-main" href={`/services/${s.id}`}>
						<span class="dot" style:background={SERVICE_TYPES[s.type]?.color}></span>
						<span class="svc-text">
							<span class="svc-name">{s.name}</span>
							<span class="svc-sub">{SERVICE_TYPES[s.type]?.label ?? s.type} · {host(s.url)}</span>
						</span>
					</a>
					{#if data.user?.isAdmin}
						<span class="actions">
							<Button variant="text" iconType="full" aria-label={`Edit ${s.name}`} onclick={() => edit(s)}><Icon icon={iEdit} /></Button>
							<Button
								variant="text"
								iconType="full"
								aria-label={`Remove ${s.name}`}
								onclick={() => {
									confirmDelete = s;
									confirmOpen = true;
								}}><Icon icon={iDelete} /></Button
							>
						</span>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</section>

<section class="card">
	<h2 class="title-medium">Appearance</h2>
	<p class="body-small">Saved on this device.</p>
	<div class="appearance">
		<ConnectedButtons>
			{#each ['system', 'light', 'dark'] as const as scheme (scheme)}
				<Button variant={appearance.scheme === scheme ? 'filled' : 'tonal'} onclick={() => setAppearance({ scheme })}>
					{scheme[0].toUpperCase() + scheme.slice(1)}
				</Button>
			{/each}
		</ConnectedButtons>
		<div class="seeds" role="radiogroup" aria-label="Accent colour">
			{#each SEEDS as seed (seed)}
				<button
					class="seed"
					role="radio"
					aria-checked={appearance.seed === seed}
					aria-label={`Accent ${seed}`}
					style:background={seed}
					onclick={() => setAppearance({ seed })}
				></button>
			{/each}
		</div>
	</div>
</section>

<section class="card">
	<div class="row">
		<div>
			<h2 class="title-medium">Account</h2>
			<p class="body-small">Signed in as {data.user?.username}</p>
		</div>
		<form method="POST" action="/logout"><Button variant="outlined" type="submit"><Icon icon={iLogout} /> Sign out</Button></form>
	</div>
</section>

<ServiceDialog bind:open={dialogOpen} {editing} onsaved={() => invalidateAll()} />

<Dialog headline={`Remove ${confirmDelete?.name ?? ''}?`} bind:open={confirmOpen}>
	Its API key will be deleted from Bosunarr. Nothing changes in the service itself.
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" onclick={remove}>Remove</Button>
	{/snippet}
</Dialog>

<style>
	header {
		margin-bottom: 1rem;
	}
	.card,
	.welcome {
		max-width: 48rem;
		padding: 1.25rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		margin-bottom: 1rem;
	}
	.welcome {
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.welcome p {
		margin: 0.5rem 0 1rem;
	}
	.row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
	}
	ul {
		list-style: none;
		margin: 0.5rem -1rem 0;
		padding: 0;
	}
	.svc {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		border-radius: var(--radius-m);
		transition: background-color 150ms;
	}
	.svc:hover {
		background: var(--m3c-surface-container);
	}
	.svc-main {
		flex: 1 1 0;
		min-width: 0;
		display: flex;
		align-items: center;
		gap: 1rem;
		padding: 0.75rem 0 0.75rem 1rem;
		color: inherit;
		text-decoration: none;
		border-radius: var(--radius-m);
	}
	.svc-main:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
	.svc-text {
		min-width: 0;
		display: grid;
	}
	.svc-name {
		font-weight: 500;
	}
	.svc-sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.dot {
		display: block;
		flex: none;
		width: 0.75rem;
		height: 0.75rem;
		border-radius: 50%;
	}
	.actions {
		display: flex;
	}
	.appearance {
		display: grid;
		gap: 1rem;
		margin-top: 0.75rem;
	}
	.seeds {
		display: flex;
		gap: 0.75rem;
		flex-wrap: wrap;
	}
	.seed {
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		border: 3px solid transparent;
		cursor: pointer;
	}
	.seed[aria-checked='true'] {
		border-color: var(--m3c-on-surface);
	}
</style>
