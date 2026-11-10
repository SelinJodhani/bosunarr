<script lang="ts">
	// Secondary tabs directly under the summary card, then the tab's content. Every service screen uses
	// this so tabs sit in the same place and the content starts at the same distance.
	import type { Snippet } from 'svelte';
	import { Tabs } from 'm3-svelte';

	let {
		tab = $bindable(),
		items,
		children
	}: { tab: string; items: Array<{ name: string; value: string }>; children: Snippet } = $props();
</script>

<div class="tabs no-scrollbar"><Tabs {items} bind:tab secondary /></div>
<div class="content" data-pager-top>
	{@render children()}
</div>

<style>
	.tabs {
		overflow-x: auto;
		scroll-margin-top: 0.5rem;
	}
	/* m3-svelte gives each tab a 5rem minimum; five tabs need a little less on a 360 px phone. */
	.tabs.tabs :global(label) {
		min-width: 3.5rem;
		padding: 0 0.5rem;
	}
	.content {
		padding-top: 1rem;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		align-content: start;
	}
</style>
