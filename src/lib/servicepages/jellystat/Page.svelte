<script lang="ts" module>
	import type { PeriodDays } from './types';
	// Remembered while navigating within the app (per page load), like the Activity tab.
	const ui = $state({ tab: 'overview', period: 30 as PeriodDays, history: {} as Record<number, { page: number; q: string }> });
</script>

<script lang="ts">
	import { Button, ConnectedButtons } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Overview from './Overview.svelte';
	import History from './History.svelte';
	import Libraries from './Libraries.svelte';
	import { PERIODS, type JsOverview } from './types';
	import { count, duration } from './format';

	let { service }: ServicePageProps = $props();
	// The parent re-keys this page per service, so the id is fixed for this instance.
	// svelte-ignore state_referenced_locally
	const base = `/api/svc/jellystat/${service.id}`;
	// svelte-ignore state_referenced_locally
	ui.history[service.id] ??= { page: 1, q: '' };
	// Read back through the state proxy so changes are reactive.
	// svelte-ignore state_referenced_locally
	const hist = ui.history[service.id];

	const q = live<JsOverview>(`${base}/overview`, 60_000);
	const d = $derived(q.state.data && Array.isArray(q.state.data.daily) ? q.state.data : undefined);
	const stats = $derived(d?.periods.find((p) => p.days === ui.period));

	const TABS = [
		{ name: 'Overview', value: 'overview' },
		{ name: 'History', value: 'history' },
		{ name: 'Libraries', value: 'libraries' }
	];
</script>

<Summary
	meta={['Jellystat', `Last ${ui.period} days`]}
	stats={[
		{ value: stats ? count(stats.plays) : null, label: 'Plays' },
		{ value: stats ? duration(stats.minutes * 60) : null, label: 'Watch time' },
		{ value: stats ? count(stats.activeUsers) : null, label: 'Active users' }
	]}
	notices={q.state.error && !d ? [{ tone: 'bad', text: `Couldn't load stats. ${q.state.error}` }] : []}
	stale={d ? q.state.error : null}
>
	<div class="period">
		<span class="plabel" id="js-period">Period</span>
		<ConnectedButtons>
			{#each PERIODS as p (p)}
				<Button variant={ui.period === p ? 'filled' : 'tonal'} size="s" aria-pressed={ui.period === p} aria-label={`Last ${p} days`} onclick={() => (ui.period = p)}>{p} days</Button>
			{/each}
		</ConnectedButtons>
	</div>
</Summary>

<ServiceTabs items={TABS} bind:tab={ui.tab}>
	{#if ui.tab === 'overview'}
		<Overview {base} data={d} loading={q.state.loading} error={q.state.error} period={ui.period} onretry={() => q.refresh()} />
	{:else if ui.tab === 'history'}
		<History
			{base}
			page={hist.page}
			q={hist.q}
			onpage={(p) => (hist.page = p)}
			onquery={(v) => {
				hist.q = v;
				hist.page = 1;
			}}
		/>
	{:else}
		<Libraries {base} />
	{/if}
</ServiceTabs>

<style>
	.period {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		flex-wrap: wrap;
	}
	.plabel {
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
