<script lang="ts">
	import { Button, ConnectedButtons } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import State from '$lib/widgets/State.svelte';
	import NowPlaying from './NowPlaying.svelte';
	import PlaysChart from './PlaysChart.svelte';
	import TopLists from './TopLists.svelte';
	import { PERIODS, type JsOverview, type PeriodDays } from './types';
	import { count, duration } from './format';

	let { base, period = $bindable() }: { base: string; period: PeriodDays } = $props();

	// The page is re-keyed per service, so `base` is fixed per instance.
	// svelte-ignore state_referenced_locally
	const q = live<JsOverview>(`${base}/overview`, 60_000);
	const d = $derived(q.state.data && Array.isArray(q.state.data.daily) ? q.state.data : undefined);
	const stats = $derived(d?.periods.find((p) => p.days === period));
	const daily = $derived(d ? d.daily.slice(-period) : []);
</script>

<div class="stack">
	<NowPlaying {base} />

	<div class="period">
		<h2 class="title-medium">Last {period} days</h2>
		<ConnectedButtons>
			{#each PERIODS as p (p)}
				<Button variant={period === p ? 'filled' : 'tonal'} size="s" aria-pressed={period === p} onclick={() => (period = p)}>{p}d</Button>
			{/each}
		</ConnectedButtons>
	</div>

	<State loading={q.state.loading} error={q.state.error} empty={!d}>
		<div class="cards">
			<div class="stat">
				<span class="label">Plays</span>
				<span class="value">{count(stats?.plays ?? 0)}</span>
			</div>
			<div class="stat">
				<span class="label">Watch time</span>
				<span class="value">{duration((stats?.minutes ?? 0) * 60)}</span>
			</div>
			<div class="stat">
				<span class="label">Active users</span>
				<span class="value">{count(stats?.activeUsers ?? 0)}</span>
			</div>
		</div>

		<section class="card" aria-labelledby="js-chart">
			<h3 id="js-chart" class="title-small">Plays per day</h3>
			<PlaysChart days={daily} />
		</section>
	</State>

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
	.period {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		flex-wrap: wrap;
		margin-top: 0.5rem;
	}
	.period h2 {
		margin: 0;
	}
	.cards {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0.75rem;
		margin-bottom: 0.75rem;
	}
	.stat {
		padding: 0.875rem 1rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		display: grid;
		gap: 0.25rem;
		min-width: 0;
	}
	.label {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.value {
		font-size: 1.375rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.card {
		padding: 1rem 1.125rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	h3 {
		margin: 0 0 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	@media (max-width: 26rem) {
		.stat {
			padding: 0.75rem;
		}
		.value {
			font-size: 1.125rem;
		}
	}
</style>
