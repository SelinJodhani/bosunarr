<script lang="ts">
	import { Button, Icon } from 'm3-svelte';
	import { WIDGETS, type WidgetInstance } from './types';
	import { iDelete, iDown, iResize, iUp } from '$lib/icons';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import Health from './Health.svelte';
	import Downloads from './Downloads.svelte';
	import Queue from './Queue.svelte';
	import Upcoming from './Upcoming.svelte';
	import Requests from './Requests.svelte';
	import Disk from './Disk.svelte';
	import NowPlaying from './NowPlaying.svelte';
	import iHistory from '@ktibow/iconset-material-symbols/history-rounded';
	import { setFrameContext } from './frame';
	import { humanError } from './errors';

	let {
		widget,
		reserve,
		highlight = false,
		editing,
		missing,
		first,
		last,
		onup,
		ondown,
		onresize,
		onremove
	}: {
		widget: WidgetInstance;
		reserve?: number;
		highlight?: boolean;
		editing: boolean;
		missing: boolean;
		first: boolean;
		last: boolean;
		onup: () => void;
		ondown: () => void;
		onresize: () => void;
		onremove: () => void;
	} = $props();

	const COMPONENTS = { health: Health, downloads: Downloads, queue: Queue, upcoming: Upcoming, requests: Requests, disk: Disk, nowplaying: NowPlaying };
	const meta = $derived(WIDGETS[widget.kind]);
	const Component = $derived(COMPONENTS[widget.kind]);

	let stale = $state<string | null>(null);
	let staleOpen = $state(false);
	const staleInfo = $derived(stale ? humanError(stale) : null);
	setFrameContext({
		get id() {
			return widget.id;
		},
		get reserve() {
			return reserve;
		},
		setStale: (e) => {
			stale = e;
			if (!e) staleOpen = false;
		}
	});
</script>

<article class:editing class:highlight aria-label={meta.label}>
	<header>
		<h2 class="title-medium">{meta.label}</h2>
		{#if editing}
			<div class="edit" role="toolbar" aria-label={`Arrange ${meta.label}`}>
				<Button variant="text" iconType="full" size="s" aria-label="Move earlier" disabled={first} onclick={onup}><Icon icon={iUp} /></Button>
				<Button variant="text" iconType="full" size="s" aria-label="Move later" disabled={last} onclick={ondown}><Icon icon={iDown} /></Button>
				<!-- Every size spans the full width on phones, so resizing only exists on wider screens. -->
				<span class="resize"><Button variant="text" iconType="full" size="s" aria-label={`Size: ${{ s: 'small', m: 'medium', l: 'large' }[widget.size]} (change)`} onclick={onresize}><Icon icon={iResize} /></Button></span>
				<Button variant="text" iconType="full" size="s" aria-label="Remove" onclick={onremove}><Icon icon={iDelete} /></Button>
			</div>
		{:else if staleInfo}
			<button class="stale" aria-expanded={staleOpen} title={`${staleInfo.short} — ${staleInfo.detail}`} onclick={() => (staleOpen = !staleOpen)}>
				<Icon icon={iHistory} size={16} /> Last known
			</button>
		{/if}
	</header>
	{#if staleOpen && staleInfo && !editing}
		<p class="stale-detail">{staleInfo.service ? `${staleInfo.service}: ` : ''}{staleInfo.short}. Showing the last data received.</p>
	{/if}
	<!-- While arranging, the widget's own links and buttons must not react to taps. -->
	<div class="body" inert={editing}>
		{#if missing}
			<p class="body-small">Add {meta.needs.map((t) => SERVICE_TYPES[t].label).join(' or ')} in Settings to use this widget.</p>
		{:else}
			<!-- A bug in one widget must not take the whole dashboard down with it. -->
			<svelte:boundary onerror={(e) => console.error(`Widget "${widget.kind}" crashed`, e)}>
				<Component size={widget.size} />
				{#snippet failed(_error, reset)}
					<p class="body-small crashed">This widget hit an error. <button class="retry" onclick={reset}>Retry</button></p>
				{/snippet}
			</svelte:boundary>
		{/if}
	</div>
</article>

<style>
	/* Shared look for tappable widget rows (links into Activity, Library, Discover…). */
	article :global(a.row-link) {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		min-height: 2.5rem;
		margin: 0 -0.5rem;
		padding: 0.25rem 0.5rem;
		border-radius: var(--radius-m);
		color: inherit;
		text-decoration: none;
		-webkit-tap-highlight-color: transparent;
		transition: background-color 150ms;
	}
	article :global(a.row-link:hover),
	article :global(a.row-link:focus-visible) {
		background: color-mix(in oklab, var(--m3c-on-surface) 6%, transparent);
	}
	article :global(a.row-link:active) {
		background: color-mix(in oklab, var(--m3c-on-surface) 10%, transparent);
	}
	article :global(a.row-link:focus-visible) {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -2px;
	}
	.stale {
		all: unset;
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		height: 2rem;
		padding: 0 0.625rem;
		margin: 0.25rem 0;
		border-radius: 0.5rem;
		font-size: 0.75rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
		background: var(--m3c-surface-container-high);
		/* Taller hit area than the chip itself. */
		position: relative;
	}
	.stale::after {
		content: '';
		position: absolute;
		inset: -0.25rem 0;
	}
	.stale:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
	.stale-detail {
		margin: 0 0 0.5rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.crashed {
		color: var(--m3c-error);
	}
	.retry {
		all: unset;
		cursor: pointer;
		font-weight: 600;
		text-decoration: underline;
	}
	article {
		height: 100%;
		display: flex;
		flex-direction: column;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		padding: 0.75rem 1rem 1rem;
		transition: box-shadow 0.2s, outline-color 0.2s, background-color 0.6s;
		outline: 2px dashed transparent;
		outline-offset: -2px;
		contain: content;
	}
	article.editing {
		outline-color: var(--m3c-outline-variant);
	}
	article.highlight {
		outline: 2px solid var(--m3c-primary);
		background: var(--m3c-secondary-container);
	}
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		height: 2.5rem;
		margin-bottom: 0.25rem;
	}
	h2 {
		margin: 0;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.edit {
		display: flex;
		flex: none;
		margin-right: -0.5rem;
	}
	.resize {
		display: none;
	}
	@media (width >= 40rem) {
		.resize {
			display: contents;
		}
	}
	.body {
		flex: 1;
		min-height: 0;
	}
</style>
