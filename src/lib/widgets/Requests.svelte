<script lang="ts">
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import { WIDGETS, type RequestItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<RequestItem[]>('/api/widgets/requests', WIDGETS.requests.refreshMs);
	const items = $derived(q.state.data ?? []);
	const shown = $derived(items.slice(0, size === 's' ? 3 : size === 'm' ? 5 : 10));
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No pending requests — all caught up">
	<ul>
		{#each shown as r (r.id)}
			<li>
				{#if size !== 's'}<Poster src={r.poster} width={36} alt={r.title} />{/if}
				<div class="text">
					<div class="title">{r.title}{r.year ? ` (${r.year})` : ''}{#if r.is4k}<span class="chip">4K</span>{/if}</div>
					<div class="sub">{r.kind === 'movie' ? 'Movie' : 'Series'} · {r.requestedBy} · {ago(r.createdAt)}</div>
				</div>
			</li>
		{/each}
	</ul>
</State>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	li {
		display: flex;
		gap: 0.75rem;
		align-items: center;
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	.title,
	.sub {
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
	.chip {
		margin-left: 0.375rem;
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		font-size: 0.6875rem;
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
</style>
