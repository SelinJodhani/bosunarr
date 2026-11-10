<script lang="ts">
	import { LoadingIndicator } from 'm3-svelte';
	let { label = 'Loading' }: { label?: string } = $props();
</script>

<div class="center"><LoadingIndicator aria-label={label} /></div>

<style>
	.center {
		display: grid;
		place-items: center;
		min-height: 8rem;
	}
</style>
