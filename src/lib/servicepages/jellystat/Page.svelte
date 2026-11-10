<script lang="ts" module>
	import type { PeriodDays } from './types';
	// Remembered while navigating within the app (per page load), like the Activity tab.
	const ui = $state({ tab: 'overview', period: 30 as PeriodDays, history: { page: 1, q: '' } });
</script>

<script lang="ts">
	import { Tabs } from 'm3-svelte';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Overview from './Overview.svelte';
	import History from './History.svelte';
	import Libraries from './Libraries.svelte';

	let { service }: ServicePageProps = $props();
	const base = $derived(`/api/svc/jellystat/${service.id}`);

	const TABS = [
		{ name: 'Overview', value: 'overview' },
		{ name: 'History', value: 'history' },
		{ name: 'Libraries', value: 'libraries' }
	];
</script>

<Tabs items={TABS} bind:tab={ui.tab} secondary />

<div class="content">
	{#key base}
		{#if ui.tab === 'overview'}
			<Overview {base} bind:period={ui.period} />
		{:else if ui.tab === 'history'}
			<History {base} ui={ui.history} />
		{:else}
			<Libraries {base} />
		{/if}
	{/key}
</div>

<style>
	.content {
		padding-top: 1rem;
		min-width: 0;
	}
</style>
