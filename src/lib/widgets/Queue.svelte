<script lang="ts">
	import { LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import { WIDGETS, type QueueItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<QueueItem[]>('/api/widgets/queue', WIDGETS.queue.refreshMs);
	const items = $derived(q.state.data ?? []);
	const shown = $derived(items.slice(0, size === 's' ? 3 : size === 'm' ? 5 : 12));
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="Queue is empty">
	<ul>
		{#each shown as it (it.id)}
			<li>
				{#if size !== 's'}<Poster src={it.poster} width={36} />{/if}
				<div class="text">
					<div class="title">{it.title}</div>
					<div class="sub">{it.subtitle ?? it.service} · {it.status}{it.timeleft ? ` · ${it.timeleft}` : ''}</div>
					<LinearProgress percent={it.progress * 100} aria-label={`${it.title} download progress`} />
				</div>
			</li>
		{/each}
	</ul>
	{#if items.length > shown.length}<p class="more">+{items.length - shown.length} more</p>{/if}
</State>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
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
		display: grid;
		gap: 0.25rem;
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
	.sub,
	.more {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.more {
		margin: 0.5rem 0 0;
	}
</style>
