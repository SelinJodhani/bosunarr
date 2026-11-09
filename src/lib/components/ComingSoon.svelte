<script lang="ts">
	let { title, milestone, points }: { title: string; milestone: string; points: string[] } = $props();
</script>

<svelte:head><title>{title} · Bosunarr</title></svelte:head>

<h1 class="title-large">{title}</h1>
<section>
	<p class="label">Coming in {milestone}</p>
	<ul>
		{#each points as p (p)}<li>{p}</li>{/each}
	</ul>
</section>

<style>
	h1 {
		margin-bottom: 1rem;
	}
	section {
		max-width: 40rem;
		padding: 1.25rem 1.5rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
	}
	ul {
		margin: 0.5rem 0 0;
		padding-left: 1.25rem;
		line-height: 1.7;
	}
</style>
