<script lang="ts">
	let { src, alt = '', width = 48 }: { src: string | null; alt?: string; width?: number } = $props();
	let failed = $state(false);
</script>

<div class="poster" style:width="{width}px" style:height="{width * 1.5}px">
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
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
</style>
