<script lang="ts">
	import { LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { bytes } from '$lib/format';
	import State from './State.svelte';
	import { WIDGETS, type DiskItem, type WidgetSize } from './types';

	let { size: _size }: { size: WidgetSize } = $props();
	const q = live<DiskItem[]>('/api/widgets/disk', WIDGETS.disk.refreshMs);
	const items = $derived(q.state.data ?? []);
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0}>
	<ul>
		{#each items as d (d.path)}
			{@const used = 1 - d.free / d.total}
			<li>
				<div class="line">
					<span class="path" title={d.path}>{d.path}</span>
					<span class="free" class:low={d.free / d.total < 0.1}>{bytes(d.free)} free</span>
				</div>
				<LinearProgress percent={used * 100} aria-label={`${d.path} usage`} />
				<div class="total">{bytes(d.total - d.free)} of {bytes(d.total)} used</div>
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
		gap: 1rem;
	}
	.line {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-size: 0.875rem;
		margin-bottom: 0.375rem;
	}
	.path {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-weight: 500;
	}
	.free {
		white-space: nowrap;
		font-variant-numeric: tabular-nums;
	}
	.free.low {
		color: var(--m3c-error);
	}
	.total {
		margin-top: 0.25rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
