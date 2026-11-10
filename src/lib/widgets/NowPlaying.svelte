<script lang="ts">
	import { Icon, LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { iPause, iPlay } from '$lib/icons';
	import State from './State.svelte';
	import Poster from './Poster.svelte';
	import iTvOff from '@ktibow/iconset-material-symbols/tv-off-rounded';
	import { WIDGETS, type NowPlayingItem, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<NowPlayingItem[]>('/api/widgets/nowplaying', WIDGETS.nowplaying.refreshMs);
	const items = $derived(q.state.data ?? []);
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No one is watching right now" emptyIcon={iTvOff} skeleton={{ rows: 2, thumb: size === 's' ? 0 : 40, bar: true }}>
	<ul>
		{#each items as p (p.id)}
			<li>
				{#if size !== 's'}<Poster src={p.poster} width={40} alt={p.title} />{/if}
				<div class="text">
					<div class="title"><Icon icon={p.paused ? iPause : iPlay} /> <span>{p.title}</span></div>
					<div class="sub">{p.subtitle ? `${p.subtitle} · ` : ''}{p.user}{size !== 's' && p.device ? ` · ${p.device}` : ''}</div>
					<LinearProgress percent={p.progress * 100} aria-label={`${p.title} playback position`} />
				</div>
			</li>
		{/each}
	</ul>
</State>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.875rem;
	}
	li {
		display: flex;
		gap: 0.75rem;
		align-items: center;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.25rem;
	}
	.title {
		display: flex;
		align-items: center;
		gap: 0.375rem;
		font-size: 0.875rem;
		font-weight: 500;
		min-width: 0;
	}
	.title :global(svg) {
		flex: none;
	}
	.title span {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
