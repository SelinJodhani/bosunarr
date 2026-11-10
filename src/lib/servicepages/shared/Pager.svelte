<script lang="ts">
	// One pager for every list on the service screens: "67 missing items" on the left, ‹ 1 / 4 › on the right.
	import { Button, Icon } from 'm3-svelte';
	import iPrev from '@ktibow/iconset-material-symbols/chevron-left-rounded';
	import iNext from '@ktibow/iconset-material-symbols/chevron-right-rounded';

	let {
		page,
		pages,
		total = null,
		noun = 'item',
		onpage
	}: { page: number; pages: number; total?: number | null; noun?: string; onpage: (p: number) => void } = $props();

	let nav = $state<HTMLElement>();
	function go(n: number) {
		const p = Math.min(pages, Math.max(1, n));
		if (p === page) return;
		onpage(p);
		// The pager sits under the list: bring the top of the list back into view.
		const top = nav?.closest('[data-pager-top]');
		if (top && top.getBoundingClientRect().top < 0) requestAnimationFrame(() => top.scrollIntoView({ block: 'start' }));
	}
</script>

{#if (total !== null && total > 0) || pages > 1}
	<nav class="pager" aria-label={`${noun} pages`} bind:this={nav}>
		<span class="count">{#if total !== null}{total.toLocaleString()} {noun}{total === 1 ? '' : 's'}{/if}</span>
		{#if pages > 1}
			<span class="nav">
				<Button variant="text" iconType="full" disabled={page <= 1} onclick={() => go(page - 1)} aria-label="Previous page"><Icon icon={iPrev} /></Button>
				<span class="where" aria-live="polite">{page} / {pages}</span>
				<Button variant="text" iconType="full" disabled={page >= pages} onclick={() => go(page + 1)} aria-label="Next page"><Icon icon={iNext} /></Button>
			</span>
		{/if}
	</nav>
{/if}

<style>
	.pager {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		min-height: 2.5rem;
	}
	.count {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
	.nav {
		flex: none;
		display: flex;
		align-items: center;
		gap: 0.25rem;
		margin-right: -0.5rem;
	}
	.where {
		font-size: 0.875rem;
		font-variant-numeric: tabular-nums;
		min-width: 3.5rem;
		text-align: center;
	}
</style>
