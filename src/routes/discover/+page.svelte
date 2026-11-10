<script lang="ts" module>
	/** Set when a section was opened from the Discover root, so its back button can pop history instead of pushing. */
	let openedHere = false;
</script>

<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { untrack } from 'svelte';
	import { Button, Chip, ConnectedButtons, Icon, Tabs, TextField } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { HOME_URL, remember, uniq } from '$lib/discover/client';
	import type { DiscoverHome, Genre, RequestFilter, SectionId } from '$lib/discover/types';
	import Row from '$lib/discover/Row.svelte';
	import PagedGrid from '$lib/discover/PagedGrid.svelte';
	import RequestsList from '$lib/discover/RequestsList.svelte';
	import Empty from '$lib/discover/Empty.svelte';
	import { iBack, iClose, iDiscover, iSearch } from '$lib/icons';

	const FILTERS: Array<{ value: RequestFilter; label: string }> = [
		{ value: 'pending', label: 'Pending' },
		{ value: 'approved', label: 'Approved' },
		{ value: 'available', label: 'Available' },
		{ value: 'failed', label: 'Failed' },
		{ value: 'all', label: 'All' }
	];
	const TABS = [
		{ name: 'Discover', value: 'discover' },
		{ name: 'Requests', value: 'requests' }
	];

	// Everything that decides what's on screen lives in the URL, so system back, reloads and
	// returning from a detail page all land exactly where you were.
	const params = $derived(page.url.searchParams);
	const tab = $derived(params.get('tab') === 'requests' ? 'requests' : 'discover');
	const filter = $derived((FILTERS.find((f) => f.value === params.get('filter'))?.value ?? 'pending') as RequestFilter);
	const sectionId = $derived(params.get('section') as SectionId | null);
	const query = $derived((params.get('q') ?? '').trim());
	const genreType = $derived(params.get('genres') === 'tv' ? 'tv' : 'movie');

	/** Change query params; `push` makes a history entry (sections), otherwise the current one is replaced. */
	function setParams(changes: Record<string, string | null>, push = false) {
		const u = new URL(page.url);
		for (const [k, v] of Object.entries(changes)) {
			if (v) u.searchParams.set(k, v);
			else u.searchParams.delete(k);
		}
		return goto(u.pathname + u.search, { replaceState: !push, keepFocus: true, noScroll: !push });
	}

	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const hasSeerr = $derived((page.data.services ?? []).some((s: { type: string }) => s.type === 'jellyseerr' || s.type === 'overseerr'));

	const home = live<DiscoverHome>(HOME_URL, 10 * 60_000);
	const d = $derived(home.state.data);
	$effect(() => {
		for (const r of d?.rows ?? []) remember(r.items);
	});

	const rows = $derived(uniq((d?.rows ?? []).map((r) => ({ ...r, key: r.id }))));
	const genres = $derived(uniq((d?.genres ?? []).filter((g) => g.mediaType === genreType).map((g) => ({ ...g, key: `${g.mediaType}-${g.id}` }))));

	const genreTitle = (g: Genre) => `${g.name} ${g.mediaType === 'movie' ? 'movies' : 'series'}`;
	const sectionTitle = $derived.by(() => {
		if (!sectionId) return '';
		const row = d?.rows.find((r) => r.id === sectionId);
		if (row) return row.title;
		const m = /^genre-(movie|tv)-(\d+)$/.exec(sectionId);
		const g = m && d?.genres.find((x) => x.mediaType === m[1] && x.id === Number(m[2]));
		return g ? genreTitle(g) : '';
	});

	/* Search: the text field is local; the debounced term goes into ?q= (replacing the entry). */
	let text = $state(untrack(() => query));
	let pushed = untrack(() => query);
	$effect(() => {
		// The URL changed from outside (back, nav bar): mirror it in the field.
		const q = query;
		if (q !== pushed) {
			pushed = q;
			text = q;
		}
	});
	function commit(next: string) {
		if (next === pushed) return;
		pushed = next;
		setParams({ q: next || null });
	}
	$effect(() => {
		const next = text.trim();
		if (next === untrack(() => pushed)) return;
		const timer = setTimeout(() => commit(next), next ? 350 : 0);
		return () => clearTimeout(timer);
	});
	function submitSearch() {
		commit(text.trim());
		// Close the keyboard so the results aren't hidden behind it.
		(document.activeElement as HTMLElement | null)?.blur();
	}
	function clearSearch() {
		text = '';
		commit('');
	}

	function openSection(id: SectionId) {
		openedHere = true;
		setParams({ section: id, q: null }, true);
	}
	function closeSection() {
		if (openedHere) {
			openedHere = false;
			history.back();
		} else setParams({ section: null });
	}

	const serviceQs = $derived(d?.serviceId ? `&service=${d.serviceId}` : '');
	const listUrl = $derived(
		sectionId
			? `/api/discover/list?section=${encodeURIComponent(sectionId)}${serviceQs}`
			: query
				? `/api/discover/search?q=${encodeURIComponent(query)}${serviceQs}`
				: null
	);
