<script lang="ts">
	import type { Snippet } from 'svelte';
	import { Icon } from 'm3-svelte';
	import { iNoEvents } from '$lib/icons';
	import AgendaItem from './AgendaItem.svelte';
	import { dayKey, fromKey } from './client.svelte';
	import type { CalendarEntry } from './types';

	let {
		days,
		groups,
		selected = null,
		emptyText,
		showEmptyDays = false,
		empty
	}: {
		days: string[];
		groups: Map<string, CalendarEntry[]>;
		selected?: string | null;
		emptyText: string;
		/** Show a header (and "nothing") for days without entries — used for a single-day agenda. */
		showEmptyDays?: boolean;
		/** Extra content (hint, action) under the empty-state line. */
		empty?: Snippet;
	} = $props();

	const today = dayKey(new Date());
	const tomorrow = dayKey(new Date(Date.now() + 864e5));
	const yesterday = dayKey(new Date(Date.now() - 864e5));

	function heading(k: string) {
		const d = fromKey(k);
		const date = d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
		const rel = k === today ? 'Today' : k === tomorrow ? 'Tomorrow' : k === yesterday ? 'Yesterday' : null;
		return { rel, date };
	}

	const shown = $derived(days.filter((k) => showEmptyDays || groups.get(k)?.length));
</script>

{#if shown.length === 0}
	<div class="empty">
		<Icon icon={iNoEvents} size={40} />
		<p class="title-medium">{emptyText}</p>
		{@render empty?.()}
	</div>
{:else}
	{#each shown as k (k)}
		{@const list = groups.get(k) ?? []}
		{@const h = heading(k)}
		<section class="day" id="day-{k}" class:selected={k === selected} class:past={k < today}>
			<h2>
				{#if h.rel}<span class="rel">{h.rel}</span>{/if}
				<span class="date">{h.date}</span>
				{#if list.length}<span class="count">{list.length} release{list.length === 1 ? '' : 's'}</span>{/if}
			</h2>
			{#if list.length}
				<ul>
					{#each list as e (e.id)}
						<li><AgendaItem entry={e} /></li>
					{/each}
				</ul>
			{:else}
				<p class="none">Nothing releasing.</p>
			{/if}
		</section>
	{/each}
{/if}

<style>
	.day {
		scroll-margin-top: calc(var(--cal-sticky, 0px) + 0.5rem);
		margin-bottom: 1.25rem;
	}
	h2 {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		margin: 0 0 0.5rem;
		padding-left: 0.25rem;
		font-size: 0.875rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
	}
	.rel {
		color: var(--m3c-primary);
		font-weight: 600;
	}
	.selected h2 .date {
		color: var(--m3c-on-surface);
	}
	.count {
		margin-left: auto;
		font-size: 0.75rem;
		font-variant-numeric: tabular-nums;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.none {
		margin: 0;
		padding-left: 0.25rem;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	.empty {
		display: grid;
		justify-items: center;
		gap: 0.5rem;
		padding: 2.5rem 1rem;
		color: var(--m3c-on-surface-variant);
		text-align: center;
	}
	.empty :global(p) {
		margin: 0;
	}
	.empty .title-medium {
		color: var(--m3c-on-surface);
	}
	.empty :global(button) {
		margin-top: 0.5rem;
	}
</style>
