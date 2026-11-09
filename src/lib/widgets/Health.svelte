<script lang="ts">
	import { live } from '$lib/live.svelte';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import State from './State.svelte';
	import { WIDGETS, type HealthItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<HealthItem[]>('/api/widgets/health', WIDGETS.health.refreshMs);
	const items = $derived(q.state.data ?? []);
	const down = $derived(items.filter((i) => !i.ok).length);
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0}>
	<p class="summary" class:bad={down > 0}>
		{down === 0 ? `All ${items.length} services healthy` : `${down} of ${items.length} need attention`}
	</p>
	<ul class:compact={size === 's'}>
		{#each items as s (s.id)}
			<li title={s.error ?? (s.version ? `v${s.version}` : '')}>
				<span class="dot" class:ok={s.ok}></span>
				<span class="name">{s.name}</span>
				{#if size !== 's'}
					<span class="meta">{s.ok ? `${s.ms} ms` : (s.error ?? 'Down')}</span>
				{/if}
				<span class="type sr-only">{SERVICE_TYPES[s.type]?.label}</span>
			</li>
		{/each}
	</ul>
</State>

<style>
	.summary {
		margin: 0 0 0.75rem;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
	.summary.bad {
		color: var(--m3c-error);
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
		gap: 0.5rem;
	}
	ul.compact {
		grid-template-columns: 1fr 1fr;
	}
	li {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container);
		min-width: 0;
	}
	.dot {
		flex: none;
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: var(--m3c-error);
	}
	.dot.ok {
		background: #2e7d32;
	}
	.name {
		flex: 1 0 auto;
		max-width: 60%;
		min-width: 3rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.875rem;
	}
	.meta {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
		flex: 0 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		margin-left: auto;
	}
</style>
