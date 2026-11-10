<script lang="ts">
	import { Icon } from 'm3-svelte';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iPerson from '@ktibow/iconset-material-symbols/person-rounded';
	import iDevices from '@ktibow/iconset-material-symbols/devices-rounded';
	import { live } from '$lib/live.svelte';
	import State from '$lib/widgets/State.svelte';
	import type { JsTop, JsTopItem } from './types';
	import { count, duration } from './format';

	let { url }: { url: string } = $props();
	// Re-keyed by the parent whenever the period changes, so `url` is fixed per instance.
	// svelte-ignore state_referenced_locally
	const q = live<JsTop>(url, 60_000);
	const d = $derived(q.state.data && Array.isArray(q.state.data.movies) ? q.state.data : undefined);

	const lists = $derived(
		d
			? [
					{ key: 'movies', label: 'Most watched movies', icon: iMovie, items: d.movies, empty: 'No movies played' },
					{ key: 'shows', label: 'Most watched shows', icon: iTv, items: d.shows, empty: 'No episodes played' },
					{ key: 'users', label: 'Most active users', icon: iPerson, items: d.users, empty: 'No one watched' },
					{ key: 'clients', label: 'Popular clients', icon: iDevices, items: d.clients, empty: 'No clients used' }
				]
			: []
	);
	const maxOf = (items: JsTopItem[]) => Math.max(1, ...items.map((i) => i.plays));
</script>

<State loading={q.state.loading} error={q.state.error} empty={!d}>
	<div class="grid">
		{#each lists as l (l.key)}
			{@const max = maxOf(l.items)}
			<section class="card" aria-labelledby={`js-top-${l.key}`}>
				<h3 id={`js-top-${l.key}`} class="title-small"><Icon icon={l.icon} /> {l.label}</h3>
				{#if l.items.length}
					<ol>
						{#each l.items as it, i (i)}
							<li>
								<div class="row">
									<span class="name" title={it.name}>{it.name}</span>
									<span class="val">{count(it.plays)} {it.plays === 1 ? 'play' : 'plays'}{it.seconds ? ` · ${duration(it.seconds)}` : ''}</span>
								</div>
								<div class="meter" aria-hidden="true"><span style:width={`${(it.plays / max) * 100}%`}></span></div>
							</li>
						{/each}
					</ol>
				{:else}
					<p class="empty">{l.empty}</p>
				{/if}
			</section>
		{/each}
	</div>
</State>

<style>
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 17rem), 1fr));
		gap: 0.75rem;
	}
	.card {
		padding: 1rem 1.125rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	h3 {
		margin: 0 0 0.75rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		color: var(--m3c-on-surface-variant);
	}
	ol {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.625rem;
	}
	.row {
		display: flex;
		justify-content: space-between;
		gap: 0.75rem;
		font-size: 0.875rem;
	}
	.name {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.val {
		flex: none;
		color: var(--m3c-on-surface-variant);
		font-size: 0.75rem;
		font-variant-numeric: tabular-nums;
		align-self: center;
	}
	.meter {
		height: 0.25rem;
		margin-top: 0.25rem;
		border-radius: 0.125rem;
		background: var(--m3c-surface-container-highest);
		overflow: hidden;
	}
	.meter span {
		display: block;
		height: 100%;
		border-radius: inherit;
		background: var(--m3c-primary);
	}
	.empty {
		margin: 0;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
