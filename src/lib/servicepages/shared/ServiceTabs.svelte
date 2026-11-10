<script lang="ts">
	// Secondary tabs directly under the summary card, then the tab's content. Every service screen uses
	// this so tabs sit in the same place and the content starts at the same distance.
	// Health / update notices render at the top of one tab's content (the first, unless `noticesOn`
	// says otherwise), so they never change the height of anything above the tabs.
	import type { Snippet } from 'svelte';
	import { Tabs } from 'm3-svelte';
	import Notices from './Notices.svelte';
	import type { SummaryNotice } from './types';

	let {
		tab = $bindable(),
		items,
		notices = [],
		noticesOn,
		stale = null,
		children
	}: {
		tab: string;
		items: Array<{ name: string; value: string }>;
		notices?: SummaryNotice[];
		/** Tab that shows the notices; defaults to the first one. */
		noticesOn?: string;
		/** Refresh error while older data is still shown (shown on every tab). */
		stale?: string | null;
		children: Snippet;
	} = $props();

	const here = $derived(tab === (noticesOn ?? items[0]?.value));
</script>

<div class="tabs no-scrollbar"><Tabs {items} bind:tab secondary /></div>
<div class="content" data-pager-top>
	<Notices notices={here ? notices : []} {stale} />
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