</script>

<svelte:head><title>Discover · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Discover</h1>
	{#if d?.serviceName}<span class="body-small via">via {d.serviceName}</span>{/if}
</header>

{#if !hasSeerr && !d?.serviceId}
	<Empty icon={iDiscover} title="Nothing to discover yet" hint="Add a Jellyseerr or Overseerr service to browse and request movies and series.">
		{#snippet action()}<Button variant="tonal" href="/settings">Open Settings</Button>{/snippet}
	</Empty>
{:else}
	<Tabs items={TABS} bind:tab={() => tab, (v) => setParams({ tab: v === 'requests' ? v : null })} secondary />

	<div class="content">
		{#if tab === 'discover'}
			{#if sectionId}
				<div class="list-head">
					<Button variant="text" iconType="full" aria-label="Back to Discover" onclick={closeSection}><Icon icon={iBack} /></Button>
					<h2 class="title-medium">{sectionTitle}</h2>
				</div>
				{#if d || home.state.error}
					{#key listUrl}
						<PagedGrid url={listUrl ?? ''} emptyText="Nothing here" showType={sectionId === 'trending'} />
					{/key}
				{/if}
			{:else}
				<div class="search">
					<TextField
						label="Search movies and series"
						leadingIcon={iSearch}
						bind:value={text}
						enter={submitSearch}
						type="search"
						autocomplete="off"
						enterkeyhint="search"
						trailing={text ? { icon: iClose, onclick: clearSearch, 'aria-label': 'Clear search' } : undefined}
					/>
				</div>

				{#if listUrl}
					<div class="list-head">
						<h2 class="title-medium">Results for “{query}”</h2>
					</div>
					{#if d || home.state.error}
						{#key listUrl}
							<PagedGrid url={listUrl} emptyText={`Nothing matches “${query}”`} />
						{/key}
					{/if}
				{:else}
					{#if d?.genres.length}
						<section class="genres" aria-labelledby="genres-h">
							<div class="genre-head">
								<h2 class="title-medium" id="genres-h">Genres</h2>
								<ConnectedButtons>
									<Button variant={genreType === 'movie' ? 'filled' : 'tonal'} size="s" aria-pressed={genreType === 'movie'} onclick={() => setParams({ genres: null })}
										>Movies</Button
									>
									<Button variant={genreType === 'tv' ? 'filled' : 'tonal'} size="s" aria-pressed={genreType === 'tv'} onclick={() => setParams({ genres: 'tv' })}
										>Series</Button
									>
								</ConnectedButtons>
							</div>
							<div class="chips no-scrollbar" role="toolbar" aria-label={genreType === 'movie' ? 'Movie genres' : 'Series genres'}>
								{#each genres as g (g.key)}
									<Chip variant="assist" onclick={() => openSection(`genre-${g.mediaType}-${g.id}`)}>{g.name}</Chip>
								{/each}
							</div>
						</section>
					{/if}

					{#if !d}
						{#if home.state.error}
							<p class="error" role="alert">{home.state.error}</p>
						{:else}
							<div aria-busy="true" aria-label="Loading Discover">
								{#each { length: 3 } as _, i (i)}
									<div class="sk-row" aria-hidden="true">
										<span class="sk-title"></span>
										<div class="sk-cards">
											{#each { length: 8 } as _, j (j)}<span class="sk-card"></span>{/each}
										</div>
									</div>
								{/each}
							</div>
						{/if}
					{:else}
						{#if d.errors.length}<p class="error">Some rows failed to load: {d.errors[0]}</p>{/if}
						{#each rows as r (r.key)}
							<Row row={r} onmore={() => openSection(r.id)} />
						{/each}
						{#if home.state.error}<p class="body-small stale">Showing saved data — {home.state.error}</p>{/if}
					{/if}
				{/if}
			{/if}
		{:else}
			<div class="chips no-scrollbar filters" role="toolbar" aria-label="Request filter">
				{#each FILTERS as f (f.value)}
					<Chip variant="general" selected={filter === f.value} onclick={() => setParams({ filter: f.value === 'pending' ? null : f.value })}>{f.label}</Chip>
				{/each}
			</div>
			{#key filter}
				<RequestsList {filter} {isAdmin} />
			{/key}
		{/if}
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-height: 2.5rem;
		margin-bottom: 0.75rem;
	}
	h1 {
		margin: 0;
	}
	h2 {
		margin: 0;
	}
	.via {
		color: var(--m3c-on-surface-variant);
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
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
	.genres {
		margin-top: 1rem;
		margin-bottom: 0.5rem;
	}
	.genre-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		min-height: 2.5rem;
	}
	.chips {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		padding: 0.5rem 0;
		align-items: center;
	}
	/* Chip rows scroll edge to edge on phones. */
	@media (width < 52.5rem) {
		.chips {
			margin: 0 calc(-1 * max(1rem, env(safe-area-inset-left)));
			padding-inline: max(1rem, env(safe-area-inset-left));
			scroll-padding-inline: max(1rem, env(safe-area-inset-left));
		}
	}
	.filters {
		padding-top: 0;
		margin-bottom: 0.5rem;
	}
	.chips > :global(*) {
		flex: none;
	}
	/* 40 px tall so every chip is a comfortable tap target. */
	.chips > :global(button.m3-container) {
		height: 2.5rem;
	}
	.list-head {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		margin: 0.75rem 0 0.5rem;
		min-height: 2.5rem;
	}
	.content > .list-head:first-child {
		margin-top: -0.25rem;
	}
	.list-head :global(button) {
		margin-left: -0.5rem;
	}
	.error,
	.stale {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	/* Loading skeleton: same geometry as Row.svelte, so nothing jumps when data arrives. */
	.sk-row {
		margin-bottom: 1.25rem;
		overflow: hidden;
	}
	.sk-title {
		display: block;
		width: 8rem;
		height: 1rem;
		margin: 0.75rem 0 1rem;
		border-radius: 0.5rem;
	}
	.sk-cards {
		display: grid;
		grid-auto-flow: column;
		grid-auto-columns: 7.25rem;
		gap: 0.75rem;
		padding: 0.25rem 0 0.5rem;
	}
	@media (width >= 52.5rem) {
		.sk-cards {
			grid-auto-columns: 9.5rem;
		}
	}
	.sk-card {
		aspect-ratio: 2 / 3;
		border-radius: 0.75rem;
	}
	.sk-title,
	.sk-card {
		background: var(--m3c-surface-container-highest);
		animation: pulse 1.2s ease-in-out infinite alternate;
	}
	@keyframes pulse {
		to {
			opacity: 0.5;
		}
	}
</style>
