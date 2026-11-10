<script lang="ts">
	// Indeterminate linear progress (m3-svelte only ships determinate / estimated bars).
	let { label = 'Working' }: { label?: string } = $props();
</script>

<div class="track" role="progressbar" aria-label={label}><span class="bar"></span></div>

<style>
	.track {
		position: relative;
		height: 0.25rem;
		border-radius: 0.125rem;
		overflow: hidden;
		background: var(--m3c-secondary-container);
	}
	.bar {
		position: absolute;
		inset: 0 auto 0 0;
		width: 40%;
		border-radius: inherit;
		background: var(--m3c-primary);
		animation: slide 1.4s cubic-bezier(0.4, 0, 0.2, 1) infinite;
	}
	@keyframes slide {
		from {
			transform: translateX(-100%);
		}
		to {
			transform: translateX(250%);
		}
	}
</style>
