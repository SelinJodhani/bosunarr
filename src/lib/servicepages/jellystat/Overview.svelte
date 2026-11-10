<script lang="ts">
	import iChart from '@ktibow/iconset-material-symbols/bar-chart-rounded';
	import Card from '../shared/Card.svelte';
	import Empty from '../shared/Empty.svelte';
	import { sentence } from '../shared/format';
	import NowPlaying from './NowPlaying.svelte';
	import PlaysChart from './PlaysChart.svelte';
	import TopLists from './TopLists.svelte';
	import type { JsOverview, PeriodDays } from './types';

	let {
		base,
		data,
		loading,
		error,
		period,
		onretry
	}: { base: string; data: JsOverview | undefined; loading: boolean; error: string | null; period: PeriodDays; onretry: () => void } = $props();

	const daily = $derived(data ? data.daily.slice(-period) : []);
</script>

<div class="stack">
	<NowPlaying {base} />

	<Card title="Plays per day" icon={iChart}>
		{#if data}
			<PlaysChart days={daily} />
		{:else if error && !loading}
			<Empty error title="Couldn't load plays" hint={sentence(error)} {onretry} />
		{:else}
			<!-- Same height as the chart so nothing jumps when it arrives. -->
			<div class="skeleton" aria-label="Loading chart" role="status"></div>
		{/if}
	</Card>

	{#key period}
		<TopLists url={`${base}/top?days=${period}`} />
	{/key}
</div>

<style>
	.stack {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.skeleton {
		height: 16.125rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container);
	}
</style>
