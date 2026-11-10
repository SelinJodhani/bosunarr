<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import { page } from '$app/state';
	import { Button, Dialog, snackbar } from 'm3-svelte';
	import { Icon } from 'm3-svelte';
	import ServiceDialog from '$lib/components/ServiceDialog.svelte';
	import { SERVICE_TYPES, type PublicService } from '$lib/services/catalog';
	import { iAdd, iCheck, iDelete, iEdit, iLogout, iServices } from '$lib/icons';

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

	const welcome = $derived(page.url.searchParams.has('welcome'));
	/** "Jellyseerr / Seerr" → "Jellyseerr": what a row is called by default. */
	const typeName = (s: PublicService) => (SERVICE_TYPES[s.type]?.label ?? s.type).split(' / ')[0];

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
	function saved(name: string, added: boolean) {
		invalidateAll();
		snackbar(added ? `Added ${name}` : `Saved ${name}`, {}, true);
	}

	/* Appearance — stored per device. */
	type Scheme = 'system' | 'light' | 'dark';
	type Appearance = { scheme: Scheme; seed: string };
	const SCHEMES: Scheme[] = ['system', 'light', 'dark'];
	const SEEDS = [
		{ value: '#6750a4', name: 'Purple' },
		{ value: '#0061a4', name: 'Blue' },
		{ value: '#006e1c', name: 'Green' },
		{ value: '#9c4146', name: 'Red' },
		{ value: '#8b5000', name: 'Orange' },
		{ value: '#006a6a', name: 'Teal' },
		{ value: '#7d5260', name: 'Rose' }
	];
	let appearance = $state<Appearance>({ scheme: 'system', seed: SEEDS[0].value });
	/* The saved choice lives in localStorage, which the server can't see. Until it's read, the
	   scheme selection is drawn from <html data-scheme> (set by app.html before first paint) and no
	   swatch is marked, so nothing ever shows the wrong state. */
	let loaded = $state(false);
	onMount(() => {
		try {
			appearance = { ...appearance, ...JSON.parse(localStorage.getItem('appearance') || '{}') };
		} catch {}
		loaded = true;
	});
	function setAppearance(patch: Partial<Appearance>) {
		appearance = { ...appearance, ...patch };
		try {
			localStorage.setItem('appearance', JSON.stringify(appearance));
		} catch {}
		const root = document.documentElement;
		if (appearance.scheme === 'system') delete root.dataset.scheme;
		else root.dataset.scheme = appearance.scheme;
		root.style.setProperty('--m3v-source', appearance.seed);
	}
</script>

<svelte:head><title>Settings · Bosunarr</title></svelte:head>

<header><h1 class="title-large">Settings</h1></header>

