<script lang="ts">
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import iAllDone from '@ktibow/iconset-material-symbols/done-all-rounded';
	import { WIDGETS, type RequestItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<RequestItem[]>('/api/widgets/requests', WIDGETS.requests.refreshMs);
	const items = $derived(q.state.data ?? []);
	const max = $derived(size === 's' ? 3 : size === 'm' ? 5 : 10);
	const shown = $derived(items.slice(0, max));
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No pending requests — all caught up" emptyIcon={iAllDone} skeleton={{ rows: Math.min(max, 3), thumb: size === 's' ? 0 : 36 }}>
	<ul>
		{#each shown as r (r.id)}
			<li>
				<a class="row-link" href="/discover?tab=requests">
				{#if size !== 's'}<Poster src={r.poster} width={36} />{/if}
				<div class="text">
					<div class="title">{r.title}{r.year ? ` (${r.year})` : ''}{#if r.is4k}<span class="chip">4K</span>{/if}</div>
					<div class="sub">{r.kind === 'movie' ? 'Movie' : 'Series'} · {r.requestedBy} · {ago(r.createdAt)}</div>
				</div>
				</a>
			</li>
		{/each}
	</ul>
	{#if items.length > shown.length}<a class="more row-link" href="/discover?tab=requests">+{items.length - shown.length} more requests</a>{/if}
</State>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.more {
		margin-top: 0.25rem !important;
		font-size: 0.75rem;
		color: var(--m3c-primary);
		font-weight: 500;
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
