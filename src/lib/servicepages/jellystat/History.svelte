<script lang="ts">
	import { TextField } from 'm3-svelte';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iClose from '@ktibow/iconset-material-symbols/close-rounded';
	import Pager from '../shared/Pager.svelte';
	import HistoryList from './HistoryList.svelte';

	let {
		base,
		page,
		q,
		onpage,
		onquery
	}: { base: string; page: number; q: string; onpage: (p: number) => void; onquery: (q: string) => void } = $props();

	const SIZE = 25;
	// svelte-ignore state_referenced_locally
	let input = $state(q);
	let pages = $state(0);

	// Debounce typing into the applied search (the owner of `q` resets the page).
	$effect(() => {
		const v = input.trim();
		const t = setTimeout(() => v !== q && onquery(v), 350);
		return () => clearTimeout(t);
	});

	/** Enter searches right away and closes the phone keyboard. */
	function search() {
		if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
		const v = input.trim();
		if (v !== q) onquery(v);
	}

	const url = $derived(`${base}/history?${new URLSearchParams({ page: String(page), size: String(SIZE), ...(q ? { q } : {}) })}`);
</script>

<div class="search">
	<TextField
		label="Search titles"
		leadingIcon={iSearch}
		bind:value={input}
		type="search"
		autocomplete="off"
		enterkeyhint="search"
		maxlength={100}
		enter={search}
		trailing={input ? { icon: iClose, onclick: () => (input = ''), 'aria-label': 'Clear search' } : undefined}
	/>
</div>

{#key url}
	<HistoryList
		{url}
		searching={Boolean(q)}
		onpages={(n) => {
			pages = n;
			if (n > 0 && page > n) onpage(n);
		}}
	/>
{/key}

<Pager {page} {pages} onpage={onpage} noun="play" />

<style>
	.search {
		max-width: 28rem;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.search :global(input::-webkit-search-cancel-button) {
		display: none;
	}
</style>
