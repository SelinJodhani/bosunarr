<script lang="ts">
	import { Icon } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { dayLabel, timeLabel } from '$lib/format';
	import { iOk } from '$lib/icons';
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

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="Nothing releasing this week">
	{#if size === 'l'}
		<div class="rail">
			{#each items as it (it.id)}
				<figure>
					<Poster src={it.poster} width={112} alt={it.title} />
					<figcaption>
						<span class="title">{it.title}</span>
						<span class="sub">{dayLabel(it.date)}{it.kind === 'episode' ? ` · ${timeLabel(it.date)}` : ''}</span>
						<span class="sub">{it.subtitle}</span>
					</figcaption>
				</figure>
			{/each}
		</div>
	{:else}
		{#each groups as [day, list] (day)}
			<h3 class="day">{day}</h3>
			<ul>
				{#each list as it (it.id)}
					<li>
						<div class="text">
							<div class="title">{it.title}</div>
							<div class="sub">{it.subtitle}</div>
						</div>
						{#if it.hasFile}<span class="have" title="Downloaded"><Icon icon={iOk} /></span>{/if}
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
		padding-bottom: 0.5rem;
		scrollbar-width: thin;
	}
	figure {
		margin: 0;
		width: 112px;
		flex: none;
		scroll-snap-align: start;
	}
	figcaption {
		display: grid;
		gap: 0.125rem;
		margin-top: 0.5rem;
	}
	.day {
		font-size: 0.75rem;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--m3c-primary);
		margin: 0.75rem 0 0.375rem;
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
		gap: 0.5rem;
	}
	li {
		display: flex;
		align-items: center;
		gap: 0.5rem;
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
