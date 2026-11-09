<script lang="ts">
	import { Button, Icon } from 'm3-svelte';
	import MediaCard from './MediaCard.svelte';
	import { uniq } from './client';
	import type { DiscoverRow } from './types';
	import { iBack, iForward } from '$lib/icons';

	let { row, onmore }: { row: DiscoverRow; onmore: () => void } = $props();
	const list = $derived(uniq(row.items));
	let scroller = $state<HTMLElement>();

	function page(dir: 1 | -1) {
		if (scroller) scroller.scrollBy({ left: dir * scroller.clientWidth * 0.85, behavior: 'smooth' });
	}
</script>

<section class="row" aria-labelledby="row-{row.id}">
	<header>
		<h2 class="title-medium" id="row-{row.id}">{row.title}</h2>
		<div class="nav">
			<span class="arrows">
				<Button variant="text" iconType="full" aria-label={`Scroll ${row.title} back`} onclick={() => page(-1)}><Icon icon={iBack} /></Button>
				<Button variant="text" iconType="full" aria-label={`Scroll ${row.title} forward`} onclick={() => page(1)}><Icon icon={iForward} /></Button>
			</span>
			<Button variant="text" onclick={onmore}>See all</Button>
		</div>
	</header>
	<ul class="scroller" bind:this={scroller}>
		{#each list as it (it.key)}
			<li><MediaCard item={it} /></li>
		{/each}
	</ul>
</section>

<style>
	.row {
		margin-bottom: 1.25rem;
	}
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		margin-bottom: 0.25rem;
	}
	.nav {
		display: flex;
		align-items: center;
		margin-right: -0.75rem;
	}
	.arrows {
		display: none;
	}
	@media (width >= 52.5rem) and (hover: hover) {
		.arrows {
			display: flex;
		}
	}
	.scroller {
		list-style: none;
		margin: 0 calc(-1 * max(1rem, env(safe-area-inset-left)));
		padding: 0.25rem max(1rem, env(safe-area-inset-left)) 0.5rem;
		display: grid;
		grid-auto-flow: column;
		grid-auto-columns: 7.25rem;
		gap: 0.75rem;
		overflow-x: auto;
		overscroll-behavior-x: contain;
		scroll-snap-type: x proximity;
		scroll-padding-inline: max(1rem, env(safe-area-inset-left));
		scrollbar-width: none;
	}
	.scroller::-webkit-scrollbar {
		display: none;
	}
	@media (width >= 52.5rem) {
		.scroller {
			margin: 0;
			padding: 0.25rem 0 0.5rem;
			scroll-padding-inline: 0;
			grid-auto-columns: 9.5rem;
		}
	}
	li {
		scroll-snap-align: start;
		min-width: 0;
	}
</style>
