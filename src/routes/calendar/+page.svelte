<script lang="ts" module>
	// Module scope: the selected day and filters survive opening a detail page and coming back.
	type Filters = { episodes: boolean; movies: boolean; monitoredOnly: boolean; hideDownloaded: boolean };
	const DEFAULT_FILTERS: Filters = { episodes: true, movies: true, monitoredOnly: false, hideDownloaded: false };
	const view = $state({ selected: '', filters: { ...DEFAULT_FILTERS } });
	let filtersLoaded = false;
</script>

<script lang="ts">
	import { onMount } from 'svelte';
	import { fly } from 'svelte/transition';
	import { Button, Chip, Icon, LoadingIndicator } from 'm3-svelte';
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
	import { iCinema, iMonitored, iNext, iPrev, iToday, iTv, iVisible, iHidden } from '$lib/icons';

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
	const showsToday = $derived(wide ? grid.days.some((d) => dayKey(d) === todayKey) && view.selected === todayKey : weekKeys.includes(todayKey));

	const title = $derived(
		wide || weekStart.getMonth() === addDays(weekStart, 6).getMonth()
			? (wide ? selectedDate : weekStart).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
			: `${weekStart.toLocaleDateString(undefined, { month: 'short' })} – ${addDays(weekStart, 6).toLocaleDateString(undefined, { month: 'short', year: 'numeric' })}`
	);
	const weekCount = $derived(weekKeys.reduce((n, k) => n + (groups.get(k)?.length ?? 0), 0));
	const weekHidden = $derived(weekCount === 0 && (data?.entries ?? []).some((e) => weekKeys.includes(entryDay(e))));
	const hiddenByFilters = $derived((data?.entries.length ?? 0) > 0 && filtered.length === 0);

	// Phones: open on today's entries rather than the start of the week (once per visit).
	let scrolledToSelected = false;
	$effect(() => {
		if (scrolledToSelected || !ready || wide || !data) return;
		scrolledToSelected = true;
		const k = view.selected;
		if (k !== weekKeys[0] && groups.get(k)?.length)
			requestAnimationFrame(() => document.getElementById(`day-${k}`)?.scrollIntoView({ block: 'start' }));
	});

	let dir = $state(1);
	function step(n: number) {
		dir = n;
		if (wide) {
			const target = addMonths(selectedDate, n);
			// Keep today selected when paging back to the current month.
			const now = new Date();
			view.selected = dayKey(target.getMonth() === now.getMonth() && target.getFullYear() === now.getFullYear() ? now : target);
		} else view.selected = dayKey(addDays(selectedDate, 7 * n));
	}
	function goToday() {
		const k = dayKey(new Date());
		dir = k < view.selected ? -1 : 1;
		view.selected = k;
		if (!wide) scrollToDay(k);
	}
	function pick(k: string) {
		view.selected = k;
		if (!wide) scrollToDay(k);
	}
	function scrollToDay(k: string) {
		requestAnimationFrame(() => document.getElementById(`day-${k}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
	}

	/* Swipe left/right on the week to change weeks (vertical scrolling stays native). */
	let swipe: { x: number; y: number; id: number } | null = null;
	function down(e: PointerEvent) {
		if (e.pointerType === 'mouse') return;
		swipe = { x: e.clientX, y: e.clientY, id: e.pointerId };
	}
	function up(e: PointerEvent) {
		if (!swipe || swipe.id !== e.pointerId) return;
		const dx = e.clientX - swipe.x;
		const dy = e.clientY - swipe.y;
		swipe = null;
		if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.5) step(dx < 0 ? 1 : -1);
	}
	function key(e: KeyboardEvent) {
		const t = e.target as HTMLElement;
		if (t.closest('input, textarea, [contenteditable]')) return;
		if (e.key === 'ArrowLeft' && e.altKey) step(-1);
		else if (e.key === 'ArrowRight' && e.altKey) step(1);
	}

	const toggle = (k: keyof Filters) => (view.filters[k] = !view.filters[k]);
</script>

<svelte:head><title>Calendar · Bosunarr</title></svelte:head>
<svelte:window onkeydown={key} />

<header>
	<h1 class="title-large">{ready ? title : 'Calendar'}</h1>
	<div class="nav">
		<Button variant="tonal" onclick={goToday} disabled={!ready || showsToday}><Icon icon={iToday} /> Today</Button>
		<Button variant="text" iconType="full" aria-label={wide ? 'Previous month' : 'Previous week'} onclick={() => step(-1)} disabled={!ready}>
			<Icon icon={iPrev} />
		</Button>
		<Button variant="text" iconType="full" aria-label={wide ? 'Next month' : 'Next week'} onclick={() => step(1)} disabled={!ready}>
			<Icon icon={iNext} />
		</Button>
	</div>
</header>

<div class="filters" role="group" aria-label="Filters">
	<Chip variant="general" icon={iTv} selected={view.filters.episodes} onclick={() => toggle('episodes')}>Episodes</Chip>
	<Chip variant="general" icon={iCinema} selected={view.filters.movies} onclick={() => toggle('movies')}>Movies</Chip>
	<Chip variant="general" icon={iMonitored} selected={view.filters.monitoredOnly} onclick={() => toggle('monitoredOnly')}>Monitored only</Chip>
	<Chip variant="general" icon={view.filters.hideDownloaded ? iHidden : iVisible} selected={view.filters.hideDownloaded} onclick={() => toggle('hideDownloaded')}
		>Hide downloaded</Chip
	>
</div>

<div class="progress">
	{#if ready && feed.state.loading && data}<div class="busy" role="progressbar" aria-label="Refreshing calendar"></div>{/if}
</div>

{#if !ready || (!data && !feed.state.error)}
	<div class="center"><LoadingIndicator aria-label="Loading calendar" /></div>
{:else}
	{#if feed.state.error}
		<p class="error" role="alert">{data ? `Showing saved data — ${feed.state.error}` : feed.state.error}</p>
	{/if}
	{#if data?.errors.length}
		<p class="error">Couldn't reach {data.errors.map((e) => e.service).join(', ')}: {data.errors[0].error}</p>
	{/if}

	{#if wide}
		<div class="month">
			<div class="grid-wrap">
				{#key grid.start.getTime()}
					<div in:fly={{ x: 24 * dir, duration: 180 }}>
						<MonthGrid days={grid.days} month={selectedDate.getMonth()} {groups} selected={view.selected} onpick={pick} />
					</div>
				{/key}
			</div>
			<aside aria-label="Selected day">
				<Agenda
					days={[view.selected]}
					{groups}
					selected={view.selected}
					showEmptyDays
					emptyText="Nothing releasing on this day."
				/>
				{#if hiddenByFilters}<p class="hint">Some releases are hidden by the filters.</p>{/if}
			</aside>
		</div>
	{:else}
		<div class="week" onpointerdown={down} onpointerup={up} onpointercancel={() => (swipe = null)} role="presentation">
			<div class="strip">
				<WeekStrip days={week} {groups} selected={view.selected} onpick={pick} />
			</div>
			{#key weekStart.getTime()}
				<div class="agenda" in:fly={{ x: 32 * dir, duration: 180 }}>
					<Agenda
						days={weekKeys}
						{groups}
						selected={view.selected}
						emptyText={weekHidden ? 'Everything this week is hidden by the filters.' : 'Nothing releasing this week.'}
					/>
				</div>
			{/key}
			{#if weekCount === 0}<span class="sr-only" aria-live="polite">No releases this week</span>{/if}
		</div>
	{/if}
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		flex-wrap: wrap;
	}
	h1 {
		margin: 0;
		font-variant-numeric: tabular-nums;
	}
	.nav {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}
	.filters {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		scrollbar-width: none;
		padding: 0.75rem 0 0.25rem;
	}
	.filters :global(*) {
		flex: none;
	}
	.progress {
		height: 4px;
		margin: 0.25rem 0;
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
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0 0 0.75rem;
	}
	.hint {
		color: var(--m3c-on-surface-variant);
		font-size: 0.8125rem;
		padding-left: 0.25rem;
	}
	.week {
		touch-action: pan-y;
	}
	.strip {
		position: sticky;
		top: 0;
		z-index: 2;
		margin: 0 -1rem 0.75rem;
		padding: 0.25rem 0.75rem 0.5rem;
		background: var(--m3c-surface);
		border-bottom: 1px solid var(--m3c-outline-variant);
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
	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip: rect(0 0 0 0);
	}
</style>
