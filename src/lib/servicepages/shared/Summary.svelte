<script lang="ts">
	// The card at the top of every service screen: a one-line identity (version, uptime…), up to four
	// number tiles, and any health / update notices. Tabs follow underneath (ServiceTabs).
	import type { Snippet } from 'svelte';
	import { Icon } from 'm3-svelte';
	import iError from '@ktibow/iconset-material-symbols/error-rounded';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import iUpdate from '@ktibow/iconset-material-symbols/system-update-rounded';
	import iOpen from '@ktibow/iconset-material-symbols/open-in-new-rounded';
	import type { SummaryNotice, SummaryStat } from './types';

	let {
		meta = [],
		stats = [],
		notices = [],
		stale = null,
		children
	}: {
		/** Short facts joined into one line, e.g. ["Sonarr 4.0.14", "Up 3 days"]. Falsy entries are skipped. */
		meta?: Array<string | null | undefined | false>;
		stats?: SummaryStat[];
		notices?: SummaryNotice[];
		/** Refresh error while older data is still shown. */
		stale?: string | null;
		children?: Snippet;
	} = $props();

	const facts = $derived(meta.filter(Boolean) as string[]);
	const ICON = { bad: iError, warn: iWarning, info: iInfo, update: iUpdate } as const;
</script>

<section class="summary" aria-label="Summary">
	{#if facts.length}
		<p class="meta">
			{#each facts as f, i (i)}<span>{f}</span>{/each}
		</p>
	{/if}

	{#if stats.length}
		<div class="stats" class:four={stats.length >= 4} style:--n={stats.length}>
			{#each stats as s (s.label)}
				{#if s.href}
					<a class="stat t-{s.tone ?? 'none'}" href={s.href}>
						<span class="num">{s.value ?? '–'}</span><span class="lbl">{s.label}</span>
					</a>
				{:else if s.onclick}
					<button class="stat t-{s.tone ?? 'none'}" onclick={s.onclick}>
						<span class="num">{s.value ?? '–'}</span><span class="lbl">{s.label}</span>
					</button>
				{:else}
					<div class="stat static t-{s.tone ?? 'none'}">
						<span class="num">{s.value ?? '–'}</span><span class="lbl">{s.label}</span>
					</div>
				{/if}
			{/each}
		</div>
	{/if}

	{@render children?.()}

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
					<span class="text">{#if n.source}<b>{n.source}</b>{' '}{/if}{n.text}</span>
					{#if n.href}
						<a class="more" href={n.href} target="_blank" rel="noopener noreferrer" aria-label="How to fix"><Icon icon={iOpen} size={20} /></a>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</section>

<style>
	.summary {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		padding: 1rem;
		margin-bottom: 0.75rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.meta {
		margin: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 0.125rem 0;
		font-size: 0.875rem;
		line-height: 1.25rem;
		color: var(--m3c-on-surface-variant);
		min-width: 0;
	}
	.meta span {
		overflow-wrap: anywhere;
	}
	.meta span + span::before {
		content: '\00a0·\00a0';
	}
	.meta span:first-child {
		color: var(--m3c-on-surface);
		font-weight: 500;
	}
	.stats {
		display: grid;
		grid-template-columns: repeat(var(--n), minmax(0, 1fr));
		gap: 0.5rem;
	}
	/* Four tiles become 2 × 2 on narrow phones; three still fit in one row. */
	@media (width < 24rem) {
		.stats.four {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	.stat {
		all: unset;
		box-sizing: border-box;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		align-content: center;
		gap: 0.125rem;
		min-width: 0;
		min-height: 4rem;
		padding: 0.5rem 0.625rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-high);
		color: var(--m3c-on-surface);
		text-decoration: none;
		cursor: pointer;
		transition: background-color 150ms;
	}
	.stat.static {
		cursor: default;
	}
	.stat:not(.static):hover {
		background: var(--m3c-surface-container-highest);
	}
	.stat:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.num {
		font-size: clamp(1.125rem, 5vw, 1.375rem);
		line-height: 1.75rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.lbl {
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
	}
	.t-info .num {
		color: var(--m3c-primary);
	}
	.t-warn .num {
		color: var(--m3c-tertiary);
	}
	.t-bad .num {
		color: var(--m3c-error);
	}
	.notices {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.notices li {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.625rem;
		min-height: 2.5rem;
		padding: 0.375rem 0.25rem 0.375rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-high);
		font-size: 0.875rem;
		line-height: 1.25rem;
	}
	.ic {
		display: grid;
	}
	.text {
		overflow-wrap: anywhere;
		padding-right: 0.5rem;
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
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		color: var(--m3c-on-surface-variant);
	}
	.more:hover {
		background: var(--m3c-surface-container-highest);
	}
</style>
