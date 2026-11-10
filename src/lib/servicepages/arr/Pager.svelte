<script lang="ts">
	import { Button, Icon } from 'm3-svelte';
	import iPrev from '@ktibow/iconset-material-symbols/chevron-left-rounded';
	import iNext from '@ktibow/iconset-material-symbols/chevron-right-rounded';

	let { page, pages, total, noun, onpage }: { page: number; pages: number; total: number; noun: string; onpage: (p: number) => void } = $props();
</script>

<nav class="pager" aria-label={`${noun} pages`}>
	<span class="count">{total.toLocaleString()} {noun}{total === 1 ? '' : 's'}</span>
	{#if pages > 1}
		<span class="nav">
			<Button variant="text" iconType="full" disabled={page <= 1} onclick={() => onpage(page - 1)} aria-label="Previous page"><Icon icon={iPrev} /></Button>
			<span class="where">{page} / {pages}</span>
			<Button variant="text" iconType="full" disabled={page >= pages} onclick={() => onpage(page + 1)} aria-label="Next page"><Icon icon={iNext} /></Button>
		</span>
	{/if}
</nav>

<style>
	.pager {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		min-height: 2.5rem;
	}
	.count {
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
	.nav {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}
	.where {
		font-size: 0.875rem;
		font-variant-numeric: tabular-nums;
		min-width: 3.5rem;
		text-align: center;
	}
</style>
