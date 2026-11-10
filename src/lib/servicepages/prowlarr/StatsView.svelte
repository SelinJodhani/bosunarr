<script lang="ts">
	import { LoadingIndicator } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import type { IndexerStat, StatsResponse } from './types';
	import { compact } from './api';

	let { url, metric }: { url: string; metric: 'queries' | 'grabs' | 'failures' | 'response' } = $props();

	// Re-created per range by {#key} in the parent, so the URL is read once.
	// svelte-ignore state_referenced_locally
	const s = live<StatsResponse>(url, 120_000);
	const list = $derived(s.state.data?.indexers ?? []);

	const value = (x: IndexerStat) =>
		metric === 'queries' ? x.queries : metric === 'grabs' ? x.grabs : metric === 'failures' ? x.failedQueries + x.failedGrabs : x.avgResponseMs;
	const label = (x: IndexerStat) => (metric === 'response' ? `${x.avgResponseMs.toLocaleString()} ms` : compact(value(x)));

	const rows = $derived([...list].sort((a, b) => value(b) - value(a) || a.name.localeCompare(b.name)));
	const max = $derived(Math.max(1, ...rows.map(value)));

	const totals = $derived.by(() => {
		const q = list.reduce((n, x) => n + x.queries, 0);
		const g = list.reduce((n, x) => n + x.grabs, 0);
		const f = list.reduce((n, x) => n + x.failedQueries + x.failedGrabs, 0);
		const timed = list.filter((x) => x.avgResponseMs > 0 && x.queries > 0);
		const w = timed.reduce((n, x) => n + x.queries, 0);
		const avg = w ? Math.round(timed.reduce((n, x) => n + x.avgResponseMs * x.queries, 0) / w) : 0;
		return { q, g, f, rate: q + g ? (f / (q + g)) * 100 : 0, avg };
	});
</script>

{#if !s.state.data}
	{#if s.state.error}
		<p class="error" role="alert">{s.state.error}</p>
	{:else}
		<div class="center"><LoadingIndicator aria-label="Loading stats" /></div>
	{/if}
{:else}
	<div class="tiles">
		<div class="tile"><span class="v">{compact(totals.q)}</span><span class="k">Queries</span></div>
		<div class="tile"><span class="v">{compact(totals.g)}</span><span class="k">Grabs</span></div>
		<div class="tile"><span class="v" class:bad={totals.rate >= 5}>{totals.rate.toFixed(1)}%</span><span class="k">Failed</span></div>
		<div class="tile"><span class="v">{totals.avg.toLocaleString()}<small> ms</small></span><span class="k">Avg response</span></div>
	</div>

	{#if rows.length === 0}
		<p class="empty">No indexer activity in this range.</p>
	{:else}
		<ul class="bars">
			{#each rows as x (x.indexerId)}
				<li>
					<div class="line">
						<span class="name">{x.name}</span>
						<span class="num">{label(x)}</span>
					</div>
					<div class="track" aria-hidden="true">
						<div class="fill" class:fail={metric === 'failures'} style:width={`${(value(x) / max) * 100}%`}></div>
					</div>
				</li>
			{/each}
		</ul>
	{/if}
{/if}

<style>
	.tiles {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 0.5rem;
		margin-bottom: 1rem;
	}
	@media (min-width: 40rem) {
		.tiles {
			grid-template-columns: repeat(4, minmax(0, 1fr));
		}
	}
	.tile {
		display: grid;
		gap: 0.125rem;
		padding: 0.75rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
	}
	.v {
		font-size: 1.375rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.v small {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.v.bad {
		color: var(--m3c-error);
	}
	.k {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.bars {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.line {
		display: flex;
		gap: 0.75rem;
		align-items: baseline;
		font-size: 0.875rem;
		margin-bottom: 0.25rem;
	}
	.name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.num {
		flex: none;
		font-variant-numeric: tabular-nums;
		color: var(--m3c-on-surface-variant);
	}
	.track {
		height: 0.5rem;
		border-radius: 0.25rem;
		background: var(--m3c-surface-container-high);
		overflow: hidden;
	}
	.fill {
		height: 100%;
		border-radius: 0.25rem;
		background: var(--m3c-primary);
		transition: width 250ms;
	}
	.fill.fail {
		background: var(--m3c-error);
	}
	.center {
		display: grid;
		place-items: center;
		padding: 2rem 0;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.error {
		color: var(--m3c-error);
	}
</style>
