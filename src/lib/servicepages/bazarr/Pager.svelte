<script lang="ts">
	import { Button, Icon } from 'm3-svelte';
	import iPrev from '@ktibow/iconset-material-symbols/chevron-left-rounded';
	import iNext from '@ktibow/iconset-material-symbols/chevron-right-rounded';

	let { page = $bindable(), total, pageSize }: { page: number; total: number; pageSize: number } = $props();
	const pages = $derived(Math.max(1, Math.ceil(total / pageSize)));
	const from = $derived(total ? (page - 1) * pageSize + 1 : 0);
	const to = $derived(Math.min(total, page * pageSize));

	/** Change page and bring the top of the list back into view (the pager sits at the bottom). */
	function go(n: number) {
		const anchor = nav?.closest('[data-pager-top]') ?? null;
		page = Math.min(pages, Math.max(1, n));
		if (anchor) requestAnimationFrame(() => anchor.scrollIntoView({ block: 'start' }));
	}
	let nav = $state<HTMLElement>();
</script>

{#if pages > 1}
	<nav class="pager" aria-label="Pages" bind:this={nav}>
		<Button variant="text" iconType="full" aria-label="Previous page" disabled={page <= 1} onclick={() => go(page - 1)}>
			<Icon icon={iPrev} />
		</Button>
		<span class="range">{from}–{to} of {total}</span>
		<Button variant="text" iconType="full" aria-label="Next page" disabled={page >= pages} onclick={() => go(page + 1)}>
			<Icon icon={iNext} />
		</Button>
	</nav>
{/if}

<style>
	.pager {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		padding: 0.5rem 0;
	}
	.range {
		font-variant-numeric: tabular-nums;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
		min-width: 7rem;
		text-align: center;
	}
</style>
