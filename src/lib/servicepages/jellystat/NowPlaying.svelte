<script lang="ts">
	import { Icon, LinearProgress } from 'm3-svelte';
	import iPlaying from '@ktibow/iconset-material-symbols/play-arrow-rounded';
	import iPaused from '@ktibow/iconset-material-symbols/pause-rounded';
	import { live } from '$lib/live.svelte';
	import State from '$lib/widgets/State.svelte';
	import type { JsSession } from './types';
	import { clock, method } from './format';

	let { base }: { base: string } = $props();
	// The page is re-keyed per service, so `base` never changes for this instance.
	// svelte-ignore state_referenced_locally
	const q = live<JsSession[]>(`${base}/now`, 10_000, { persist: false });
	const items = $derived(Array.isArray(q.state.data) ? q.state.data : []);
</script>

<section class="card" aria-labelledby="js-now">
	<h2 id="js-now" class="title-medium">
		Now playing
		{#if items.length}<span class="badge">{items.length}</span>{/if}
	</h2>
	<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No one is watching right now">
		<ul>
			{#each items as p (p.id)}
				<li>
					<span class="state" class:paused={p.paused} title={p.paused ? 'Paused' : 'Playing'}>
						<Icon icon={p.paused ? iPaused : iPlaying} />
					</span>
					<div class="text">
						<div class="title">{p.title}</div>
						<div class="sub">{[p.subtitle, p.user, p.device].filter(Boolean).join(' · ')}</div>
						<LinearProgress percent={p.progress * 100} aria-label={`${p.title} playback position`} />
						<div class="meta">
							<span>{clock(p.positionSec)}{p.runtimeSec ? ` / ${clock(p.runtimeSec)}` : ''}</span>
							{#if p.method}<span class="method" class:transcode={p.method === 'Transcode'}>{method(p.method)}</span>{/if}
						</div>
					</div>
				</li>
			{/each}
		</ul>
	</State>
</section>

<style>
	.card {
		padding: 1rem 1.125rem 1.125rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	h2 {
		margin: 0 0 0.75rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.badge {
		font-size: 0.75rem;
		line-height: 1;
		padding: 0.25rem 0.5rem;
		border-radius: 1rem;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
		gap: 1rem 1.5rem;
	}
	li {
		display: flex;
		gap: 0.75rem;
		align-items: flex-start;
	}
	.state {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 50%;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.state.paused {
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.25rem;
	}
	.title,
	.sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title {
		font-weight: 500;
	}
	.sub,
	.meta {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.meta {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-variant-numeric: tabular-nums;
	}
	.method.transcode {
		color: var(--m3c-tertiary);
		font-weight: 500;
	}
</style>
