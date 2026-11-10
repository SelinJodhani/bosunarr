<script lang="ts" module>
	// Survives leaving and returning to the page.
	let tab = $state('search');
</script>

<script lang="ts">
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '../types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import { sentence } from '../shared/format';
	import type { SummaryNotice } from '../shared/types';
	import type { Overview } from './types';
	import { base } from './api';
	import SearchTab from './SearchTab.svelte';
	import IndexersTab, { indexerView } from './IndexersTab.svelte';
	import StatsTab from './StatsTab.svelte';
	import HistoryTab from './HistoryTab.svelte';

	let { service, isAdmin }: ServicePageProps = $props();

	// The page is re-created per service ({#key} in /services/[id]), so reading the id once is fine.
	// svelte-ignore state_referenced_locally
	const api = base(service.id);
	const ov = live<Overview>(`${api}/overview`, 30_000);
	const d = $derived(ov.state.data);
	const enabled = $derived(d?.indexers.filter((i) => i.enabled) ?? []);
	const failing = $derived(enabled.filter((i) => i.failure).length);

	// Search fans out to every indexer, so it's admin-only (server enforces it too).
	// svelte-ignore state_referenced_locally
	if (!isAdmin && tab === 'search') tab = 'indexers';

	const items = $derived([
		...(isAdmin ? [{ name: 'Search', value: 'search' }] : []),
		{ name: 'Indexers', value: 'indexers' },
		{ name: 'Stats', value: 'stats' },
		{ name: 'History', value: 'history' }
	]);
	const TONE = { error: 'bad', warning: 'warn', notice: 'info' } as const;
	const notices = $derived<SummaryNotice[]>((d?.health ?? []).map((h) => ({ tone: /update/i.test(h.source) ? 'update' : TONE[h.type], text: h.message, href: h.wikiUrl ?? undefined })));

	function showIndexers(filter: 'all' | 'enabled' | 'failing') {
		indexerView.filter = filter;
		tab = 'indexers';
	}
</script>

{#if !d}
	{#if ov.state.error}
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(ov.state.error)} onretry={() => ov.refresh()} />
	{:else}
		<Loading label="Loading Prowlarr" />
	{/if}
{:else}
	<Summary
		meta={[d.version ? `Prowlarr ${d.version}` : 'Prowlarr']}
		stats={[
			{ value: d.indexers.length, label: 'Indexers', onclick: () => showIndexers('all') },
			{ value: enabled.length, label: 'Enabled', onclick: () => showIndexers('enabled') },
			{ value: failing, label: 'Failing', tone: failing ? 'bad' : undefined, onclick: () => showIndexers('failing') }
		]}
		{notices}
		stale={ov.state.error}
	/>

	<ServiceTabs {items} bind:tab>
		{#if tab === 'search' && isAdmin}
			<SearchTab {api} {isAdmin} indexers={d.indexers} />
		{:else if tab === 'indexers'}
			<IndexersTab {api} {isAdmin} indexers={d.indexers} refresh={ov.refresh} />
		{:else if tab === 'stats'}
			<StatsTab {api} />
		{:else}
			<HistoryTab {api} />
		{/if}
	</ServiceTabs>
{/if}
