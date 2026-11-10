<script lang="ts">
	import { Icon } from 'm3-svelte';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iMusic from '@ktibow/iconset-material-symbols/music-note-rounded';
	import iFolder from '@ktibow/iconset-material-symbols/folder-rounded';
	import { live } from '$lib/live.svelte';
	import State from '../shared/State.svelte';
	import type { JsLibrary } from './types';
	import { count, duration, when } from './format';

	let { base }: { base: string } = $props();
	// The page is re-keyed per service, so `base` is fixed per instance.
	// svelte-ignore state_referenced_locally
	const q = live<JsLibrary[]>(`${base}/libraries`, 300_000);
	const libs = $derived(Array.isArray(q.state.data) ? q.state.data : []);
	const ICONS = { movies: iMovie, tvshows: iTv, music: iMusic, other: iFolder };

	function counts(l: JsLibrary): [string, string][] {
		if (l.kind === 'tvshows')
			return [
				['Series', count(l.items)],
				['Seasons', count(l.seasons)],
				['Episodes', count(l.episodes)]
			];
		return [[l.kind === 'movies' ? 'Movies' : 'Items', count(l.items)]];
	}
</script>

<State
	loading={q.state.loading}
	error={q.state.error}
	empty={libs.length === 0}
	icon={iFolder}
	title="No libraries yet"
	hint="Jellystat hasn't synced any libraries from Jellyfin."
	onretry={() => q.refresh()}
>
	<ul>
		{#each libs as l (l.id)}
			<li>
				<h3><span class="ico"><Icon icon={ICONS[l.kind]} /></span><span class="name">{l.name}</span></h3>
				<dl>
					{#each counts(l) as [k, v] (k)}
						<div><dt>{k}</dt><dd>{v}</dd></div>
					{/each}
					{#if l.contentSec > 0}<div><dt>Runtime</dt><dd>{duration(l.contentSec)}</dd></div>{/if}
					<div><dt>Plays</dt><dd>{count(l.plays)}</dd></div>
					{#if l.watchedSec > 0}<div><dt>Watched</dt><dd>{duration(l.watchedSec)}</dd></div>{/if}
				</dl>
				<p class="last">
					{#if l.lastPlayed}
						Last played <time datetime={l.lastPlayed}>{when(l.lastPlayed)}</time>{l.lastItem ? ` · ${l.lastItem}` : ''}
					{:else}
						Never played
					{/if}
				</p>
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
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 17rem), 1fr));
		gap: 0.75rem;
	}
	li {
		padding: 1rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		align-content: start;
	}
	h3 {
		margin: 0;
		font-size: 1rem;
		line-height: 1.5rem;
		font-weight: 500;
		display: flex;
		align-items: center;
		gap: 0.625rem;
		min-width: 0;
	}
	.ico {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 0.75rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	dl {
		margin: 0;
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0.75rem 0.5rem;
	}
	dl div {
		min-width: 0;
	}
	dt {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	dd {
		margin: 0;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.last {
		margin: 0;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
