<script lang="ts">
	import { untrack } from 'svelte';
	import { live } from '$lib/live.svelte';
	import Art from '../shared/Art.svelte';
	import iDone from '@ktibow/iconset-material-symbols/task-alt-rounded';
	import State from '../shared/State.svelte';
	import Pager from '../shared/Pager.svelte';
	import { shortDate } from './format';
	import type { Paged, WantedItem, WantedKind } from './types';

	let { url, kind, onpage }: { url: string; kind: WantedKind; onpage: (p: number) => void } = $props();

	// The parent remounts this list (keyed) whenever the URL changes.
	const q = live<Paged<WantedItem>>(untrack(() => url), 60_000);
	const data = $derived(q.state.data);
	const items = $derived(data?.records ?? []);
	const empty = $derived(kind === 'missing' ? 'Nothing missing: everything monitored is on disk' : 'Everything meets its quality cutoff');
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} icon={iDone} title={empty} onretry={() => q.refresh()}>
	<ul class="list">
		{#each items as w (w.id)}
			<li>
				<a class="row" href={w.href ?? undefined}>
					<Art src={w.poster} width={40} />
					<span class="body">
						<span class="title">{w.title}</span>
						{#if w.subtitle}<span class="sub">{w.subtitle}</span>{/if}
						<span class="meta">
							{#if w.dateLabel && w.date}<span>{w.dateLabel} {shortDate(w.date)}</span>{/if}
							{#if w.quality}<span class="pill">Has {w.quality}</span>{/if}
						</span>
					</span>
				</a>
			</li>
		{/each}
	</ul>
	{#if data}
		<Pager page={data.page} pages={data.pages} total={data.total} noun={kind === 'missing' ? 'missing item' : 'upgradable item'} {onpage} />
	{/if}
</State>

<style>
	.list {
		list-style: none;
		margin: 0 0 0.5rem;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	@media (min-width: 60rem) {
		.list {
			grid-template-columns: repeat(2, minmax(0, 1fr));
			gap: 0.5rem;
		}
	}
	.row {
		display: flex;
		gap: 0.875rem;
		align-items: center;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m);
		color: inherit;
		text-decoration: none;
		background: var(--m3c-surface-container-low);
	}
	.row:hover {
		background: var(--m3c-surface-container);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.125rem;
	}
	.title,
	.sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title {
		font-weight: 500;
	}
	.sub,
	.meta {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 0.75rem;
	}
	.pill {
		color: var(--m3c-on-secondary-container);
	}
</style>
