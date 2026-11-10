<script lang="ts">
	// Fixed-size poster: a tinted placeholder with an icon until the image has loaded, then a short fade-in.
	import { Icon } from 'm3-svelte';
	import { iMovie } from '$lib/icons';

	let { src, alt = '', width = 48 }: { src: string | null; alt?: string; width?: number } = $props();
	let failedSrc = $state<string | null>(null);
	let loadedSrc = $state<string | null>(null);
	let img = $state<HTMLImageElement>();
	// Keyed by URL so a reused element (virtualised lists, refreshed data) never shows a stale state.
	const failed = $derived(src != null && failedSrc === src);
	const loaded = $derived(src != null && loadedSrc === src);

	// Already decoded from cache: `load` may have fired before the handler was attached.
	$effect(() => {
		if (img && src && img.complete && img.naturalWidth) loadedSrc = src;
	});
</script>

<div class="poster" class:loaded style:width="{width}px" style:height="{width * 1.5}px">
	{#if !loaded}
		<span class="ph" aria-hidden="true"><Icon icon={iMovie} size={Math.round(Math.min(40, Math.max(16, width * 0.28)))} /></span>
	{/if}
	{#if src && !failed}
		<img bind:this={img} {src} {alt} loading="lazy" decoding="async" {width} height={width * 1.5} onload={() => (loadedSrc = src)} onerror={() => (failedSrc = src)} />
	{/if}
</div>

<style>
	.poster {
		position: relative;
		flex: none;
		border-radius: 0.5rem;
		overflow: hidden;
		background: var(--m3c-surface-container-highest);
	}
	.ph {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		color: var(--m3c-outline);
	}
	img {
		position: relative;
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
		opacity: 0;
		transition: opacity 180ms ease-out;
	}
	.loaded img {
		opacity: 1;
	}
</style>
