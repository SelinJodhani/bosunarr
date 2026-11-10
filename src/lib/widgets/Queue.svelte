<script lang="ts">
	import { LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { arrTimeleft, queueState } from '$lib/activity/format';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import iDone from '@ktibow/iconset-material-symbols/download-done-rounded';
	import { WIDGETS, type QueueItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<QueueItem[]>('/api/widgets/queue', WIDGETS.queue.refreshMs);
	const items = $derived(q.state.data ?? []);
	const max = $derived(size === 's' ? 3 : size === 'm' ? 5 : 12);
	const shown = $derived(items.slice(0, max));
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="Queue is empty" emptyIcon={iDone} skeleton={{ rows: max, thumb: size === 's' ? 0 : 36, bar: true }}>
	<ul>
		{#each shown as it (it.id)}
			<li>
				<a class="row-link" href="/activity">
				{#if size !== 's'}<Poster src={it.poster} width={36} />{/if}
				<div class="text">
					<div class="title">{it.title}</div>
					<div class="sub">{it.subtitle ?? it.service} · {queueState(it.status)}{arrTimeleft(it.timeleft) && it.status === 'downloading' ? ` · ${arrTimeleft(it.timeleft)} left` : ''}</div>
					<LinearProgress percent={it.progress * 100} aria-label={`${it.title} download progress`} />
				</div>
				</a>
			</li>
		{/each}
	</ul>
	{#if items.length > shown.length}<a class="more row-link" href="/activity">+{items.length - shown.length} more in Activity</a>{/if}
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
		margin-top: 0.25rem !important;
		color: var(--m3c-primary);
		font-weight: 500;
	}
</style>
