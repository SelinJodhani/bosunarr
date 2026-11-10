<script lang="ts">
	import { Icon } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { dayLabel, timeLabel } from '$lib/format';
	import { iNoEvents, iOk } from '$lib/icons';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import { WIDGETS, type UpcomingItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<UpcomingItem[]>('/api/widgets/upcoming', WIDGETS.upcoming.refreshMs);
	const items = $derived((q.state.data ?? []).slice(0, size === 's' ? 4 : size === 'm' ? 8 : 24));

	const groups = $derived.by(() => {
		const map = new Map<string, UpcomingItem[]>();
		for (const it of items) {
			const k = dayLabel(it.date);
			map.set(k, [...(map.get(k) ?? []), it]);
		}
		return [...map.entries()];
	});
</script>

<State
	loading={q.state.loading}
	error={q.state.error}
	empty={items.length === 0}
	emptyText="Nothing releasing this week"
	emptyIcon={iNoEvents}
	skeleton={size === 'l' ? { rows: 6, rail: true } : { rows: size === 's' ? 3 : 5 }}
>
	{#if size === 'l'}
		<div class="rail no-scrollbar">
			{#each items as it (it.id)}
				<a class="card" href={it.href ?? '/calendar'}>
					<Poster src={it.poster} width={112} fluid />
					<span class="title">{it.title}</span>
					<span class="sub">{dayLabel(it.date)}{it.kind === 'episode' ? ` · ${timeLabel(it.date)}` : ''}</span>
					<span class="sub">{it.subtitle}</span>
				</a>
			{/each}
		</div>
	{:else}
		{#each groups as [day, list] (day)}
			<h3 class="day">{day}</h3>
			<ul>
				{#each list as it (it.id)}
					<li>
						<a class="row-link" href={it.href ?? '/calendar'}>
							<div class="text">
								<div class="title">{it.title}</div>
								<div class="sub">{it.subtitle}{it.kind === 'episode' ? ` · ${timeLabel(it.date)}` : ''}</div>
							</div>
							{#if it.hasFile}<span class="have" title="Downloaded"><Icon icon={iOk} /><span class="sr-only">Downloaded</span></span>{/if}
						</a>
					</li>
				{/each}
			</ul>
		{/each}
	{/if}
</State>

<style>
	.rail {
		display: flex;
		gap: 0.875rem;
		overflow-x: auto;
		scroll-snap-type: x mandatory;
		scroll-padding-inline: 0.25rem;
		/* Room for the focus ring; negative margin keeps the visual edge aligned. */
		padding: 0.25rem;
		margin: -0.25rem;
	}
	.card {
		/* Grow to fill wide cards, but never into giant posters. */
		flex: 1 0 112px;
		max-width: 160px;
		min-width: 0;
		scroll-snap-align: start;
		display: grid;
		gap: 0.125rem;
		align-content: start;
		color: inherit;
		text-decoration: none;
		border-radius: 0.5rem;
		-webkit-tap-highlight-color: transparent;
	}
	.card :global(.poster) {
		margin-bottom: 0.375rem;
		transition: filter 150ms;
	}
	.card:hover :global(.poster) {
		filter: brightness(1.08);
	}
	.card:active :global(.poster) {
		filter: brightness(0.92);
	}
	.card:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.day {
		font-size: 0.75rem;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--m3c-primary);
		margin: 0.75rem 0 0.25rem;
	}
	.day:first-child {
		margin-top: 0;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.125rem;
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	.title,
	.sub {
		display: block;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title {
		font-size: 0.875rem;
		font-weight: 500;
	}
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.have {
		color: var(--m3c-primary);
		display: flex;
	}
</style>
