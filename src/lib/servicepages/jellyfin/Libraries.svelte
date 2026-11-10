<script lang="ts">
	import { Button, Icon, LinearProgress } from 'm3-svelte';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iMusic from '@ktibow/iconset-material-symbols/library-music-rounded';
	import iBook from '@ktibow/iconset-material-symbols/book-rounded';
	import iPhotos from '@ktibow/iconset-material-symbols/photo-library-rounded';
	import iLiveTv from '@ktibow/iconset-material-symbols/live-tv-rounded';
	import iCollections from '@ktibow/iconset-material-symbols/collections-bookmark-rounded';
	import iFolder from '@ktibow/iconset-material-symbols/folder-rounded';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import type { IconifyIcon } from '@iconify/types';
	import type { JfLibrary } from './types';

	let { libraries, isAdmin, onscan }: { libraries: JfLibrary[]; isAdmin: boolean; onscan: (l: JfLibrary) => void } = $props();

	const ICON: Record<string, IconifyIcon> = {
		movies: iMovie,
		tvshows: iTv,
		music: iMusic,
		musicvideos: iMusic,
		books: iBook,
		photos: iPhotos,
		homevideos: iPhotos,
		livetv: iLiveTv,
		boxsets: iCollections,
		playlists: iCollections
	};
	const UNIT: Record<string, [string, string]> = {
		movies: ['movie', 'movies'],
		tvshows: ['series', 'series'],
		music: ['album', 'albums'],
		books: ['book', 'books'],
		boxsets: ['collection', 'collections']
	};
	const fmt = new Intl.NumberFormat();
	function countLabel(l: JfLibrary) {
		if (l.count === null) return null;
		const [one, many] = UNIT[l.kind] ?? ['item', 'items'];
		return `${fmt.format(l.count)} ${l.count === 1 ? one : many}`;
	}
</script>

<ul class="libs">
	{#each libraries as l (l.id)}
		<li>
			<span class="ic"><Icon icon={ICON[l.kind] ?? iFolder} /></span>
			<div class="text">
				<div class="name">{l.name}</div>
				<div class="sub">
					{#if l.scanning}Scanning{l.progress !== null ? ` · ${Math.round(l.progress)}%` : '…'}{:else}{[countLabel(l), `${l.paths} ${l.paths === 1 ? 'folder' : 'folders'}`].filter(Boolean).join(' · ')}{/if}
				</div>
				{#if l.scanning}<LinearProgress percent={l.progress ?? 0} aria-label={`Scanning ${l.name}`} />{/if}
			</div>
			{#if isAdmin}
				<Button variant="text" iconType="full" disabled={l.scanning} aria-label={`Scan ${l.name}`} onclick={() => onscan(l)}><Icon icon={iRefresh} /></Button>
			{/if}
		</li>
	{/each}
</ul>

<style>
	.libs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	li {
		display: flex;
		align-items: center;
		gap: 0.875rem;
		padding: 0.5rem 0;
		min-width: 0;
	}
	li + li {
		border-top: 1px solid var(--m3c-outline-variant);
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.25rem;
	}
	.name,
	.sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.name {
		font-weight: 500;
	}
	.sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
