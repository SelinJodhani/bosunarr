<script lang="ts">
	import { Icon } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import { iNext } from '$lib/icons';
	import State from './State.svelte';
	import { humanError } from './errors';
	import { WIDGETS, type HealthItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<HealthItem[]>('/api/widgets/health', WIDGETS.health.refreshMs);
	const items = $derived(q.state.data ?? []);
	const down = $derived(items.filter((i) => !i.ok).length);
	// Problems first, so they're visible without scrolling.
	const sorted = $derived([...items].sort((a, b) => Number(a.ok) - Number(b.ok)));
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No services yet" skeleton={{ rows: 6, lead: true, tiles: true }}>
	<p class="summary" class:bad={down > 0}>
		{down === 0 ? `All ${items.length} services healthy` : `${down} of ${items.length} need attention`}
	</p>
	<ul class:compact={size === 's'}>
		{#each sorted as s (s.id)}
			{@const err = s.ok ? null : humanError(s.error ?? 'Down')}
			<li>
				<a
					href={`/services/${s.id}`}
					class:down={!s.ok}
					title={err ? err.detail : s.version ? `${SERVICE_TYPES[s.type]?.label ?? s.type} v${s.version}` : undefined}
				>
					<span class="dot" class:ok={s.ok} aria-hidden="true"></span>
					<span class="name">{s.name}</span>
					{#if size !== 's'}
						<span class="meta">{s.ok ? `${s.ms} ms` : err!.short}</span>
					{/if}
					<span class="sr-only">{s.ok ? 'healthy' : `problem: ${err!.short}`}, {SERVICE_TYPES[s.type]?.label}</span>
					<span class="chev" aria-hidden="true"><Icon icon={iNext} size={18} /></span>
				</a>
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
		font-weight: 500;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(14rem, 100%), 1fr));
		gap: 0.5rem;
	}
	ul.compact {
		grid-template-columns: minmax(0, 1fr);
	}
	li {
		min-width: 0;
	}
	a {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0 0.25rem 0 0.75rem;
		height: 2.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container);
		min-width: 0;
		color: inherit;
		text-decoration: none;
		-webkit-tap-highlight-color: transparent;
		transition: background-color 150ms;
	}
	a:hover,
	a:focus-visible {
		background: var(--m3c-surface-container-high);
	}
	a:active {
		background: var(--m3c-surface-container-highest);
	}
	a:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -2px;
	}
	a.down {
		background: color-mix(in oklab, var(--m3c-error-container) 55%, var(--m3c-surface-container));
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
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.875rem;
	}
	.meta {
		flex: none;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
		font-variant-numeric: tabular-nums;
	}
	.down .meta {
		color: var(--m3c-on-error-container);
		font-weight: 500;
	}
	.chev {
		flex: none;
		display: flex;
		color: var(--m3c-on-surface-variant);
		opacity: 0.7;
	}
</style>
