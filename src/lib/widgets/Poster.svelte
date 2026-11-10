<script lang="ts">
	/** Fixed-size poster (no layout shift while the image loads); `fluid` fills its container at 2:3. */
	let { src, alt = '', width = 48, fluid = false }: { src: string | null; alt?: string; width?: number; fluid?: boolean } = $props();
	let failed = $state(false);
</script>

<div class="poster" class:fluid style:width={fluid ? undefined : `${width}px`} style:height={fluid ? undefined : `${width * 1.5}px`}>
	{#if src && !failed}
		<img {src} {alt} loading="lazy" decoding="async" width={width} height={width * 1.5} onerror={() => (failed = true)} />
	{/if}
</div>

<style>
	.poster {
		flex: none;
		border-radius: 0.5rem;
		overflow: hidden;
		background: var(--m3c-surface-container-highest);
	}
	.fluid {
		width: 100%;
		aspect-ratio: 2 / 3;
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
</style>
