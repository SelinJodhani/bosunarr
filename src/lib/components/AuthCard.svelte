<script lang="ts">
	import type { Snippet } from 'svelte';
	import { Icon } from 'm3-svelte';
	import { iBoat } from '$lib/icons';

	let { title, subtitle, children }: { title: string; subtitle: string; children: Snippet } = $props();
</script>

<div class="wrap">
	<section>
		<div class="brand"><Icon icon={iBoat} size={28} /> Bosunarr</div>
		<h1>{title}</h1>
		<p>{subtitle}</p>
		{@render children()}
	</section>
</div>

<style>
	.wrap {
		min-height: 100dvh;
		display: grid;
		place-items: center;
		padding: 1.5rem;
		background: radial-gradient(120% 80% at 50% 0%, var(--m3c-primary-container-subtle), var(--m3c-surface));
	}
	section {
		width: min(26rem, 100%);
		padding: 2rem 1.5rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.12);
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		color: var(--m3c-primary);
		font-weight: 600;
		margin-bottom: 1.5rem;
	}
	h1 {
		font-size: 1.75rem;
		font-weight: 500;
	}
	p {
		margin: 0.25rem 0 1.5rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
