<script lang="ts" module>
	// Module scope: the selected day and filters survive opening a detail page and coming back.
	type Filters = { episodes: boolean; movies: boolean; monitoredOnly: boolean; hideDownloaded: boolean };
	const DEFAULT_FILTERS: Filters = { episodes: true, movies: true, monitoredOnly: false, hideDownloaded: false };
	const view = $state({ selected: '', filters: { ...DEFAULT_FILTERS } });
	let filtersLoaded = false;
</script>

<script lang="ts">
	import { onMount, tick } from 'svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { afterNavigate } from '$app/navigation';
	import { fly } from 'svelte/transition';
	import { Button, Chip, Icon } from 'm3-svelte';
	import {
		addDays,
		addMonths,
		byDay,
		calendarFeed,
		calendarUrl,
		dayKey,
		entryDay,
		fromKey,
		monthGrid,
		prefetch,
		startOfWeek,
		weekStartDay
	} from '$lib/calendar/client.svelte';
	import Agenda from '$lib/calendar/Agenda.svelte';
	import WeekStrip from '$lib/calendar/WeekStrip.svelte';
	import MonthGrid from '$lib/calendar/MonthGrid.svelte';
	import { iCinema, iMonitored, iNext, iPrev, iTv, iVisible, iHidden, iWarning } from '$lib/icons';

	// Everything here depends on the device's clock and time zone, so it renders only in the browser.
	let ready = $state(false);
	let wide = $state(false);
	let firstDay = $state(1);

	onMount(() => {
		firstDay = weekStartDay();
		if (!view.selected) view.selected = dayKey(new Date());
		if (!filtersLoaded) {
			filtersLoaded = true;
			try {
				const saved = JSON.parse(localStorage.getItem('calendar:filters') ?? 'null');
				if (saved && typeof saved === 'object') view.filters = { ...DEFAULT_FILTERS, ...saved };
			} catch {}
		}
		const mq = matchMedia('(width >= 60rem)');
		wide = mq.matches;
		const onChange = () => (wide = mq.matches);
		mq.addEventListener('change', onChange);
		ready = true;
		return () => mq.removeEventListener('change', onChange);
	});

	$effect(() => {
		const f = JSON.stringify(view.filters);
		if (!ready) return;
		try {
			localStorage.setItem('calendar:filters', f);
		} catch {}
	});

	const selectedDate = $derived(view.selected ? fromKey(view.selected) : new Date());
	const weekStart = $derived(startOfWeek(selectedDate, firstDay));
	const week = $derived(Array.from({ length: 7 }, (_, i) => addDays(weekStart, i)));
	// The data window is the month grid the view sits in: a week always fits inside its first day's grid.
	const anchor = $derived(wide ? selectedDate : weekStart);
	const grid = $derived(monthGrid(anchor, firstDay));
	const url = $derived(ready && view.selected ? calendarUrl(grid.start, grid.end) : null);

	const feed = calendarFeed(() => url);
	const data = $derived(feed.state.data);

	// Warm the neighbouring months once this one has loaded, so paging is instant.
	$effect(() => {
		if (!data || !ready) return;
		for (const m of [-1, 1]) {
			const g = monthGrid(addMonths(anchor, m), firstDay);
			prefetch(calendarUrl(g.start, g.end));
		}
	});

	const filtered = $derived(
		(data?.entries ?? []).filter(
			(e) =>
				(e.kind === 'episode' ? view.filters.episodes : view.filters.movies) &&
				(!view.filters.monitoredOnly || e.monitored) &&
				(!view.filters.hideDownloaded || e.status !== 'downloaded')
		)
	);
	const groups = $derived(byDay(filtered));
	const weekKeys = $derived(week.map(dayKey));
	const todayKey = $derived(ready ? dayKey(new Date()) : '');
	/** "Today" is available whenever the selected day isn't today (even within the current week). */
	const showsToday = $derived(view.selected === todayKey);

	// Phones keep the header on one row: the year only appears when it isn't this year.
	const title = $derived.by(() => {
		if (wide) return selectedDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
		const end = addDays(weekStart, 6);
		const thisYear = weekStart.getFullYear() === new Date().getFullYear() && end.getFullYear() === weekStart.getFullYear();
		if (weekStart.getMonth() === end.getMonth())
			return weekStart.toLocaleDateString(undefined, thisYear ? { month: 'long' } : { month: 'short', year: 'numeric' });
		const a = weekStart.toLocaleDateString(undefined, { month: 'short' });
		return thisYear ? `${a}\u2009–\u2009${end.toLocaleDateString(undefined, { month: 'short' })}` : `${a}\u2009–\u2009${end.toLocaleDateString(undefined, { month: 'short', year: 'numeric' })}`;
	});
	const weekCount = $derived(weekKeys.reduce((n, k) => n + (groups.get(k)?.length ?? 0), 0));
	const weekHidden = $derived(weekCount === 0 && (data?.entries ?? []).some((e) => weekKeys.includes(entryDay(e))));
	const hiddenByFilters = $derived((data?.entries.length ?? 0) > 0 && filtered.length === 0);
	const filtersActive = $derived(JSON.stringify(view.filters) !== JSON.stringify(DEFAULT_FILTERS));
	const failures = $derived(data?.errors ?? []);
	let showDetails = $state(false);

	// Height of the sticky header + week strip on phones: day sections scroll to just below it.
	let stickyHeight = $state(0);

	// Phones: open on today's entries, but only scroll when they'd start below the fold (once per visit).
	// The title, arrows and week strip stay pinned, so the header is never scrolled away.
	let scrolledToSelected = false;
	// SvelteKit restores the scroll position after hydrating (e.g. on reload): only scroll after that.
	let navigated = $state(false);
	afterNavigate(() => (navigated = true));
	$effect(() => {
		if (scrolledToSelected || !ready || !navigated || wide || !data) return;
		if (scrollY > 0) {
			scrolledToSelected = true; // coming back to a remembered position: leave it
			return;
		}
		scrolledToSelected = true;
		const k = view.selected;
		if (k === weekKeys[0] || !groups.get(k)?.length) return;
		tick().then(() => {
			const el = document.getElementById(`day-${k}`);
			if (el && el.getBoundingClientRect().top > innerHeight * 0.6) el.scrollIntoView({ block: 'start' });
		});
	});

	let dir = $state(1);
	function step(n: number) {
		dir = n;
		if (wide) {
			const target = addMonths(selectedDate, n);
			// Keep today selected when paging back to the current month.
			const now = new Date();
			view.selected = dayKey(target.getMonth() === now.getMonth() && target.getFullYear() === now.getFullYear() ? now : target);
		} else {
			view.selected = dayKey(addDays(selectedDate, 7 * n));
			toWeekTop();
		}
	}
	function goToday() {
		const k = dayKey(new Date());
		dir = k < view.selected ? -1 : 1;
		const sameWeek = weekKeys.includes(k);
		view.selected = k;
		if (!wide) sameWeek ? scrollToDay(k) : toWeekTop();
	}
	function pick(k: string) {
		view.selected = k;
		if (!wide) scrollToDay(k);
	}
	function scrollToDay(k: string) {
		tick().then(() => {
			const el = document.getElementById(`day-${k}`);
			if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
			else toWeekTop();
		});
	}
	/** A new week starts at its top, as long as the agenda had been scrolled past it. */
	function toWeekTop() {
		const top = agendaEl ? agendaEl.getBoundingClientRect().top + scrollY - stickyHeight - 8 : 0;
		if (scrollY > top) scrollTo({ top: Math.max(0, top) });
	}
	let agendaEl = $state<HTMLElement>();

	/*
	 * Swipe left/right on the week to change weeks. `touch-action: pan-y` leaves vertical scrolling to the
	 * browser (which cancels the pointer when it takes over), so only a clearly horizontal gesture counts.
	 */
	const SWIPE_MIN = 56;
	let swipe: { x: number; y: number; id: number } | null = null;
	function down(e: PointerEvent) {
		if (e.pointerType === 'mouse' || !e.isPrimary) return;
		if ((e.target as Element).closest('.filters, input, textarea')) return;
		swipe = { x: e.clientX, y: e.clientY, id: e.pointerId };
	}
	function move(e: PointerEvent) {
		if (!swipe || swipe.id !== e.pointerId) return;
		// A mostly vertical start means scrolling: give up on this gesture.
		const dx = Math.abs(e.clientX - swipe.x);
		const dy = Math.abs(e.clientY - swipe.y);
		if (dy > 24 && dy > dx) swipe = null;
	}
	function up(e: PointerEvent) {
		if (!swipe || swipe.id !== e.pointerId) return;
		const dx = e.clientX - swipe.x;
		const dy = e.clientY - swipe.y;
		swipe = null;
		if (Math.abs(dx) >= SWIPE_MIN && Math.abs(dx) > Math.abs(dy) * 1.5) step(dx < 0 ? 1 : -1);
	}
	function key(e: KeyboardEvent) {
		const t = e.target as HTMLElement;
		if (t.closest('input, textarea, [contenteditable]')) return;
		if (e.key === 'ArrowLeft' && e.altKey) step(-1);
		else if (e.key === 'ArrowRight' && e.altKey) step(1);
	}

	const toggle = (k: keyof Filters) => (view.filters[k] = !view.filters[k]);
	const clearFilters = () => (view.filters = { ...DEFAULT_FILTERS });
