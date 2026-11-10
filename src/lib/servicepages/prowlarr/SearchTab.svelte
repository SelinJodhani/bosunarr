<script lang="ts" module>
	import type { SearchResult } from './types';
	type Sort = 'seeders' | 'age' | 'size' | 'sizeAsc' | 'title' | 'indexer';
	// Survives switching tabs and leaving the page (keyed by service so instances don't mix).
	const view = $state({
		api: '',
		q: '',
		cats: [] as number[],
		indexers: [] as number[],
		sort: 'seeders' as Sort,
		results: null as SearchResult[] | null,
		truncated: false,
		searched: ''
	});
	/** The search in flight, shared across mounts so a new one (or another instance) cancels it. */
	let controller: AbortController | null = null;
</script>

<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { BottomSheet, Button, Checkbox, Chip, Icon, LoadingIndicator, TextField, snackbar } from 'm3-svelte';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iClose from '@ktibow/iconset-material-symbols/close-rounded';
	import iTune from '@ktibow/iconset-material-symbols/tune-rounded';
	import iSend from '@ktibow/iconset-material-symbols/send-rounded';
	import iOpen from '@ktibow/iconset-material-symbols/open-in-new-rounded';
	import iUp from '@ktibow/iconset-material-symbols/arrow-upward-rounded';
	import iDown from '@ktibow/iconset-material-symbols/arrow-downward-rounded';
	import iSearchOff from '@ktibow/iconset-material-symbols/search-off-rounded';
	import iManage from '@ktibow/iconset-material-symbols/manage-search-rounded';
	import Empty from '../shared/Empty.svelte';
	import { protocolLabel, sentence } from '../shared/format';
	import SortMenu from '$lib/components/SortMenu.svelte';
	import { getJSON } from '$lib/http';
	import { bytes } from '$lib/format';
	import { CATEGORIES, type Indexer, type SearchResponse } from './types';
	import { ageLabel, post } from './api';

	let { api, isAdmin, indexers }: { api: string; isAdmin: boolean; indexers: Indexer[] } = $props();

	// Reset saved state when showing a different Prowlarr instance.
	$effect(() => {
		const a = api;
		untrack(() => {
			if (view.api !== a) {
				controller?.abort();
				Object.assign(view, { api: a, q: '', cats: [], indexers: [], results: null, truncated: false, searched: '' });
			}
		});
	});

	const SORTS = [
		{ value: 'seeders', text: 'Seeders' },
		{ value: 'age', text: 'Newest' },
		{ value: 'size', text: 'Largest' },
		{ value: 'sizeAsc', text: 'Smallest' },
		{ value: 'title', text: 'Title' },
		{ value: 'indexer', text: 'Indexer' }
	];

	let busy = $state(false);
	let error = $state<string | null>(null);
	let pickerOpen = $state(false);
	let openToken = $state<string | null>(null);
	let grabbing = $state(false);
	/** Seconds since the search started, for the progress text. */
	let elapsed = $state(0);
	let tick: ReturnType<typeof setInterval> | undefined;
	$effect(() => () => clearInterval(tick));

	const enabledIndexers = $derived(indexers.filter((i) => i.enabled));
	const open = $derived(openToken ? view.results?.find((r) => r.token === openToken) : undefined);

	async function run() {
		const q = view.q.trim();
		if (!q) return; // a new search replaces (aborts) the one in flight
		// Hide the phone keyboard so the results are visible.
		if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
		controller?.abort();
		const mine = new AbortController();
		controller = mine;
		const forApi = api;
		busy = true;
		error = null;
		elapsed = 0;
		clearInterval(tick);
		tick = setInterval(() => elapsed++, 1000);
		const qs = new URLSearchParams({ q });
		for (const c of view.cats) qs.append('cat', String(c));
		for (const i of view.indexers) qs.append('indexer', String(i));
		try {
			const r = await getJSON<SearchResponse>(`${forApi}/search?${qs}`, { signal: mine.signal });
			// Drop late answers: a newer search started, or the page now shows another instance.
			if (controller !== mine || view.api !== forApi) return;
			view.results = r.results;
			view.truncated = r.truncated;
			view.searched = q;
		} catch (e) {
			if (mine.signal.aborted || view.api !== forApi) return;
			error = e instanceof Error ? e.message : String(e);
		} finally {
			if (controller === mine) {
				controller = null;
				busy = false;
				clearInterval(tick);
			}
		}
	}

	function cancel() {
		controller?.abort();
		controller = null;
		busy = false;
		clearInterval(tick);
	}

	function toggleCat(id: number) {
		view.cats = view.cats.includes(id) ? view.cats.filter((c) => c !== id) : [...view.cats, id];
	}
	function toggleIndexer(id: number) {
		view.indexers = view.indexers.includes(id) ? view.indexers.filter((c) => c !== id) : [...view.indexers, id];
	}

	const sorted = $derived.by(() => {
		const list = [...(view.results ?? [])];
		const age = (r: SearchResult) => r.ageHours ?? Infinity;
		const by: Record<Sort, (a: SearchResult, b: SearchResult) => number> = {
			seeders: (a, b) => (b.seeders ?? -1) - (a.seeders ?? -1) || (b.grabs ?? 0) - (a.grabs ?? 0),
			age: (a, b) => age(a) - age(b),
			size: (a, b) => b.size - a.size,
			sizeAsc: (a, b) => a.size - b.size,
			title: (a, b) => a.title.localeCompare(b.title),
			indexer: (a, b) => a.indexer.localeCompare(b.indexer) || (b.seeders ?? -1) - (a.seeders ?? -1)
		};
		return list.sort(by[view.sort]);
	});

	const indexerLabel = $derived(
		view.indexers.length === 0
			? 'All indexers'
			: view.indexers.length === 1
				? (indexers.find((i) => i.id === view.indexers[0])?.name ?? '1 indexer')
				: `${view.indexers.length} indexers`
	);

	const searchCount = $derived(view.indexers.length || enabledIndexers.length);

	// Sending is the confirmation: the sheet already shows exactly what will be sent.
	async function grab() {
		if (!open || grabbing) return;
		grabbing = true;
		const err = await post(`${api}/grab`, { token: open.token });
		grabbing = false;
		if (err) {
			snackbar(`Couldn't send it. ${sentence(err)}`, {}, true);
			return;
		}
		openToken = null;
		snackbar(`Sent to the ${open?.protocol === 'usenet' ? 'Usenet' : 'torrent'} client`, { 'Open Activity': () => goto('/activity') }, true);
	}
