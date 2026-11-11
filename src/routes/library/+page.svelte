<script lang="ts" module>
	// Filters live at module scope so they survive navigating to a detail page and back.
	type Kind = 'all' | 'movie' | 'series';
	type StatusFilter = 'all' | 'missing' | 'downloaded' | 'unmonitored' | '4k';
	type SortKey = 'title' | 'added' | 'year' | 'size' | 'rating';
	const filters = $state({ q: '', kind: 'all' as Kind, status: 'all' as StatusFilter, sort: 'title' as SortKey });
</script>

<script lang="ts">
	import SortMenu from '$lib/components/SortMenu.svelte';
	import { Button, Chip, ConnectedButtons, Icon, TextField } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { bytes } from '$lib/format';
	import { LIBRARY_URL, detailHref, posterSeed, STATUS_LABEL } from '$lib/library/client';
	import type { LibraryItem, LibraryResult } from '$lib/library/types';
	import Poster from '$lib/widgets/Poster.svelte';
	import State from '$lib/widgets/State.svelte';
	import { iCheck, iClose, iEmpty, iSearch, iWarning } from '$lib/icons';

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
		{ value: 'title', text: 'Title (A–Z)' },
		{ value: 'added', text: 'Recently added' },
		{ value: 'year', text: 'Year (newest first)' },
		{ value: 'size', text: 'Size on disk (largest first)' },
		{ value: 'rating', text: 'Rating (highest first)' }
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

	const filtersOn = $derived(filters.kind !== 'all' || filters.status !== 'all');
	/** Search or filters narrow the grid: the header then counts what is shown. */
	const narrowed = $derived(filtersOn || filters.q.trim() !== '');
	function clearAll() {
		filters.q = '';
		filters.kind = 'all';
		filters.status = 'all';
	}

	/* Header summary: counts per kind, or which service is unreachable. */
	const summary = $derived.by(() => {
		const errs = q.state.data?.errors ?? [];
		const parts: Array<{ text: string; down?: string }> = [];
		if (narrowed) {
			parts.push({ text: `${items.length} of ${all.length}` });
			if (errs.length) parts.push({ text: `${errs.map((e) => e.service).join(', ')} unreachable`, down: errs.map((e) => e.error).join('\n') });
			return parts;
		}
		for (const [kind, noun] of [
			['movie', 'movies'],
			['series', 'series']
		] as const) {
			const down = errs.filter((e) => e.kind === kind);
			const n = all.filter((i) => i.kind === kind).length;
			if (down.length && !n) parts.push({ text: `${down.map((e) => e.service).join(', ')} unreachable`, down: down.map((e) => e.error).join('\n') });
			else if (n || !down.length) parts.push({ text: `${n} ${noun}` });
			if (down.length && n) parts.push({ text: `${down.map((e) => e.service).join(', ')} unreachable`, down: down.map((e) => e.error).join('\n') });
		}
		return parts;
	});

	/* ---------------- Sticky toolbar ---------------- */
	let sentinel = $state<HTMLElement>();
	let toolbarH = $state(0);

	/* ---------------- Virtualised grid (window scroll) ---------------- */
	let gridEl = $state<HTMLElement>();
	let width = $state(0);
	let scrollY = $state(0);
	let viewport = $state(800);
	let gridTop = $state(0);
	let sentinelTop = $state(0);
	const stuck = $derived(sentinelTop > 0 && scrollY > sentinelTop + 1);

	const GAP = $derived(width >= 900 ? 16 : 12);
	const CAPTION = 48;
	const minCard = $derived(width >= 900 ? 140 : width >= 560 ? 124 : 96);
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
		if (sentinel) sentinelTop = sentinel.getBoundingClientRect().top + window.scrollY;
	}
	$effect(() => {
		// Re-measure when anything above the grid can change height.
		void filters.kind;
		void filters.status;
		void summary.length;
		void toolbarH;
		void width;
		void gridEl;
		void sentinel;
		measure();
	});

	/** Start extracting the poster theme as soon as a card is touched, so the detail page opens already themed. */
	const prime = (i: LibraryItem) => i.poster && void posterSeed(i.poster);

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
	const showRail = $derived(letters.length > 3 && !filters.q);

	// Letter of the first row under the toolbar: drives the rail highlight and its aria-valuenow.
	const currentPos = $derived.by(() => {
		if (!letters.length) return 0;
		const row = Math.max(0, Math.floor((scrollY + toolbarH + 8 - gridTop) / rowH));
		// Last item of that row: a jump lands on the row holding a letter's first title, which may start with the previous letter.
		const idx = Math.min(items.length - 1, row * cols + cols - 1);
		let pos = 0;
		for (let p = 0; p < letters.length; p++) if (letters[p][1] <= idx) pos = p;
		return pos;
	});

	let railEl = $state<HTMLElement>();
	let activePos = $state<number | null>(null);
	let dragging = $state(false);
	let recentScroll = $state(false);
	let railFocus = $state(false);
	let wide = $state(false);
	const railShown = $derived(wide || dragging || recentScroll || railFocus || activePos != null);

	// Rail sits under the toolbar (wherever it currently is) and above the bottom nav.
	const navH = $derived(wide ? 16 : 72);
	const railTop = $derived(Math.max(toolbarH, gridTop - scrollY) + 8);
	const CELL_MAX = 28;
	const railH = $derived(Math.max(0, Math.min(viewport - railTop - navH, letters.length * CELL_MAX)));
	const cellH = $derived(letters.length ? railH / letters.length : 0);

	let scrollTimer: ReturnType<typeof setTimeout> | undefined;
	let lastY = 0;
	function onScroll() {
		if (Math.abs(window.scrollY - lastY) < 4) return;
		lastY = window.scrollY;
		recentScroll = true;
		clearTimeout(scrollTimer);
		scrollTimer = setTimeout(() => (recentScroll = false), 1500);
	}

	function jump(pos: number) {
		const [, index] = letters[pos] ?? [];
		if (index == null) return;
		window.scrollTo({ top: gridTop + Math.floor(index / cols) * rowH - toolbarH - 8, behavior: 'instant' });
	}
	function posAt(clientY: number) {
		const r = railEl!.getBoundingClientRect();
		return Math.min(letters.length - 1, Math.max(0, Math.floor(((clientY - r.top) / r.height) * letters.length)));
	}
	function select(pos: number, e?: PointerEvent) {
		if (pos === activePos) return;
		activePos = pos;
		jump(pos);
		// Haptic tick for touch drags only. Chrome blocks (and warns about) vibrate() before the page has had a
		// real user activation, and a first touch's pointerdown doesn't count as one yet.
		if (e?.pointerType === 'touch' && navigator.userActivation?.hasBeenActive) navigator.vibrate?.(4);
	}
	function railDown(e: PointerEvent) {
		if (!railShown && !wide) return;
		e.preventDefault();
		railEl?.setPointerCapture(e.pointerId);
		dragging = true;
		clearTimeout(railTimer);
		select(posAt(e.clientY), e);
	}
	function railMove(e: PointerEvent) {
		if (dragging) select(posAt(e.clientY), e);
	}
	function railUp() {
		dragging = false;
		releaseRail(400);
	}
	let railTimer: ReturnType<typeof setTimeout> | undefined;
	/** Hide the letter bubble shortly after the last interaction. */
	function releaseRail(delay = 700) {
		clearTimeout(railTimer);
		railTimer = setTimeout(() => (activePos = null), delay);
	}
	function railKey(e: KeyboardEvent) {
		const at = activePos ?? currentPos;
		let to: number | null = null;
		if (e.key === 'ArrowDown' || e.key === 'ArrowRight') to = at + 1;
		else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') to = at - 1;
		else if (e.key === 'PageDown') to = at + 5;
		else if (e.key === 'PageUp') to = at - 5;
		else if (e.key === 'Home') to = 0;
		else if (e.key === 'End') to = letters.length - 1;
		else if (/^[a-z#]$/i.test(e.key)) {
			const p = letters.findIndex(([l]) => l === e.key.toUpperCase());
			if (p >= 0) to = p;
		}
		if (to == null) return;
		e.preventDefault();
		select(Math.min(letters.length - 1, Math.max(0, to)));
		releaseRail(900);
	}
	const shownPos = $derived(activePos ?? currentPos);

	const subtitle = (i: LibraryItem) => {
		const parts = [i.year ?? '—'];
		if (i.episodes) parts.push(`${i.episodes.have}/${i.episodes.total} ep`);
		else if (i.sizeOnDisk) parts.push(bytes(i.sizeOnDisk, 0));
		return parts.join(' · ');
	};

	$effect(() => {
		const mq = matchMedia('(width >= 52.5rem)');
		const sync = () => (wide = mq.matches);
		sync();
		mq.addEventListener('change', sync);
		return () => mq.removeEventListener('change', sync);
	});
</script>

<svelte:head><title>Library · Bosunarr</title></svelte:head>
<svelte:window bind:scrollY bind:innerHeight={viewport} onresize={measure} onscroll={onScroll} />

<header class="head">
	<h1 class="title-large">Library</h1>
	{#if summary.length && q.state.data}
		<p class="count body-small" aria-live="polite">
			{#each summary as p, i (p.text)}
				{#if i}<span class="sep" aria-hidden="true">·</span>{/if}
				{#if p.down}
					<span class="down" title={p.down}><Icon icon={iWarning} size={14} />{p.text}</span>
				{:else}
					<span>{p.text}</span>
				{/if}
			{/each}
		</p>
	{/if}
</header>

<div class="sentinel" bind:this={sentinel} aria-hidden="true"></div>
<div class="toolbar" class:stuck bind:offsetHeight={toolbarH}>
	<div class="row">
		<div class="search">
			<TextField
				label="Search library"
				leadingIcon={iSearch}
				bind:value={filters.q}
				type="text"
				inputmode="search"
				enterkeyhint="search"
				autocomplete="off"
				spellcheck="false"
				onkeydown={(e: KeyboardEvent) => {
					if (e.key === 'Escape') filters.q = '';
					// Enter: the grid already filters as you type, so just close the keyboard to reveal the results.
					else if (e.key === 'Enter') (e.target as HTMLElement).blur();
				}}
				trailing={filters.q ? { icon: iClose, onclick: () => (filters.q = ''), 'aria-label': 'Clear search' } : undefined}
			/>
		</div>
		<SortMenu options={SORTS} bind:value={filters.sort} defaultValue="title" />
	</div>

	<div class="filters no-scrollbar">
		<div class="kinds" role="group" aria-label="Type">
			<ConnectedButtons>
				{#each KINDS as k (k.value)}
					<Button variant={filters.kind === k.value ? 'filled' : 'tonal'} aria-pressed={filters.kind === k.value} onclick={() => (filters.kind = k.value)}>
						{k.label}
					</Button>
				{/each}
			</ConnectedButtons>
		</div>
		<div class="statuses chip-row" role="group" aria-label="Status">
			{#each STATUSES as s (s.value)}
				<Chip
					variant="general"
					selected={filters.status === s.value}
					icon={filters.status === s.value ? iCheck : undefined}
					aria-pressed={filters.status === s.value}
					onclick={() => (filters.status = filters.status === s.value ? 'all' : s.value)}>{s.label}</Chip
				>
			{/each}
		</div>
	</div>
</div>

<State
	loading={q.state.loading && !q.state.data}
	error={q.state.data ? null : q.state.error}
	empty={all.length === 0}
	emptyText="Add a Sonarr or Radarr service in Settings to see your library"
>
	{#if items.length === 0}
		<div class="empty" role="status">
			<Icon icon={filters.q ? iSearch : iEmpty} size={40} />
			<p class="title-medium">{filters.q ? `No results for “${filters.q.trim()}”` : 'Nothing matches these filters'}</p>
			{#if filters.q && filtersOn}<p class="body-small">Filters are on — try searching everything.</p>{/if}
			<Button variant="tonal" onclick={clearAll}>{filters.q && filtersOn ? 'Clear search & filters' : filters.q ? 'Clear search' : 'Clear filters'}</Button>
		</div>
	{:else}
		<div class="grid-wrap" class:with-rail={showRail}>
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
						<a
							class="card"
							href={detailHref(it)}
							data-sveltekit-preload-code="hover"
							style:height="{rowH - GAP}px"
							onpointerdown={() => prime(it)}
							onpointerenter={() => prime(it)}
							onfocus={() => prime(it)}
						>
							<div class="art">
								<Poster src={it.poster} alt="" width={cardW} />
								{#if it.status === 'missing' || it.status === 'partial'}
									<span class="badge missing" title={STATUS_LABEL[it.status]}
										>{it.status === 'partial' ? `${it.episodes?.have ?? 0}/${it.episodes?.total ?? 0}` : 'Missing'}</span
									>
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
		</div>

		{#if showRail && railH > 0}
			<div
				class="rail"
				class:shown={railShown}
				bind:this={railEl}
				style:top="{railTop + (viewport - railTop - navH - railH) / 2}px"
				style:height="{railH}px"
				role="slider"
				tabindex="0"
				aria-label="Jump to letter"
				aria-orientation="vertical"
				aria-valuemin={0}
				aria-valuemax={letters.length - 1}
				aria-valuenow={shownPos}
				aria-valuetext={letters[shownPos]?.[0]}
				onpointerdown={railDown}
				onpointermove={railMove}
				onpointerup={railUp}
				onpointercancel={railUp}
				onlostpointercapture={railUp}
				onkeydown={railKey}
				onfocus={() => (railFocus = true)}
				onblur={() => (railFocus = false)}
			>
				<div class="pill">
					{#each letters as [l], p (l)}
						<span class:active={p === shownPos}>{l}</span>
					{/each}
				</div>
			</div>
			{#if activePos != null}
				<div
					class="bubble"
					aria-hidden="true"
					style:top="{Math.min(viewport - navH - 64, Math.max(railTop, railTop + (viewport - railTop - navH - railH) / 2 + (activePos + 0.5) * cellH - 32))}px"
				>
					{letters[activePos]?.[0]}
				</div>
			{/if}
		{/if}
	{/if}
</State>

{#if q.state.data && q.state.error}
	<p class="body-small stale">Showing saved data — {q.state.error}</p>
{/if}

<style>
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 2.5rem;
		margin-bottom: 0.25rem;
	}
	.count {
		margin: 0;
		min-width: 0;
		text-align: right;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.sep {
		margin-inline: 0.3em;
	}
	.down {
		color: var(--m3c-error);
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		vertical-align: bottom;
	}
	.sentinel {
		height: 0;
	}
	/* Search + filters stay reachable deep into a 1000-item grid. Full-bleed so the stuck surface spans the screen. */
	.toolbar {
		--bleed-l: max(1rem, env(safe-area-inset-left));
		--bleed-r: max(1rem, env(safe-area-inset-left));
		position: sticky;
		top: 0;
		z-index: 6;
		margin: 0 calc(-1 * var(--bleed-r)) 0 calc(-1 * var(--bleed-l));
		padding: 0.5rem var(--bleed-r) 0 var(--bleed-l);
		background: var(--m3c-surface);
		transition:
			background-color 150ms,
			box-shadow 150ms;
	}
	.toolbar.stuck {
		background: var(--m3c-surface-container);
		box-shadow:
			0 1px 2px rgb(0 0 0 / 0.18),
			0 2px 6px 2px rgb(0 0 0 / 0.08);
	}
	@media (width >= 52.5rem) {
		.toolbar {
			--bleed-l: 0.5rem;
			--bleed-r: 1.5rem;
		}
		.toolbar.stuck {
			border-radius: 0 0 var(--radius-m) var(--radius-m);
		}
	}
	.row {
		display: flex;
		gap: 0.5rem;
		align-items: center;
		max-width: 44rem;
	}
	.search {
		flex: 1;
		min-width: 0;
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.filters {
		display: flex;
		gap: 0.5rem 0.75rem;
		overflow-x: auto;
		align-items: center;
		/* Scroll edge-to-edge on phones; content still lines up with the page padding. */
		margin: 0 calc(-1 * var(--bleed-r)) 0 calc(-1 * var(--bleed-l));
		padding: 0.625rem var(--bleed-r) 0.75rem var(--bleed-l);
		scroll-padding-inline: var(--bleed-l);
	}
	.kinds,
	.statuses {
		display: flex;
		gap: 0.5rem;
		flex: none;
	}
	.kinds :global(.m3-container) {
		gap: 0.125rem;
	}
	.kinds :global(button) {
		padding-inline: 1rem;
	}
	.statuses {
		padding-left: 0.75rem;
		border-left: 1px solid var(--m3c-outline-variant);
	}
	/* Status chips: standard 32 px chips with a 40 px tap area (`.chip-row` in app.css). */
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
		overflow-wrap: anywhere;
	}
	.empty .title-medium {
		color: var(--m3c-on-surface);
	}
	.empty :global(button) {
		margin-top: 0.5rem;
	}
	.stale {
		margin: 0.75rem 0;
		color: var(--m3c-error);
	}
	.grid-wrap {
		position: relative;
	}
	/* The rail gets its own narrow gutter so it never covers posters or their 4K badges. It is reserved
	   whenever the rail is available (not only while it shows), so the grid never reflows mid-scroll. */
	.grid-wrap.with-rail {
		padding-right: 0.875rem;
	}
	@media (width >= 52.5rem) {
		.grid-wrap.with-rail {
			padding-right: 1.75rem;
		}
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
		border-radius: 0.75rem;
		outline-offset: 2px;
		-webkit-touch-callout: none;
	}
	.card:focus-visible {
		outline: 2px solid var(--m3c-primary);
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
	@media (hover: hover) {
		.card:hover .art {
			transform: scale(1.02);
		}
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
		font-variant-numeric: tabular-nums;
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
	/* A–Z fast scroller: 44 px wide hit area; letters share the height evenly so a touch maps to the letter under it. */
	/* Phones: the pill hugs the screen edge, inside the page gutter plus the reserved strip; the hit area
	   still reaches 44 px in from the edge. */
	.rail {
		position: fixed;
		right: env(safe-area-inset-right);
		width: 2.75rem;
		z-index: 5;
		display: flex;
		justify-content: flex-end;
		padding-right: 0.25rem;
		box-sizing: border-box;
		touch-action: none;
		user-select: none;
		-webkit-user-select: none;
		outline: none;
		opacity: 0;
		pointer-events: none;
		transition: opacity 200ms;
	}
	.rail.shown {
		opacity: 1;
		pointer-events: auto;
	}
	.rail:focus-visible {
		opacity: 1;
		pointer-events: auto;
	}
	.pill {
		width: 1.25rem;
		height: 100%;
		display: flex;
		flex-direction: column;
		border-radius: 0.75rem;
		background: color-mix(in oklab, var(--m3c-surface-container-high) 92%, transparent);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.2);
	}
	.rail:focus-visible .pill {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.pill span {
		flex: 1;
		min-height: 0;
		display: grid;
		place-items: center;
		font-size: 0.6875rem;
		font-weight: 600;
		color: var(--m3c-on-surface-variant);
	}
	.pill span.active {
		color: var(--m3c-primary);
		font-weight: 800;
	}
	@media (width >= 52.5rem) {
		.rail {
			right: 0.25rem;
		}
		.pill {
			background: transparent;
			box-shadow: none;
		}
	}
	.bubble {
		position: fixed;
		right: 3.5rem;
		width: 4rem;
		height: 4rem;
		border-radius: 2rem 2rem 0.5rem 2rem;
		display: grid;
		place-items: center;
		font-size: 2rem;
		font-weight: 500;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
		box-shadow: 0 2px 8px rgb(0 0 0 / 0.25);
		z-index: 7;
		pointer-events: none;
	}
</style>
