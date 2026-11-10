<script lang="ts" module>
	import type { DiscoverItem } from './types';
	/** Pages already loaded per source, so going back from a detail page paints instantly where you left off. */
	const memo = new Map<string, { items: DiscoverItem[]; page: number; totalPages: number; totalResults: number }>();
</script>

<script lang="ts">
	import { getJSON } from '$lib/http';
	import { LoadingIndicator } from 'm3-svelte';
	import { untrack } from 'svelte';
	import MediaCard from './MediaCard.svelte';
	import Empty from './Empty.svelte';
	import { iSearchOff } from './icons';
	import { remember, uniq } from './client';
	import type { DiscoverPage } from './types';

	/** `url` is the list endpoint without `page`; the parent re-mounts this component ({#key}) when it changes. */
	let { url, emptyText = 'Nothing found', showType = true }: { url: string; emptyText?: string; showType?: boolean } = $props();

	// svelte-ignore state_referenced_locally
	const start = memo.get(url);
	let items = $state<DiscoverItem[]>(start?.items ?? []);
	let page = $state(start?.page ?? 0);
	let totalPages = $state(start?.totalPages ?? 1);
	let totalResults = $state(start?.totalResults ?? 0);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let sentinel = $state<HTMLElement>();

	// TMDb lists report hundreds of pages; past the first few it's obscure filler and the page never ends.
	const MAX_PAGES = 10;
	const capped = $derived(totalPages > MAX_PAGES);
	const done = $derived(page >= Math.min(totalPages, MAX_PAGES));

	async function load() {
		if (loading || done) return;
		loading = true;
		error = null;
		const next = page + 1;
		try {
			const body = await getJSON<DiscoverPage>(`${url}${url.includes('?') ? '&' : '?'}page=${next}`);
			remember(body.items);
			items = uniq([...items, ...body.items]);
			page = next;
			totalPages = body.totalPages;
			totalResults = body.totalResults;
			memo.set(url, { items, page, totalPages, totalResults });
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	// Track whether the sentinel is near the viewport; keep loading pages while it stays there
	// (the observer only fires on changes, so a short first page would otherwise stall).
	let near = $state(false);
	$effect(() => {
		const el = sentinel;
		if (!el) return;
		const io = new IntersectionObserver((e) => (near = Boolean(e[0]?.isIntersecting)), { rootMargin: '800px 0px' });
		io.observe(el);
		return () => io.disconnect();
	});
	$effect(() => {
		if ((near || page === 0) && !loading && !done && !error) untrack(load);
	});
</script>

<div class="pg">
{#if items.length}
	{#if !capped}<p class="count body-small">{totalResults.toLocaleString()} result{totalResults === 1 ? '' : 's'}</p>{/if}
	<ul class="grid">
		{#each items as it (it.key)}
			<li><MediaCard item={it} {showType} /></li>
		{/each}
	</ul>
{:else if page === 0 && !error}
	<ul class="grid" aria-hidden="true">
		{#each { length: 9 } as _, i (i)}
			<li class="sk"><span class="sk-art"></span><span class="sk-line"></span><span class="sk-line short"></span></li>
		{/each}
	</ul>
{:else if !loading && !error}
	<Empty icon={iSearchOff} title={emptyText} />
{/if}

<div bind:this={sentinel} class="sentinel">
	{#if loading && items.length}
		<LoadingIndicator aria-label="Loading more" />
	{:else if error}
		<p class="error" role="alert">{error} <button class="retry" onclick={load}>Retry</button></p>
	{:else if done && capped && items.length}
		<p class="end body-small">That's the top {items.length}. Search to find anything else.</p>
	{/if}
</div>
</div>

<style>
	.end {
		text-align: center;
		color: var(--m3c-on-surface-variant);
		padding: 1rem 0 0.5rem;
	}
	.count {
		margin: 0 0 0.5rem;
		color: var(--m3c-on-surface-variant);
	}
	/* Same card sizing as the Library grid (measured on the grid's own width): three columns on
	   360–412 px phones, more as space allows. */
	.pg {
		container-type: inline-size;
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(6rem, 1fr));
		gap: 0.75rem;
	}
	@container (width >= 35rem) {
		.grid {
			grid-template-columns: repeat(auto-fill, minmax(7.75rem, 1fr));
		}
	}
	@container (width >= 56.25rem) {
		.grid {
			grid-template-columns: repeat(auto-fill, minmax(8.75rem, 1fr));
			gap: 1rem;
		}
	}
	li {
		min-width: 0;
		content-visibility: auto;
		contain-intrinsic-size: auto 15rem;
	}
	.sentinel {
		display: grid;
		place-items: center;
		min-height: 4rem;
		padding: 1rem 0;
	}
	.sk {
		display: grid;
		gap: 0.375rem;
		content-visibility: visible;
	}
	.sk-art,
	.sk-line {
		display: block;
		border-radius: 0.75rem;
		background: var(--m3c-surface-container-highest);
		animation: pulse 1.2s ease-in-out infinite alternate;
	}
	.sk-art {
		aspect-ratio: 2 / 3;
	}
	.sk-line {
		height: 0.75rem;
		border-radius: 0.375rem;
	}
	.sk-line.short {
		width: 45%;
	}
	@keyframes pulse {
		to {
			opacity: 0.5;
		}
	}
	.error {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.retry {
		all: unset;
		cursor: pointer;
		font-weight: 500;
		color: var(--m3c-primary);
		margin-left: 0.25rem;
		padding: 0 0.5rem;
		min-height: 2.5rem;
		display: inline-flex;
		align-items: center;
	}
</style>
