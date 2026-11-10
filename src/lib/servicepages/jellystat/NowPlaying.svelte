<script lang="ts">
	import { Icon, LinearProgress } from 'm3-svelte';
	import iPaused from '@ktibow/iconset-material-symbols/pause-rounded';
	import iTv from '@ktibow/iconset-material-symbols/live-tv-rounded';
	import { live } from '$lib/live.svelte';
	import Card from '../shared/Card.svelte';
	import State from '../shared/State.svelte';
	import type { JsSession } from './types';
	import { clock, method } from './format';

	let { base }: { base: string } = $props();
	// The page is re-keyed per service, so `base` never changes for this instance.
	// svelte-ignore state_referenced_locally
	const q = live<JsSession[]>(`${base}/now`, 10_000, { persist: false });
	const items = $derived(Array.isArray(q.state.data) ? q.state.data : []);
</script>

<Card title="Now playing" icon={iTv} count={items.length || null}>
	<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} icon={iTv} title="No one is watching right now" onretry={() => q.refresh()}>
		<ul>
			{#each items as p (p.id)}
				<li class:paused={p.paused}>
					<div class="title">{p.title}</div>
					<div class="sub">{[p.subtitle, p.user, p.device].filter(Boolean).join(' · ')}</div>
					<LinearProgress percent={p.progress * 100} aria-label={`${p.title} playback position`} />
					<div class="meta">
						<span class="pos">
							{#if p.paused}<span class="paused-tag"><Icon icon={iPaused} size={14} />Paused</span>{/if}
							{clock(p.positionSec)}{p.runtimeSec ? ` / ${clock(p.runtimeSec)}` : ''}
						</span>
						{#if p.method}<span class="method" class:transcode={p.method === 'Transcode'}>{method(p.method)}</span>{/if}
					</div>
				</li>
			{/each}
		</ul>
	</State>
</Card>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
		gap: 1rem 1.5rem;
	}
	li {
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	li.paused :global(.m3-container) {
		opacity: 0.6;
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
	.pos {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
	}
	.paused-tag {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
		font-weight: 500;
		color: var(--m3c-on-surface);
	}
	.method.transcode {
		color: var(--m3c-tertiary);
		font-weight: 500;
	}
</style>
