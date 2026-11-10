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
	import { remember, uniq } from './client';
	import type { DiscoverPage } from './types';

	/** `url` is the list endpoint without `page`; the parent re-mounts this component ({#key}) when it changes. */
	let { url, emptyText = 'Nothing found' }: { url: string; emptyText?: string } = $props();

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

{#if items.length}
	<p class="count body-small">{totalResults.toLocaleString()} result{totalResults === 1 ? '' : 's'}</p>
	<ul class="grid">
		{#each items as it (it.key)}
			<li><MediaCard item={it} /></li>
		{/each}
	</ul>
{:else if !loading && !error && page > 0}
	<p class="empty">{emptyText}</p>
{/if}

<div bind:this={sentinel} class="sentinel">
	{#if loading}
		<LoadingIndicator aria-label="Loading more" />
	{:else if error}
		<p class="error" role="alert">{error} <button class="retry" onclick={load}>Retry</button></p>
	{:else if done && capped && items.length}
		<p class="end body-small">That's the top {items.length}. Search to find anything else.</p>
	{/if}
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
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(6.5rem, 1fr));
		gap: 1rem 0.75rem;
	}
	@media (width >= 35rem) {
		.grid {
			grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
		}
	}
	@media (width >= 75rem) {
		.grid {
			grid-template-columns: repeat(auto-fill, minmax(9.75rem, 1fr));
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
	.empty {
		color: var(--m3c-on-surface-variant);
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
		margin-left: 0.5rem;
	}
</style>
