<script lang="ts">
	// Poster thumbnail with a placeholder icon when there's no image (or it fails to load).
	import type { IconifyIcon } from '@iconify/types';
	import { Icon } from 'm3-svelte';
	import iImage from '@ktibow/iconset-material-symbols/movie-rounded';

	let { src, alt = '', width = 44, icon }: { src: string | null | undefined; alt?: string; width?: number; icon?: IconifyIcon } = $props();
	let failed = $state(false);
</script>

<div class="art" style:width="{width}px" style:height="{Math.round(width * 1.5)}px">
	{#if src && !failed}
		<img {src} {alt} loading="lazy" decoding="async" {width} height={Math.round(width * 1.5)} onerror={() => (failed = true)} />
	{:else}
		<Icon icon={icon ?? iImage} size={Math.round(width * 0.5)} />
	{/if}
</div>

<style>
	.art {
		flex: none;
		display: grid;
		place-items: center;
		border-radius: 0.5rem;
		overflow: hidden;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
</style>