</script>

<svelte:head><title>Calendar · Bosunarr</title></svelte:head>
<svelte:window onkeydown={key} />

{#snippet head()}
	<header>
		<h1 class="title-large">{title}</h1>
		<div class="nav">
			<Button variant="tonal" onclick={goToday} disabled={!ready || showsToday}>Today</Button>
			<Button variant="text" iconType="full" aria-label={wide ? 'Previous month' : 'Previous week'} onclick={() => step(-1)} disabled={!ready}>
				<Icon icon={iPrev} />
			</Button>
			<Button variant="text" iconType="full" aria-label={wide ? 'Next month' : 'Next week'} onclick={() => step(1)} disabled={!ready}>
				<Icon icon={iNext} />
			</Button>
		</div>
	</header>
{/snippet}

{#snippet chip(k: keyof Filters, label: string, icon: IconifyIcon)}
	<Chip variant="general" {icon} selected={view.filters[k]} onclick={() => toggle(k)}>{label}</Chip>
{/snippet}

{#snippet filters()}
	<!-- .chip-row (app.css): 32 px chips with 40 px tall touch targets. -->
	<div class="filters chip-row no-scrollbar" role="group" aria-label="Filters">
		{@render chip('episodes', 'Episodes', iTv)}
		{@render chip('movies', 'Movies', iCinema)}
		{@render chip('monitoredOnly', 'Monitored only', iMonitored)}
		{@render chip('hideDownloaded', 'Hide downloaded', view.filters.hideDownloaded ? iHidden : iVisible)}
	</div>
{/snippet}

{#snippet notices()}
	{#if feed.state.error}
		<div class="notice" role="alert">
			<Icon icon={iWarning} size={20} />
			<p>{data ? "Couldn't refresh the calendar — showing the last copy." : "Couldn't load the calendar."}</p>
			<Button variant="text" onclick={() => feed.refresh()}>Retry</Button>
		</div>
	{/if}
	{#if failures.length}
		<div class="notice">
			<Icon icon={iWarning} size={20} />
			<div class="msg">
				{#each failures as f (f.service)}
					<p><strong>{f.service}</strong> {f.message || "couldn't be loaded"}.</p>
				{/each}
				<p class="sub">{failures.length === 1 ? 'Its' : 'Their'} releases aren't shown.</p>
				{#if showDetails}
					<ul class="details">
						{#each failures as f (f.service)}<li>{f.service}: {f.detail || f.error}</li>{/each}
					</ul>
				{/if}
			</div>
			<Button variant="text" aria-expanded={showDetails} onclick={() => (showDetails = !showDetails)}>{showDetails ? 'Hide' : 'Details'}</Button>
		</div>
	{/if}
{/snippet}

{#snippet skeleton(n: number)}
	<div class="skeleton" aria-hidden="true">
		<div class="sk-h"></div>
		{#each Array.from({ length: n }, (_, i) => i) as i (i)}<div class="sk-item"><div class="sk-p"></div><div class="sk-t"><i></i><i></i><i></i></div></div>{/each}
	</div>
{/snippet}

{#if wide}
	{@render head()}
	{@render filters()}
	<div class="progress">
		{#if feed.state.loading && data}<div class="busy" role="progressbar" aria-label="Refreshing calendar"></div>{/if}
	</div>
	<div class="month">
		<div class="grid-wrap">
			{#key grid.start.getTime()}
				<div in:fly={{ x: 24 * dir, duration: 180 }}>
					<MonthGrid days={grid.days} month={selectedDate.getMonth()} {groups} selected={view.selected} onpick={pick} />
				</div>
			{/key}
		</div>
		<aside aria-label="Selected day">
			{@render notices()}
			{#if data}
				<Agenda days={[view.selected]} {groups} selected={view.selected} showEmptyDays emptyText="Nothing releasing on this day." />
				{#if hiddenByFilters}<p class="hint">Some releases are hidden by the filters.</p>{/if}
			{:else if !feed.state.error}
				{@render skeleton(2)}
			{/if}
		</aside>
	</div>
{:else}
	<div
		class="phone"
		onpointerdown={down}
		onpointermove={move}
		onpointerup={up}
		onpointercancel={() => (swipe = null)}
		role="presentation"
		style:--cal-sticky="{stickyHeight}px"
	>
		<div class="sticky" bind:offsetHeight={stickyHeight}>
			{@render head()}
			<div class="strip">
				<WeekStrip days={week} {groups} selected={view.selected} today={todayKey} onpick={pick} />
			</div>
			<div class="progress">
				{#if feed.state.loading && data}<div class="busy" role="progressbar" aria-label="Refreshing calendar"></div>{/if}
			</div>
		</div>
		{@render filters()}
		<div class="agenda" bind:this={agendaEl}>
			{#if !ready || (!data && !feed.state.error)}
				{@render skeleton(3)}
			{:else}
				<!-- Inside the agenda, so a notice arriving with the data replaces the skeleton instead of pushing it down. -->
				{@render notices()}
			{/if}
			{#if ready && data}
				{#key weekStart.getTime()}
					<div in:fly={{ x: 32 * dir, duration: 180 }}>
						<Agenda days={weekKeys} {groups} selected={view.selected} emptyText={weekHidden ? 'Everything this week is hidden' : 'Nothing releasing this week'}>
							{#snippet empty()}
								{#if weekHidden && filtersActive}
									<p class="es-hint">Your filters hide this week's releases.</p>
									<div class="es-action"><Button variant="tonal" onclick={clearFilters}>Clear filters</Button></div>
								{:else}
									<p class="es-hint">Swipe or use the arrows to see other weeks.</p>
								{/if}
							{/snippet}
						</Agenda>
					</div>
				{/key}
			{/if}
		</div>
		{#if ready && data && weekCount === 0}<span class="sr-only" aria-live="polite">No releases this week</span>{/if}
	</div>
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		min-height: 2.5rem;
		margin-bottom: 0.75rem;
	}
	h1 {
		flex: 1;
		min-width: 0;
		margin: 0;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		font-variant-numeric: tabular-nums;
	}
	.nav {
		flex: none;
		display: flex;
		align-items: center;
	}
	/* A slightly tighter Today button keeps "Sept – Oct 2027" on one row at 360 px. */
	.nav :global(.m3-container.tonal) {
		padding-inline: 0.75rem;
		margin-right: 0.25rem;
	}
	.filters {
		overflow-x: auto;
		margin: -0.25rem -1rem 0;
		padding: 0.25rem 1rem 0.75rem;
	}
	.filters > :global(*) {
		flex: none;
	}
	.progress {
		height: 4px;
		margin-bottom: 0.5rem;
	}
	.busy {
		height: 100%;
		border-radius: 2px;
		background: linear-gradient(90deg, transparent, var(--m3c-primary), transparent) 0 0 / 40% 100% no-repeat
			var(--m3c-secondary-container);
		animation: busy 1.1s linear infinite;
	}
	@keyframes busy {
		from {
			background-position: -40% 0;
		}
		to {
			background-position: 140% 0;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.busy {
			animation: none;
		}
	}
	.notice {
		display: flex;
		align-items: flex-start;
		gap: 0.75rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem 0.25rem 0.5rem 0.75rem;
		border-radius: var(--m3-shape-medium, 0.75rem);
		background: var(--m3c-surface-container-high);
		color: var(--m3c-on-surface);
		font-size: 0.875rem;
	}
	.notice > :global(svg) {
		flex: none;
		color: var(--m3c-error);
		margin-top: 0.625rem;
	}
	.notice > p,
	.notice .msg {
		flex: 1;
		min-width: 0;
		margin: 0;
		padding: 0.5rem 0;
	}
	.msg p {
		margin: 0;
	}
	.msg .sub {
		color: var(--m3c-on-surface-variant);
	}
	.details {
		margin: 0.375rem 0 0;
		padding: 0;
		list-style: none;
		font-size: 0.75rem;
		font-family: ui-monospace, monospace;
		overflow-wrap: anywhere;
	}
	.notice :global(button) {
		flex: none;
	}
	.hint {
		color: var(--m3c-on-surface-variant);
		font-size: 0.8125rem;
		padding-left: 0.25rem;
	}
	/* Phones: the whole area below the nav takes swipes, even when the week is empty. */
	.phone {
		touch-action: pan-y;
		min-height: calc(100dvh - 6.5rem - env(safe-area-inset-bottom));
		display: flex;
		flex-direction: column;
	}
	.sticky {
		position: sticky;
		top: 0;
		z-index: 2;
		margin: -1rem -1rem 0.75rem;
		padding: 1rem 1rem 0;
		background: var(--m3c-surface);
		border-bottom: 1px solid var(--m3c-outline-variant);
	}
	.sticky header {
		margin-bottom: 0.5rem;
	}
	.sticky .progress {
		margin: 0.25rem -1rem 0;
		height: 3px;
	}
	.agenda {
		flex: 1;
		overflow-x: clip;
	}
	.month {
		display: grid;
		grid-template-columns: minmax(0, 1fr) 22rem;
		gap: 1.25rem;
		align-items: start;
	}
	.grid-wrap {
		overflow: hidden;
	}
	aside {
		position: sticky;
		top: 1rem;
		max-height: calc(100dvh - 2rem);
		overflow-y: auto;
		scrollbar-width: thin;
	}
	.skeleton {
		display: grid;
		gap: 0.5rem;
	}
	.sk-h {
		width: 9rem;
		height: 0.875rem;
		margin: 0.25rem 0 0.25rem 0.25rem;
		border-radius: 0.25rem;
		background: var(--m3c-surface-container-high);
	}
	.sk-item {
		display: flex;
		gap: 0.75rem;
		padding: 0.625rem 0.75rem;
		border-radius: var(--m3-shape-large, 1rem);
		background: var(--m3c-surface-container-low);
	}
	.sk-p {
		width: 44px;
		height: 66px;
		border-radius: 0.5rem;
		background: var(--m3c-surface-container-highest);
	}
	.sk-t {
		flex: 1;
		display: grid;
		align-content: start;
		gap: 0.5rem;
		padding-top: 0.25rem;
	}
	.sk-t i {
		height: 0.75rem;
		border-radius: 0.25rem;
		background: var(--m3c-surface-container-highest);
	}
	.sk-t i:nth-child(1) {
		width: 70%;
	}
	.sk-t i:nth-child(2) {
		width: 50%;
	}
	.sk-t i:nth-child(3) {
		width: 30%;
	}
	.skeleton {
		animation: pulse 1.4s ease-in-out infinite;
	}
	@keyframes pulse {
		50% {
			opacity: 0.55;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.skeleton {
			animation: none;
		}
	}
	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip: rect(0 0 0 0);
	}
</style>
