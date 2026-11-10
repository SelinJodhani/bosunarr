<script lang="ts">
	// Health / update / sync notices for a service screen. They sit at the top of a tab's content
	// (see ServiceTabs), never in the summary card, so the card keeps one height whether the data is
	// cached or fresh and the tabs never jump when a notice comes or goes.
	import { Icon } from 'm3-svelte';
	import iError from '@ktibow/iconset-material-symbols/error-rounded';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import iUpdate from '@ktibow/iconset-material-symbols/system-update-rounded';
	import type { SummaryNotice } from './types';

	let { notices = [], stale = null }: { notices?: SummaryNotice[]; stale?: string | null } = $props();

	const ICON = { bad: iError, warn: iWarning, info: iInfo, update: iUpdate } as const;
	/** Lets long URLs wrap after a "/" instead of mid-word. */
	const parts = (s: string) => s.split(/(?<=[^\s/]\/)(?=[^\s/])/);
</script>

{#if notices.length || stale}
	<ul class="notices">
		{#if stale}
			<li class="n-warn" role="status">
				<span class="ic"><Icon icon={iWarning} size={20} /></span>
				<span class="text"><b>Couldn't refresh</b> Showing the last data we had.</span>
			</li>
		{/if}
		{#each notices as n, i (i)}
			<li class="n-{n.tone}">
				<span class="ic"><Icon icon={n.icon ?? ICON[n.tone]} size={20} /></span>
				<span class="text"
					>{#if n.source}<b>{n.source}</b>{' '}{/if}{#each parts(n.text) as p, j (j)}{#if j}<wbr />{/if}{p}{/each}</span
				>
				{#if n.href}
					<a class="more" href={n.href} target="_blank" rel="noopener noreferrer">{n.linkLabel ?? 'How to fix'}</a>
				{/if}
			</li>
		{/each}
	</ul>
{/if}

<style>
	.notices {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	li {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr);
		align-items: center;
		column-gap: 0.75rem;
		min-height: 2.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		font-size: 0.875rem;
		line-height: 1.25rem;
	}
	li:has(.more) {
		grid-template-columns: auto minmax(0, 1fr) auto;
		padding-right: 0.25rem;
	}
	.ic {
		display: grid;
	}
	.text {
		overflow-wrap: break-word;
		min-width: 0;
	}
	.n-bad .ic {
		color: var(--m3c-error);
	}
	.n-warn .ic {
		color: var(--m3c-tertiary);
	}
	.n-info .ic,
	.n-update .ic {
		color: var(--m3c-primary);
	}
	.more {
		display: inline-flex;
		align-items: center;
		min-height: 2.5rem;
		padding: 0 0.75rem;
		border-radius: 1.25rem;
		color: var(--m3c-primary);
		font-weight: 500;
		text-decoration: none;
		white-space: nowrap;
	}
	.more:hover {
		background: color-mix(in oklab, var(--m3c-primary) 8%, transparent);
	}
	.more:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
</style>
