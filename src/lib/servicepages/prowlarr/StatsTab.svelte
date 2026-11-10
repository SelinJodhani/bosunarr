<script lang="ts" module>
	type Metric = 'queries' | 'grabs' | 'failures' | 'response';
	const view = $state({ days: 30, metric: 'queries' as Metric });
</script>

<script lang="ts">
	import { Chip } from 'm3-svelte';
	import { STAT_RANGES } from './types';
	import StatsView from './StatsView.svelte';

	let { api }: { api: string } = $props();

	const METRICS: Array<{ value: Metric; label: string }> = [
		{ value: 'queries', label: 'Queries' },
		{ value: 'grabs', label: 'Grabs' },
		{ value: 'failures', label: 'Failures' },
		{ value: 'response', label: 'Response time' }
	];
</script>

<div class="chips" role="toolbar" aria-label="Range">
	{#each STAT_RANGES as d (d)}
		<Chip variant="general" selected={view.days === d} onclick={() => (view.days = d)}>{d} days</Chip>
	{/each}
</div>
<div class="chips" role="toolbar" aria-label="Metric">
	{#each METRICS as m (m.value)}
		<Chip variant="general" selected={view.metric === m.value} onclick={() => (view.metric = m.value)}>{m.label}</Chip>
	{/each}
</div>

{#key view.days}
	<StatsView url={`${api}/stats?days=${view.days}`} metric={view.metric} />
{/key}

<style>
	.chips {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		padding: 0 0 0.75rem;
		scrollbar-width: none;
	}
	.chips > :global(*) {
		flex: none;
	}
</style>
