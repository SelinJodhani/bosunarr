<script lang="ts">
	// The compact card at the top of every service screen: a one-line identity (version, uptime…) and
	// up to four number tiles. Its height depends only on the layout, never on the data, so cached and
	// fresh data paint the same. Health / update notices live under the tabs (ServiceTabs).
	import type { Snippet } from 'svelte';
	import type { SummaryStat } from './types';

	let {
		meta = [],
		stats = [],
		children
	}: {
		/** Short facts joined into one line, e.g. ["Sonarr 4.0.14", "Up 3 days"]. Falsy entries are skipped. */
		meta?: Array<string | null | undefined | false>;
		stats?: SummaryStat[];
		children?: Snippet;
	} = $props();

	const facts = $derived(meta.filter(Boolean) as string[]);
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
</section>

<style>
	.summary {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.625rem;
		padding: 0.75rem;
		margin-bottom: 0.75rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	/* One line: the identity never wraps, so the card height is fixed. */
	.meta {
		margin: 0 0.125rem;
		display: flex;
		overflow: hidden;
		white-space: nowrap;
		font-size: 0.875rem;
		line-height: 1.25rem;
		color: var(--m3c-on-surface-variant);
		min-width: 0;
	}
	.meta span {
		flex: none;
	}
	.meta span:last-child {
		flex: 0 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
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
	/* Four tiles stay in one row; on narrow phones they just get tighter. */
	@media (width < 24rem) {
		.stats.four {
			gap: 0.375rem;
		}
		.stats.four .stat {
			padding: 0.375rem 0.5rem;
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
		min-height: 3.5rem;
		padding: 0.375rem 0.625rem;
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
</style>
