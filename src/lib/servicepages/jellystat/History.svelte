<script lang="ts">
	import { Button, Icon, TextField } from 'm3-svelte';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iClose from '@ktibow/iconset-material-symbols/close-rounded';
	import iPrev from '@ktibow/iconset-material-symbols/chevron-left-rounded';
	import iNext from '@ktibow/iconset-material-symbols/chevron-right-rounded';
	import HistoryList from './HistoryList.svelte';

	let { base, ui }: { base: string; ui: { page: number; q: string } } = $props();

	const SIZE = 25;
	// svelte-ignore state_referenced_locally
	let input = $state(ui.q);
	let pages = $state(0);

	// Debounce typing into the applied search; only writes `ui`, never reads it.
	$effect(() => {
		const v = input.trim();
		const t = setTimeout(() => {
			if (v !== ui.q) {
				ui.q = v;
				ui.page = 1;
			}
		}, 350);
		return () => clearTimeout(t);
	});

	const url = $derived(`${base}/history?${new URLSearchParams({ page: String(ui.page), size: String(SIZE), ...(ui.q ? { q: ui.q } : {}) })}`);
</script>

<div class="stack">
	<div class="search">
		<TextField
			label="Search titles"
			leadingIcon={iSearch}
			bind:value={input}
			type="search"
			autocomplete="off"
			enterkeyhint="search"
			maxlength={100}
			trailing={input ? { icon: iClose, onclick: () => (input = ''), 'aria-label': 'Clear search' } : undefined}
		/>
	</div>

	{#key url}
		<HistoryList {url} searching={Boolean(ui.q)} onpages={(n) => {
			pages = n;
			if (n > 0 && ui.page > n) ui.page = n;
		}} />
	{/key}

	{#if pages > 1}
		<nav class="pager" aria-label="History pages">
			<Button variant="tonal" iconType="full" aria-label="Previous page" disabled={ui.page <= 1} onclick={() => (ui.page = Math.max(1, ui.page - 1))}>
				<Icon icon={iPrev} />
			</Button>
			<span aria-live="polite">Page {ui.page} of {pages}</span>
			<Button variant="tonal" iconType="full" aria-label="Next page" disabled={ui.page >= pages} onclick={() => (ui.page = Math.min(pages, ui.page + 1))}>
				<Icon icon={iNext} />
			</Button>
		</nav>
	{/if}
</div>

<style>
	.stack {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.search {
		max-width: 28rem;
		display: grid;
	}
	.pager {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 1rem;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
		font-variant-numeric: tabular-nums;
	}
</style>