<section class="card">
	<div class="row">
		<h2 class="title-medium">Services</h2>
		{#if data.user?.isAdmin && data.services.length > 0}<Button variant="tonal" onclick={add}><Icon icon={iAdd} /> Add</Button>{/if}
	</div>
	{#if data.services.length === 0}
		<div class="empty">
			<Icon icon={iServices} size={40} />
			<p class="title-medium">{welcome ? 'Add your first service' : 'No services yet'}</p>
			<p class="body-small">Bosunarr talks to your apps with their API keys. Keys are encrypted on this server and never sent to your browser.</p>
			{#if data.user?.isAdmin}<Button variant="tonal" onclick={add}><Icon icon={iAdd} /> Add service</Button>{/if}
		</div>
	{:else}
		<ul>
			{#each data.services as s (s.id)}
				{@const label = typeName(s)}
				<li class="svc">
					<a class="svc-main" href={`/services/${s.id}`}>
						<span class="badge" style:--c={SERVICE_TYPES[s.type]?.color} aria-hidden="true">{label[0]}</span>
						<span class="svc-text">
							<span class="svc-name">{s.name}</span>
							<span class="svc-sub">{s.name.trim().toLowerCase() === label.toLowerCase() ? '' : `${label} · `}{host(s.url)}</span>
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
	<p class="body-small sub">Saved on this device.</p>
	<div class="appearance">
		<div class="segmented" class:loaded role="radiogroup" aria-label="Theme">
			{#each SCHEMES as scheme (scheme)}
				<button
					type="button"
					role="radio"
					data-scheme={scheme}
					aria-checked={loaded && appearance.scheme === scheme}
					onclick={() => setAppearance({ scheme })}
				>
					<span class="check"><Icon icon={iCheck} size={18} /></span>
					{scheme[0].toUpperCase() + scheme.slice(1)}
				</button>
			{/each}
		</div>
		<div class="seeds" role="radiogroup" aria-label="Accent colour">
			{#each SEEDS as seed (seed.value)}
				<button
					type="button"
					class="seed"
					role="radio"
					aria-checked={loaded && appearance.seed === seed.value}
					aria-label={seed.name}
					title={seed.name}
					style:--seed={seed.value}
					onclick={() => setAppearance({ seed: seed.value })}
				><span></span></button>
			{/each}
		</div>
	</div>
</section>

<section class="card">
	<div class="row">
		<div>
			<h2 class="title-medium">Account</h2>
			<p class="body-small sub">Signed in as {data.user?.username}</p>
		</div>
		<form method="POST" action="/logout"><Button variant="outlined" type="submit"><Icon icon={iLogout} /> Sign out</Button></form>
	</div>
</section>

<ServiceDialog bind:open={dialogOpen} {editing} onsaved={saved} />

<Dialog headline={`Remove ${confirmDelete?.name ?? ''}?`} bind:open={confirmOpen}>
	Its API key will be deleted from Bosunarr. Nothing changes in the service itself.
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" data-danger onclick={remove}>Remove</Button>
	{/snippet}
</Dialog>

<style>
	header {
		display: flex;
		align-items: center;
		min-height: 2.5rem;
		margin-bottom: 0.75rem;
	}
	h1,
	h2 {
		margin: 0;
	}
	.card {
		max-width: 48rem;
		padding: 1rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		margin-bottom: 1rem;
	}
	@media (width >= 37.5rem) {
		.card {
			padding: 1.25rem;
		}
	}
	.sub {
		margin: 0.125rem 0 0;
		color: var(--m3c-on-surface-variant);
	}
	.row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 2.5rem;
	}
	.empty {
		display: grid;
		justify-items: center;
		gap: 0.5rem;
		padding: 2.5rem 1rem;
		text-align: center;
		color: var(--m3c-on-surface-variant);
	}
	.empty p {
		margin: 0;
		max-width: 22rem;
	}
	.empty .title-medium {
		color: var(--m3c-on-surface);
	}
	.empty :global(button) {
		margin-top: 0.5rem;
	}
	ul {
		list-style: none;
		margin: 0.5rem -0.5rem 0;
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
		gap: 0.75rem;
		min-height: 3.5rem;
		padding: 0.5rem 0 0.5rem 0.5rem;
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
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.svc-sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	/* Type badge: brand tint + initial. A square, so it never reads as a health dot. */
	.badge {
		display: grid;
		place-items: center;
		flex: none;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 0.625rem;
		background: color-mix(in oklab, var(--c) 18%, var(--m3c-surface-container-highest));
		color: color-mix(in oklab, var(--c) 70%, var(--m3c-on-surface));
		font-weight: 600;
		font-size: 0.9375rem;
		text-transform: uppercase;
	}
	.actions {
		display: flex;
		flex: none;
	}
	.appearance {
		display: grid;
		gap: 1rem;
		margin-top: 0.75rem;
	}

	/* M3 single-select segmented button. */
	.segmented {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		max-width: 22rem;
		border: 1px solid var(--m3c-outline);
		border-radius: 1.25rem;
		overflow: hidden;
	}
	.segmented button {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.375rem;
		min-width: 0;
		min-height: 2.5rem;
		padding: 0 0.5rem;
		border: none;
		background: transparent;
		color: var(--m3c-on-surface);
		font: inherit;
		font-size: 0.875rem;
		font-weight: 500;
		letter-spacing: 0.00625rem;
		cursor: pointer;
		transition: background-color 150ms;
	}
	.segmented button + button {
		border-left: 1px solid var(--m3c-outline);
	}
	.segmented button:hover {
		background: color-mix(in oklab, var(--m3c-on-surface) 8%, transparent);
	}
	.segmented button:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -3px;
	}
	.check {
		display: none;
	}
	.segmented.loaded button[aria-checked='true'],
	:global(html:not([data-scheme])) .segmented:not(.loaded) button[data-scheme='system'],
	:global(html[data-scheme='light']) .segmented:not(.loaded) button[data-scheme='light'],
	:global(html[data-scheme='dark']) .segmented:not(.loaded) button[data-scheme='dark'] {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.segmented.loaded button[aria-checked='true'] .check,
	:global(html:not([data-scheme])) .segmented:not(.loaded) button[data-scheme='system'] .check,
	:global(html[data-scheme='light']) .segmented:not(.loaded) button[data-scheme='light'] .check,
	:global(html[data-scheme='dark']) .segmented:not(.loaded) button[data-scheme='dark'] .check {
		display: contents;
	}

	/* Seven swatches in one row down to 320 px: 40 px tap targets, 32 px visible circles. */
	.seeds {
		display: flex;
		justify-content: space-between;
		max-width: 22rem;
	}
	.seed {
		display: grid;
		place-items: center;
		flex: none;
		width: 2.5rem;
		height: 2.5rem;
		padding: 0;
		border: none;
		border-radius: 50%;
		background: transparent;
		cursor: pointer;
	}
	.seed span {
		width: 2rem;
		height: 2rem;
		border-radius: 50%;
		background: var(--seed);
		transition: box-shadow 150ms;
	}
	.seed[aria-checked='true'] span {
		box-shadow:
			0 0 0 2px var(--m3c-surface-container-low),
			0 0 0 4px var(--m3c-on-surface);
	}
	.seed:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
</style>
