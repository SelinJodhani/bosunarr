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

	let {
		widget,
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
</script>

<article class:editing aria-label={meta.label}>
	<header>
		<h2 class="title-medium">{meta.label}</h2>
		{#if editing}
			<div class="edit" role="toolbar" aria-label={`Arrange ${meta.label}`}>
				<Button variant="text" iconType="full" size="xs" aria-label="Move earlier" disabled={first} onclick={onup}><Icon icon={iUp} /></Button>
				<Button variant="text" iconType="full" size="xs" aria-label="Move later" disabled={last} onclick={ondown}><Icon icon={iDown} /></Button>
				<Button variant="text" iconType="full" size="xs" aria-label={`Size: ${widget.size.toUpperCase()}`} onclick={onresize}><Icon icon={iResize} /></Button>
				<Button variant="text" iconType="full" size="xs" aria-label="Remove" onclick={onremove}><Icon icon={iDelete} /></Button>
			</div>
		{/if}
	</header>
	<div class="body">
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
		padding: 1rem 1.125rem 1.125rem;
		transition: box-shadow 0.2s, outline-color 0.2s;
		outline: 2px dashed transparent;
		outline-offset: -2px;
		contain: content;
	}
	article.editing {
		outline-color: var(--m3c-outline-variant);
	}
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		min-height: 2rem;
		margin-bottom: 0.5rem;
	}
	.edit {
		display: flex;
		gap: 0.125rem;
	}
	.body {
		flex: 1;
		min-height: 0;
	}
</style>
