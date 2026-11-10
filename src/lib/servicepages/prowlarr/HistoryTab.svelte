<script lang="ts" module>
	type Filter = 'all' | 'query' | 'grab' | 'rss' | 'failed';
	const view = $state({ filter: 'all' as Filter });
</script>

<script lang="ts">
	import { Chip, Icon } from 'm3-svelte';
	import iHistory from '@ktibow/iconset-material-symbols/history-rounded';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import Pager from '../shared/Pager.svelte';
	import { sentence } from '../shared/format';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iGrab from '@ktibow/iconset-material-symbols/download-rounded';
	import iRss from '@ktibow/iconset-material-symbols/rss-feed-rounded';
	import iKey from '@ktibow/iconset-material-symbols/key-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import type { HistoryEntry, HistoryResponse } from './types';
	import '../shared/chips.css';

	let { api }: { api: string } = $props();

	// svelte-ignore state_referenced_locally
	const h = live<HistoryResponse>(`${api}/history`, 30_000);
	const records = $derived(h.state.data?.records ?? []);

	const FILTERS: Array<{ value: Filter; label: string; test: (e: HistoryEntry) => boolean }> = [
		{ value: 'all', label: 'All', test: () => true },
		{ value: 'query', label: 'Searches', test: (e) => e.kind === 'query' },
		{ value: 'grab', label: 'Grabs', test: (e) => e.kind === 'grab' },
		{ value: 'rss', label: 'RSS', test: (e) => e.kind === 'rss' },
		{ value: 'failed', label: 'Failed', test: (e) => !e.successful }
	];
	const counts = $derived(Object.fromEntries(FILTERS.map((f) => [f.value, records.filter(f.test).length])) as Record<Filter, number>);
	const list = $derived(records.filter((FILTERS.find((f) => f.value === view.filter) ?? FILTERS[0]).test));
	// Prowlarr returns the latest 100 events; show them 25 at a time.
	const SIZE = 25;
	let page = $state(1);
	const pages = $derived(Math.max(1, Math.ceil(list.length / SIZE)));
	const shown = $derived(list.slice((Math.min(page, pages) - 1) * SIZE, Math.min(page, pages) * SIZE));
	function setFilter(f: Filter) {
		view.filter = f;
		page = 1;
	}

	const ICON = { query: iSearch, grab: iGrab, rss: iRss, auth: iKey, other: iInfo };
	const KIND = { query: 'Search', grab: 'Grab', rss: 'RSS', auth: 'Login', other: 'Event' };
	const fallback = (e: HistoryEntry) => (e.kind === 'rss' ? 'RSS sync' : e.kind === 'query' ? '(no query text)' : KIND[e.kind]);
</script>

<div class="chip-row scroll" role="toolbar" aria-label="Filter history">
	{#each FILTERS as f (f.value)}
		{#if f.value === 'all' || counts[f.value] > 0 || view.filter === f.value}
			<!-- Prowlarr only sends the latest events, so "All" says how many that is rather than implying a total. -->
			<Chip variant="general" selected={view.filter === f.value} onclick={() => setFilter(f.value)}
				>{f.value === 'all' ? `Latest ${counts.all}` : f.label}{#if f.value !== 'all'}<span class="chip-n">{counts[f.value]}</span>{/if}</Chip
			>
		{/if}
	{/each}
</div>

{#if !h.state.data}
	{#if h.state.error}
		<Empty error title="Couldn't load history" hint={sentence(h.state.error)} onretry={() => h.refresh()} />
	{:else}
		<Loading label="Loading history" />
	{/if}
{:else if list.length === 0}
	<Empty icon={iHistory} title={records.length ? 'Nothing matches this filter' : 'No history yet'} hint={records.length ? null : 'Searches, grabs and RSS syncs show up here.'} />
{:else}
	<ul class="list">
		{#each shown as e (e.id)}
			<li class="row" class:failed={!e.successful}>
				<span class="ic k-{e.kind}" title={KIND[e.kind]}><Icon icon={ICON[e.kind]} size={20} /></span>
				<div class="body">
					<span class="top">
						<span class="title">{e.title || fallback(e)}</span>
						<span class="when">{ago(e.date)}</span>
					</span>
					<span class="meta">
						<span class="idx">{e.indexer ?? `Indexer ${e.indexerId}`}</span>
						{#if !e.successful}
							<span class="bad">Failed</span>
						{:else if e.kind !== 'grab' && e.results !== null}
							<span>{e.results} results</span>
						{/if}
						{#if e.source}<span class="src">{e.source}</span>{/if}
						{#if e.elapsedMs !== null}<span>{e.elapsedMs.toLocaleString()} ms</span>{/if}
					</span>
				</div>
			</li>
		{/each}
	</ul>
	<Pager page={Math.min(page, pages)} {pages} total={list.length} noun="event" onpage={(p) => (page = p)} />
	{#if h.state.data.total > records.length}
		<p class="more body-small">The latest {records.length} of {h.state.data.total.toLocaleString()} events. Older ones are in Prowlarr.</p>
	{/if}
{/if}

<style>
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.625rem 1rem 0.625rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		content-visibility: auto;
		contain-intrinsic-size: auto 3.75rem;
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 50%;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.k-grab {
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.failed .ic {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	/* Title and time share the first line (time pinned right); the facts get one line under it. */
	.top {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: baseline;
		gap: 0.75rem;
	}
	.title {
		font-size: 0.875rem;
		font-weight: 500;
		overflow-wrap: anywhere;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.meta {
		display: flex;
		align-items: center;
		overflow: hidden;
		white-space: nowrap;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		font-variant-numeric: tabular-nums;
	}
	.meta > * {
		flex: none;
	}
	.meta > * + *::before {
		content: '\00a0·\00a0';
	}
	.idx {
		font-weight: 600;
		flex: 0 1 auto;
		min-width: 2rem;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.src {
		flex: 0 100 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.when {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
		font-variant-numeric: tabular-nums;
	}
	.bad {
		color: var(--m3c-error);
		font-weight: 600;
	}
	.more {
		margin: 0;
		color: var(--m3c-on-surface-variant);
	}
</style>