</script>

<div class="toolbar">
	<div class="search">
		<TextField
			label="Search indexers"
			leadingIcon={iSearch}
			type="search"
			autocomplete="off"
			enterkeyhint="search"
			maxlength={200}
			bind:value={view.q}
			enter={run}
			trailing={view.q ? { icon: iClose, onclick: () => (view.q = ''), 'aria-label': 'Clear search' } : undefined}
		/>
	</div>
	<Button variant="filled" iconType="full" aria-label="Search" disabled={!view.q.trim()} onclick={run}><Icon icon={iSearch} /></Button>
	<SortMenu options={SORTS} bind:value={view.sort} />
</div>

<div class="chips" role="toolbar" aria-label="Search filters">
	<Chip variant="general" icon={iTune} selected={view.indexers.length > 0} onclick={() => (pickerOpen = true)}>{indexerLabel}</Chip>
	{#each CATEGORIES as c (c.id)}
		<Chip variant="general" selected={view.cats.includes(c.id)} onclick={() => toggleCat(c.id)}>{c.name}</Chip>
	{/each}
</div>

{#if busy}
	<div class="progress" role="status">
		<LoadingIndicator aria-label="Searching" />
		<p class="p-title">Searching {searchCount} indexer{searchCount === 1 ? '' : 's'}…</p>
		<p class="p-sub">{elapsed < 5 ? 'Results appear once every indexer has answered.' : `${elapsed} s — slow indexers can take up to a minute.`}</p>
		<Button variant="tonal" onclick={cancel}>Cancel</Button>
	</div>
{:else if error}
	<Empty error title="The search failed" hint={sentence(error)} onretry={run} />
{:else if view.results === null}
	<Empty icon={iManage} title="Search your indexers" hint={`Searches ${enabledIndexers.length ? `all ${enabledIndexers.length} enabled indexers` : 'every enabled indexer'}. Pick categories or indexers above to narrow it down.`} />
{:else if sorted.length === 0}
	<Empty icon={iSearchOff} title={`No results for “${view.searched}”`} hint="Try fewer words, or fewer category filters." />
{:else}
	<p class="count body-small">
		{sorted.length} results for “{view.searched}”{view.truncated ? ' (showing the first 500)' : ''}
	</p>
	<ul class="list">
		{#each sorted as r (r.token)}
			<li>
				<button class="row" onclick={() => (openToken = r.token)} aria-haspopup="dialog">
					<span class="title">{r.title}</span>
					<span class="meta">
						<span class="idx">{r.indexer}</span>
						<span class="proto p-{r.protocol}">{protocolLabel(r.protocol)}</span>
					</span>
					<span class="meta">
						<span class="fact">{bytes(r.size)}</span>
						<span class="fact">{ageLabel(r.ageHours)}</span>
						{#if r.protocol === 'torrent'}
							<span class="fact peers" class:dead={r.seeders === 0} aria-label={`${r.seeders ?? 'unknown'} seeders, ${r.leechers ?? 'unknown'} peers`}>
								<Icon icon={iUp} size={14} />{r.seeders ?? '—'}
								<Icon icon={iDown} size={14} />{r.leechers ?? '—'}
							</span>
						{/if}
					</span>
				</button>
			</li>
		{/each}
	</ul>
{/if}

{#if pickerOpen}
	<BottomSheet close={() => (pickerOpen = false)}>
		<div class="sheet">
			<h2 class="sheet-title">Search these indexers</h2>
			<p class="sheet-sub">Leave all unticked to search every enabled indexer.</p>
			<ul class="picks">
				{#each enabledIndexers as i (i.id)}
					<li>
						<label class="check">
							<Checkbox><input type="checkbox" checked={view.indexers.includes(i.id)} onchange={() => toggleIndexer(i.id)} /></Checkbox>
							<span class="pick-name">{i.name}</span>
							<span class="proto p-{i.protocol}">{protocolLabel(i.protocol)}</span>
						</label>
					</li>
				{:else}
					<li class="empty">No enabled indexers.</li>
				{/each}
			</ul>
			<div class="actions">
				<Button variant="text" disabled={!view.indexers.length} onclick={() => (view.indexers = [])}>Clear</Button>
				<Button variant="filled" onclick={() => (pickerOpen = false)}>Done</Button>
			</div>
		</div>
	</BottomSheet>
{/if}

{#if open}
	<BottomSheet close={() => (openToken = null)}>
		<div class="sheet">
			<h2 class="sheet-title">{open.title}</h2>
			<p class="sheet-sub">{open.indexer}</p>
			<dl>
				<dt>Size</dt><dd>{bytes(open.size)}</dd>
				<dt>Age</dt><dd>{ageLabel(open.ageHours)}{open.publishDate ? ` · ${new Date(open.publishDate).toLocaleDateString()}` : ''}</dd>
				<dt>Protocol</dt><dd>{protocolLabel(open.protocol)}</dd>
				{#if open.protocol === 'torrent'}<dt>Seeders / peers</dt><dd>{open.seeders ?? '—'} / {open.leechers ?? '—'}</dd>{/if}
				{#if open.grabs !== null}<dt>Grabs</dt><dd>{open.grabs.toLocaleString()}</dd>{/if}
				{#if open.categories.length}<dt>Categories</dt><dd>{open.categories.join(', ')}</dd>{/if}
			</dl>
			<div class="actions">
				{#if isAdmin}
					<Button variant="filled" disabled={grabbing} onclick={grab}><Icon icon={iSend} /> {grabbing ? 'Sending…' : 'Send to download client'}</Button>
				{/if}
				{#if open.infoUrl}
					<Button variant="tonal" href={open.infoUrl} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} /> Indexer page</Button>
				{/if}
			</div>
		</div>
	</BottomSheet>
{/if}


<style>
	.toolbar {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}
	.search {
		flex: 1;
		min-width: 0;
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.search :global(input::-webkit-search-cancel-button) {
		display: none;
	}
	.chips {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		scrollbar-width: none;
	}
	.progress {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		justify-items: center;
		gap: 0.25rem;
		padding: 2rem 1rem;
		text-align: center;
	}
	.p-title {
		margin: 0.5rem 0 0;
		font-weight: 500;
	}
	.p-sub {
		margin: 0 0 0.75rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.chips > :global(*) {
		flex: none;
	}
	.empty,
	.count {
		color: var(--m3c-on-surface-variant);
	}
	.count {
		margin: 0;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.list li {
		content-visibility: auto;
		contain-intrinsic-size: auto 5.5rem;
	}
	.row {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		width: 100%;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
		padding: 0.75rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		transition: background-color 150ms;
	}
	.row:hover {
		background: var(--m3c-surface-container);
	}
	.row:focus-visible {
		outline: 2px solid var(--m3c-primary);
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
		gap: 0.75rem;
		min-width: 0;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		font-variant-numeric: tabular-nums;
	}
	.idx {
		flex: 1;
		font-weight: 600;
		color: var(--m3c-primary);
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.fact {
		flex: none;
		white-space: nowrap;
	}
	.peers {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
	}
	.peers.dead {
		color: var(--m3c-error);
	}
	.proto {
		flex: none;
		margin-left: auto;
		padding: 0 0.375rem;
		white-space: nowrap;
		border-radius: 0.375rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
		font-size: 0.75rem;
	}
	.p-usenet {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.sheet {
		padding: 0 1.5rem 1.5rem;
		max-width: 40rem;
		margin: 0 auto;
	}
	.sheet-title {
		font-size: 1.125rem;
		line-height: 1.4;
		overflow-wrap: anywhere;
	}
	.sheet-sub {
		margin: 0.25rem 0 0.75rem;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 0.375rem 1rem;
		margin: 1rem 0;
		font-size: 0.875rem;
	}
	dt {
		color: var(--m3c-on-surface-variant);
	}
	dd {
		margin: 0;
		overflow-wrap: anywhere;
		font-variant-numeric: tabular-nums;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.picks {
		list-style: none;
		margin: 0 0 1rem;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	.check {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
		padding: 0.25rem 0;
	}
	.pick-name {
		flex: 1;
		min-width: 0;
		overflow-wrap: anywhere;
	}
</style>
