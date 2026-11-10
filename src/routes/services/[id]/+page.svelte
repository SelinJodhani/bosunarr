<script lang="ts">
	import type { Component } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { Button, Icon, LoadingIndicator } from 'm3-svelte';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import { iBack, iOpen } from '$lib/icons';

	let { data } = $props();

	// Each service type ships its own screen at src/lib/servicepages/<type>/Page.svelte.
	const pages = import.meta.glob<{ default: Component<ServicePageProps> }>('/src/lib/servicepages/*/Page.svelte');
	const loader = $derived(pages[`/src/lib/servicepages/${data.service.type}/Page.svelte`]);
	const info = $derived(SERVICE_TYPES[data.service.type]);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const webUrl = $derived(data.service.externalUrl ?? data.service.url);
</script>

<svelte:head><title>{data.service.name} · Bosunarr</title></svelte:head>

<header>
	<Button variant="text" iconType="full" aria-label="Back" onclick={() => (history.length > 1 ? history.back() : goto('/settings'))}><Icon icon={iBack} /></Button>
	<span class="dot" style:background={info?.color}></span>
	<div class="titles">
		<h1 class="title-large">{data.service.name}</h1>
		<p class="body-small">{info?.label ?? data.service.type}</p>
	</div>
	<Button variant="text" iconType="full" href={webUrl} target="_blank" rel="noopener noreferrer" aria-label={`Open ${data.service.name}`}>
		<Icon icon={iOpen} />
	</Button>
</header>

{#key data.service.id}
	{#if loader}
		{#await loader()}
			<div class="center"><LoadingIndicator aria-label="Loading" /></div>
		{:then mod}
			<mod.default service={data.service} {isAdmin} />
		{/await}
	{:else}
		<section class="card">
			<p>A dedicated {info?.label ?? data.service.type} screen isn't available yet.</p>
			<Button variant="tonal" href={webUrl} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} /> Open {info?.label ?? 'it'}</Button>
		</section>
	{/if}
{/key}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin: -0.25rem 0 1rem -0.5rem;
	}
	.titles {
		flex: 1;
		min-width: 0;
	}
	.titles h1,
	.titles p {
		margin: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.dot {
		flex: none;
		width: 0.75rem;
		height: 0.75rem;
		border-radius: 50%;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.card {
		padding: 1.25rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		display: grid;
		gap: 1rem;
		justify-items: start;
	}
	.card p {
		margin: 0;
	}
</style>
