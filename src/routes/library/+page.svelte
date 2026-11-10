<script lang="ts" module>
	// Filters live at module scope so they survive navigating to a detail page and back.
	type Kind = 'all' | 'movie' | 'series';
	type StatusFilter = 'all' | 'missing' | 'downloaded' | 'unmonitored' | '4k';
	type SortKey = 'title' | 'added' | 'year' | 'size' | 'rating';
	const filters = $state({ q: '', kind: 'all' as Kind, status: 'all' as StatusFilter, sort: 'title' as SortKey });
</script>

<script lang="ts">
	import SortMenu from '$lib/components/SortMenu.svelte';
	import { Button, Chip, Icon, TextField } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { bytes } from '$lib/format';
	import { LIBRARY_URL, detailHref, STATUS_LABEL } from '$lib/library/client';
	import type { LibraryItem, LibraryResult } from '$lib/library/types';
	import Poster from '$lib/widgets/Poster.svelte';
	import State from '$lib/widgets/State.svelte';
	import { iClose, iMovie, iSearch, iTv } from '$lib/icons';

	const q = live<LibraryResult>(LIBRARY_URL, 5 * 60_000);
	const all = $derived(q.state.data?.items ?? []);

	const KINDS: Array<{ value: Kind; label: string }> = [
		{ value: 'all', label: 'All' },
		{ value: 'movie', label: 'Movies' },
		{ value: 'series', label: 'Series' }
	];
	const STATUSES: Array<{ value: StatusFilter; label: string }> = [
		{ value: 'missing', label: 'Missing' },
		{ value: 'downloaded', label: 'Downloaded' },
		{ value: 'unmonitored', label: 'Unmonitored' },
		{ value: '4k', label: '4K' }
	];
	const SORTS = [
		{ value: 'title', text: 'Title' },
		{ value: 'added', text: 'Recently added' },
		{ value: 'year', text: 'Year' },
		{ value: 'size', text: 'Size on disk' },
		{ value: 'rating', text: 'Rating' }
	];

	const matchesStatus = (i: LibraryItem) => {
		switch (filters.status) {
			case 'missing':
				return i.status === 'missing' || i.status === 'partial';
			case 'downloaded':
				return i.status === 'downloaded';
			case 'unmonitored':
				return !i.monitored;
			case '4k':
				return i.is4k;
			default:
				return true;
		}
	};

	const items = $derived.by(() => {
		const needle = filters.q.trim().toLowerCase();
		const list = all.filter(
			(i) =>
				(filters.kind === 'all' || i.kind === filters.kind) &&
				matchesStatus(i) &&
				(!needle || i.title.toLowerCase().includes(needle) || String(i.year ?? '') === needle)
		);
		const by: Record<SortKey, (a: LibraryItem, b: LibraryItem) => number> = {
			title: (a, b) => a.sortTitle.localeCompare(b.sortTitle),
			added: (a, b) => (b.added ?? '').localeCompare(a.added ?? ''),
			year: (a, b) => (b.year ?? 0) - (a.year ?? 0),
			size: (a, b) => b.sizeOnDisk - a.sizeOnDisk,
			rating: (a, b) => (b.rating ?? 0) - (a.rating ?? 0)
		};
		return filters.sort === 'title' ? list : [...list].sort(by[filters.sort]);
	});

	const counts = $derived({
		movies: all.filter((i) => i.kind === 'movie').length,
		series: all.filter((i) => i.kind === 'series').length
	});

	/* ---------------- Virtualised grid (window scroll) ---------------- */
	const GAP = 12;
	const CAPTION = 48;
	let gridEl = $state<HTMLElement>();
	let width = $state(0);
	let scrollY = $state(0);
	let viewport = $state(800);
	let gridTop = $state(0);

	const minCard = $derived(width >= 900 ? 156 : width >= 560 ? 132 : 104);
	const cols = $derived(Math.max(2, Math.floor((width + GAP) / (minCard + GAP))));
	const cardW = $derived(width ? (width - GAP * (cols - 1)) / cols : minCard);
	const rowH = $derived(Math.round(cardW * 1.5 + CAPTION + GAP));
	const rows = $derived(Math.ceil(items.length / cols));
	const OVERSCAN = 3;
	const first = $derived(Math.max(0, Math.floor((scrollY - gridTop) / rowH) - OVERSCAN));
	const last = $derived(Math.min(rows, Math.ceil((scrollY - gridTop + viewport) / rowH) + OVERSCAN));
	const visible = $derived(items.slice(first * cols, last * cols));

	function measure() {
		if (gridEl) gridTop = gridEl.getBoundingClientRect().top + window.scrollY;
	}
	$effect(() => {
		// Re-measure when anything above the grid can change height.
		void filters.kind;
		void filters.status;
		void q.state.error;
		void width;
		measure();
	});

	/* ---------------- A–Z rail ---------------- */
	const letterOf = (i: LibraryItem) => {
		const c = i.sortTitle[0]?.toUpperCase() ?? '#';
		return c >= 'A' && c <= 'Z' ? c : '#';
	};
	const letters = $derived.by(() => {
		if (filters.sort !== 'title') return [];
		const firstIndex = new Map<string, number>();
		items.forEach((i, idx) => {
			const l = letterOf(i);
			if (!firstIndex.has(l)) firstIndex.set(l, idx);
		});
		return [...firstIndex.entries()];
	});
	let activeLetter = $state<string | null>(null);

	function jump(index: number) {
		window.scrollTo({ top: gridTop + Math.floor(index / cols) * rowH - 8, behavior: 'instant' });
	}
	function railPointer(e: PointerEvent) {
		if (e.type === 'pointerdown') (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		else if (!(e.buttons & 1)) return;
		const rail = e.currentTarget as HTMLElement;
		const r = rail.getBoundingClientRect();
		const pos = Math.min(letters.length - 1, Math.max(0, Math.floor(((e.clientY - r.top) / r.height) * letters.length)));
		const [l, idx] = letters[pos] ?? [];
		if (l != null && l !== activeLetter) {
			activeLetter = l;
			jump(idx);
		}
		releaseRail();
	}
	let railTimer: ReturnType<typeof setTimeout> | undefined;
	/** Hide the letter bubble shortly after the last touch (pointerup isn't reliable on every device). */
	function releaseRail(delay = 700) {
		clearTimeout(railTimer);
		railTimer = setTimeout(() => (activeLetter = null), delay);
	}

	const subtitle = (i: LibraryItem) => {
		const parts = [i.year ?? '—'];
		if (i.episodes) parts.push(`${i.episodes.have}/${i.episodes.total} ep`);
		else if (i.sizeOnDisk) parts.push(bytes(i.sizeOnDisk, 0));
		return parts.join(' · ');
	};
</script>

<svelte:head><title>Library · Bosunarr</title></svelte:head>
<svelte:window bind:scrollY bind:innerHeight={viewport} onresize={measure} />

<header>
	<div>
		<h1 class="title-large">Library</h1>
		{#if all.length}
			<p class="body-small">{counts.movies} movies · {counts.series} series</p>
		{/if}
	</div>
</header>

<div class="toolbar">
	<div class="search">
		<TextField
			label="Search library"
			leadingIcon={iSearch}
			bind:value={filters.q}
			type="search"
			autocomplete="off"
			trailing={filters.q ? { icon: iClose, onclick: () => (filters.q = ''), 'aria-label': 'Clear search' } : undefined}
		/>
	</div>
	<SortMenu options={SORTS} bind:value={filters.sort} />
</div>

<div class="chips" role="toolbar" aria-label="Filters">
	{#each KINDS as k (k.value)}
		<Chip
			variant="general"
			selected={filters.kind === k.value}
			icon={k.value === 'movie' ? iMovie : k.value === 'series' ? iTv : undefined}
			onclick={() => (filters.kind = k.value)}>{k.label}</Chip
		>
	{/each}
	<span class="divider" aria-hidden="true"></span>
	{#each STATUSES as s (s.value)}
		<Chip variant="general" selected={filters.status === s.value} onclick={() => (filters.status = filters.status === s.value ? 'all' : s.value)}
			>{s.label}</Chip
		>
	{/each}
</div>

{#if q.state.data?.errors?.length}
	<p class="partial body-small">
		Couldn't reach {q.state.data.errors.map((e) => e.service).join(', ')} — showing what's available.
	</p>
{/if}

<State
	loading={q.state.loading && !q.state.data}
	error={q.state.data ? null : q.state.error}
	empty={items.length === 0}
	emptyText={all.length ? 'Nothing matches these filters' : 'Add a Sonarr or Radarr service in Settings to see your library'}
>
	<div class="grid-wrap">
		<div
			class="grid"
			bind:this={gridEl}
			bind:clientWidth={width}
			style:height="{rows * rowH - GAP}px"
			style:--cols={cols}
			style:--gap="{GAP}px"
		>
			<div class="window" style:transform="translateY({first * rowH}px)">
				{#each visible as it (it.key)}
					<a class="card" href={detailHref(it)} data-sveltekit-preload-code="hover" style:height="{rowH - GAP}px">
						<div class="art">
							<Poster src={it.poster} alt="" width={cardW} />
							{#if it.status === 'missing' || it.status === 'partial'}
								<span class="badge missing" title={STATUS_LABEL[it.status]}>{it.status === 'partial' ? `${it.episodes?.have ?? 0}/${it.episodes?.total ?? 0}` : 'Missing'}</span>
							{:else if it.status === 'upcoming'}
								<span class="badge soon" title={STATUS_LABEL.upcoming}>Soon</span>
							{/if}
							{#if it.is4k}<span class="badge uhd">4K</span>{/if}
							{#if !it.monitored}<span class="unmon" title="Unmonitored"></span>{/if}
						</div>
						<span class="t">{it.title}</span>
						<span class="s">{subtitle(it)}</span>
					</a>
				{/each}
			</div>
		</div>

		{#if letters.length > 3 && !filters.q}
			<div
				class="rail"
				aria-hidden="true"
				onpointerdown={railPointer}
				onpointermove={railPointer}
				onpointerup={() => releaseRail(250)}
				onpointercancel={() => releaseRail(0)}
				onlostpointercapture={() => releaseRail(250)}
			>
				{#each letters as [l] (l)}
					<span class:active={activeLetter === l}>{l}</span>
				{/each}
			</div>
			{#if activeLetter}<div class="bubble" aria-hidden="true">{activeLetter}</div>{/if}
		{/if}
	</div>
</State>

{#if q.state.data && q.state.error}
	<p class="body-small stale">Showing saved data — {q.state.error}</p>
{/if}

<style>
	header {
		margin-bottom: 0.75rem;
	}
	header p {
		margin: 0.125rem 0 0;
	}
	.toolbar {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}
	.search {
		flex: 1;
		min-width: 0;
	}
	.search :global(.m3-container) {
		width: 100%;
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
	.partial,
	.stale {
		margin: 0 0 0.75rem;
		color: var(--m3c-error);
	}
	.grid-wrap {
		position: relative;
		padding-right: 1.25rem;
	}
	.grid {
		position: relative;
		contain: strict;
		min-height: 1px;
	}
	.window {
		display: grid;
		grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
		gap: var(--gap);
		will-change: transform;
	}
	.card {
		display: flex;
		flex-direction: column;
		min-width: 0;
		color: inherit;
		text-decoration: none;
		border-radius: var(--radius-m);
		outline-offset: 2px;
	}
	.art {
		position: relative;
		border-radius: 0.75rem;
		overflow: hidden;
		transition: transform 150ms;
	}
	.art :global(.poster) {
		width: 100% !important;
		height: auto !important;
		aspect-ratio: 2 / 3;
		border-radius: 0.75rem;
	}
	.card:hover .art,
	.card:focus-visible .art {
		transform: scale(1.02);
	}
	.card:active .art {
		transform: scale(0.98);
	}
	.t,
	.s {
		overflow: hidden;
		white-space: nowrap;
		text-overflow: ellipsis;
	}
	.t {
		margin-top: 0.375rem;
		font-size: 0.8125rem;
		font-weight: 500;
		line-height: 1.125rem;
	}
	.s {
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.badge {
		position: absolute;
		top: 0.375rem;
		left: 0.375rem;
		padding: 0.125rem 0.4rem;
		border-radius: 0.5rem;
		font-size: 0.6875rem;
		font-weight: 600;
		line-height: 1rem;
	}
	.badge.missing {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.badge.soon {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.badge.uhd {
		left: auto;
		right: 0.375rem;
		background: var(--m3c-inverse-surface);
		color: var(--m3c-inverse-on-surface);
	}
	.unmon {
		position: absolute;
		inset: 0;
		background: color-mix(in oklab, var(--m3c-surface) 45%, transparent);
	}
	.rail {
		position: fixed;
		right: max(0.125rem, env(safe-area-inset-right));
		top: 50%;
		transform: translateY(-50%);
		display: flex;
		flex-direction: column;
		z-index: 5;
		padding: 0.25rem 0;
		border-radius: 1rem;
		touch-action: none;
		user-select: none;
	}
	.rail span {
		cursor: pointer;
		font-size: 0.625rem;
		font-weight: 600;
		line-height: 0.9rem;
		width: 1.125rem;
		text-align: center;
		color: var(--m3c-on-surface-variant);
	}
	.rail span.active,
	.rail span:hover {
		color: var(--m3c-primary);
	}
	.bubble {
		position: fixed;
		right: 2.5rem;
		top: 50%;
		transform: translateY(-50%);
		width: 4rem;
		height: 4rem;
		border-radius: 1.25rem;
		display: grid;
		place-items: center;
		font-size: 2rem;
		font-weight: 500;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
		z-index: 6;
	}
	@media (width < 52.5rem) {
		.rail {
			top: calc(50% - 2.5rem);
		}
	}
</style>
