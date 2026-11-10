<script lang="ts" module>
	import type { PeriodDays } from './types';
	// Remembered while navigating within the app (per page load), like the Activity tab.
	const ui = $state({ tab: 'overview', period: 30 as PeriodDays, history: {} as Record<number, { page: number; q: string }> });
</script>

<script lang="ts">
	import { Tabs } from 'm3-svelte';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Overview from './Overview.svelte';
	import History from './History.svelte';
	import Libraries from './Libraries.svelte';

	let { service }: ServicePageProps = $props();
	const base = $derived(`/api/svc/jellystat/${service.id}`);
	// History paging/search is per instance. The parent re-keys this page per service, so init once.
	// svelte-ignore state_referenced_locally
	ui.history[service.id] ??= { page: 1, q: '' };

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
			<History {base} ui={ui.history[service.id]} />
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
