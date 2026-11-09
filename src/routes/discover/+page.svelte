<script lang="ts" module>
	import type { RequestFilter, SectionId } from '$lib/discover/types';
	// Module scope: survives going to a detail page and back.
	const ui = $state({
		tab: 'discover',
		q: '',
		/** Debounced search term actually queried. */
		query: '',
		section: null as { id: SectionId; title: string } | null,
		genreType: 'movie' as 'movie' | 'tv',
		filter: 'pending' as RequestFilter
	});
</script>

<script lang="ts">
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { Button, Chip, Icon, LoadingIndicator, Tabs, TextField } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { HOME_URL, remember, uniq } from '$lib/discover/client';
	import type { DiscoverHome, Genre } from '$lib/discover/types';
	import Row from '$lib/discover/Row.svelte';
	import PagedGrid from '$lib/discover/PagedGrid.svelte';
	import RequestsList from '$lib/discover/RequestsList.svelte';
	import { iBack, iClose, iMovie, iSearch, iTv } from '$lib/icons';

	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const hasSeerr = $derived((page.data.services ?? []).some((s: { type: string }) => s.type === 'jellyseerr' || s.type === 'overseerr'));

	// Deep link from the Dashboard widget: /discover?tab=requests
	$effect(() => {
		const t = page.url.searchParams.get('tab');
		if (t === 'requests' || t === 'discover') ui.tab = t;
	});

	const home = live<DiscoverHome>(HOME_URL, 10 * 60_000);
	const d = $derived(home.state.data);
	$effect(() => {
		for (const r of d?.rows ?? []) remember(r.items);
	});

	const rows = $derived(uniq((d?.rows ?? []).map((r) => ({ ...r, key: r.id }))));
	const genres = $derived(uniq((d?.genres ?? []).filter((g) => g.mediaType === ui.genreType).map((g) => ({ ...g, key: `${g.mediaType}-${g.id}` }))));

	/* Search with debounce */
	$effect(() => {
		const next = ui.q.trim();
		if (next === untrack(() => ui.query)) return;
		const timer = setTimeout(() => (ui.query = next), next ? 350 : 0);
		return () => clearTimeout(timer);
	});
	function clearSearch() {
		ui.q = '';
		ui.query = '';
	}

	function openSection(id: SectionId, title: string) {
		ui.section = { id, title };
		window.scrollTo({ top: 0 });
	}
	const openGenre = (g: Genre) => openSection(`genre-${g.mediaType}-${g.id}`, `${g.name} ${g.mediaType === 'movie' ? 'movies' : 'series'}`);

	const serviceQs = $derived(d?.serviceId ? `&service=${d.serviceId}` : '');
	const listUrl = $derived(
		ui.query
			? `/api/discover/search?q=${encodeURIComponent(ui.query)}${serviceQs}`
			: ui.section
				? `/api/discover/list?section=${ui.section.id}${serviceQs}`
				: null
	);

	const TABS = [
		{ name: 'Discover', value: 'discover' },
		{ name: 'Requests', value: 'requests' }
	];
	const FILTERS: Array<{ value: RequestFilter; label: string }> = [
		{ value: 'pending', label: 'Pending' },
		{ value: 'approved', label: 'Approved' },
		{ value: 'available', label: 'Available' },
		{ value: 'failed', label: 'Failed' },
		{ value: 'all', label: 'All' }
	];
</script>

<svelte:head><title>Discover · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Discover</h1>
	{#if d?.serviceName}<span class="body-small via">via {d.serviceName}</span>{/if}
</header>

{#if !hasSeerr && !d?.serviceId}
	<p class="empty">Add a Jellyseerr or Overseerr service in <a href="/settings">Settings</a> to discover and request movies and series.</p>
{:else}
	<Tabs items={TABS} bind:tab={ui.tab} secondary />

	<div class="content">
		{#if ui.tab === 'discover'}
			<div class="search">
				<TextField
					label="Search movies and series"
					leadingIcon={iSearch}
					bind:value={ui.q}
					enter={() => (ui.query = ui.q.trim())}
					type="search"
					autocomplete="off"
					enterkeyhint="search"
					trailing={ui.q ? { icon: iClose, onclick: clearSearch, 'aria-label': 'Clear search' } : undefined}
				/>
			</div>

			{#if listUrl}
				<div class="list-head">
					{#if !ui.query && ui.section}
						<Button variant="text" iconType="full" aria-label="Back to Discover" onclick={() => (ui.section = null)}><Icon icon={iBack} /></Button>
						<h2 class="title-medium">{ui.section.title}</h2>
					{:else}
						<h2 class="title-medium">Results for “{ui.query}”</h2>
					{/if}
				</div>
				{#key listUrl}
					<PagedGrid url={listUrl} emptyText={ui.query ? `Nothing matches “${ui.query}”` : 'Nothing here'} />
				{/key}
			{:else}
				{#if d?.genres.length}
					<div class="chips" role="toolbar" aria-label="Genres">
						<Chip variant="general" icon={iMovie} selected={ui.genreType === 'movie'} onclick={() => (ui.genreType = 'movie')}>Movies</Chip>
						<Chip variant="general" icon={iTv} selected={ui.genreType === 'tv'} onclick={() => (ui.genreType = 'tv')}>Series</Chip>
						<span class="divider" aria-hidden="true"></span>
						{#each genres as g (g.key)}
							<Chip variant="assist" onclick={() => openGenre(g)}>{g.name}</Chip>
						{/each}
					</div>
				{/if}

				{#if !d}
					{#if home.state.error}
						<p class="error" role="alert">{home.state.error}</p>
					{:else}
						<div class="center"><LoadingIndicator aria-label="Loading Discover" /></div>
					{/if}
				{:else}
					{#if d.errors.length}<p class="error">Some rows failed to load: {d.errors[0]}</p>{/if}
					{#each rows as r (r.key)}
						<Row row={r} onmore={() => openSection(r.id, r.title)} />
					{/each}
					{#if home.state.error}<p class="body-small stale">Showing saved data — {home.state.error}</p>{/if}
				{/if}
			{/if}
		{:else}
			<div class="chips" role="toolbar" aria-label="Request filter">
				{#each FILTERS as f (f.value)}
					<Chip variant="general" selected={ui.filter === f.value} onclick={() => (ui.filter = f.value)}>{f.label}</Chip>
				{/each}
			</div>
			{#key ui.filter}
				<RequestsList filter={ui.filter} {isAdmin} />
			{/key}
		{/if}
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		margin-bottom: 0.5rem;
	}
	.via {
		color: var(--m3c-on-surface-variant);
	}
	.content {
		padding-top: 1rem;
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.search :global(input::-webkit-search-cancel-button) {
		display: none;
	}
	@media (width >= 52.5rem) {
		.search {
			max-width: 36rem;
		}
	}
	.chips {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		padding: 0.75rem 0;
		scrollbar-width: none;
		align-items: center;
	}
	.chips > :global(*) {
		flex: none;
	}
	.divider {
		width: 1px;
		height: 1.5rem;
		background: var(--m3c-outline-variant);
		margin: 0 0.25rem;
	}
	.list-head {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		margin: 0.75rem 0 0.5rem;
		min-height: 2.5rem;
	}
	.list-head :global(button) {
		margin-left: -0.5rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error,
	.stale {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
</style>
