<script lang="ts" module>
	type Metric = 'queries' | 'grabs' | 'failures' | 'response';
	const view = $state({ days: 30, metric: 'queries' as Metric });
</script>

<script lang="ts">
	import { Chip } from 'm3-svelte';
	import { STAT_RANGES } from './types';
	import StatsView from './StatsView.svelte';
	import '../shared/chips.css';

	let { api }: { api: string } = $props();

	const METRICS: Array<{ value: Metric; label: string }> = [
		{ value: 'queries', label: 'Queries' },
		{ value: 'grabs', label: 'Grabs' },
		{ value: 'failures', label: 'Failures' },
		{ value: 'response', label: 'Response time' }
	];
</script>

<div class="chip-row scroll" role="toolbar" aria-label="Range">
	{#each STAT_RANGES as d (d)}
		<Chip variant="general" selected={view.days === d} onclick={() => (view.days = d)}>{d} days</Chip>
	{/each}
</div>
<div class="chip-row scroll" role="toolbar" aria-label="Metric">
	{#each METRICS as m (m.value)}
		<Chip variant="general" selected={view.metric === m.value} onclick={() => (view.metric = m.value)}>{m.label}</Chip>
	{/each}
</div>

{#key view.days}
	<StatsView url={`${api}/stats?days=${view.days}`} metric={view.metric} />
{/key}

<style>
</style>
