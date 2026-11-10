<script lang="ts">
	import type { Component } from 'svelte';
	import { page } from '$app/state';
	import { afterNavigate, goto } from '$app/navigation';
	import { Button, Icon } from 'm3-svelte';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Loading from '$lib/servicepages/shared/Loading.svelte';
	import Empty from '$lib/servicepages/shared/Empty.svelte';
	import { iBack, iOpen } from '$lib/icons';

	let { data } = $props();

	// Each service type ships its own screen at src/lib/servicepages/<type>/Page.svelte.
	const pages = import.meta.glob<{ default: Component<ServicePageProps> }>('/src/lib/servicepages/*/Page.svelte');
	const loader = $derived(pages[`/src/lib/servicepages/${data.service.type}/Page.svelte`]);
	const info = $derived(SERVICE_TYPES[data.service.type]);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const webUrl = $derived(data.service.externalUrl ?? data.service.url);
	// Only mention the type when the name doesn't already say it ("Sonarr 4K" → "Sonarr", "Sonarr" → nothing).
	const typeLabel = $derived.by(() => {
		const label = info?.label ?? data.service.type;
		const name = data.service.name.toLowerCase();
		return label.split(/\s*\/\s*/).some((part) => name.includes(part.toLowerCase())) ? null : label;
	});

	// Back goes to wherever the user came from inside the app; opened directly (or from a notification), it goes home.
	let cameFromApp = $state(false);
	afterNavigate(({ from, type }) => {
		if (type !== 'enter' && from) cameFromApp = true;
	});
	const back = () => (cameFromApp ? history.back() : goto('/settings'));
</script>

<svelte:head><title>{data.service.name} · Bosunarr</title></svelte:head>

<header>
	<Button variant="text" iconType="full" aria-label="Back" onclick={back}><Icon icon={iBack} /></Button>
	<span class="badge" style:--c={info?.color} aria-hidden="true">{(info?.label ?? data.service.type)[0]}</span>
	<h1 class="title-large" class:grow={!typeLabel}>{data.service.name}</h1>
	{#if typeLabel}<span class="type">{typeLabel}</span>{/if}
	<Button variant="text" iconType="full" href={webUrl} target="_blank" rel="noopener noreferrer" aria-label={`Open ${data.service.name} in a new tab`}>
		<Icon icon={iOpen} />
	</Button>
</header>

{#key data.service.id}
	{#if loader}
		{#await loader()}
			<Loading />
		{:then mod}
			<mod.default service={data.service} {isAdmin} />
		{/await}
	{:else}
		<Empty title={`There's no ${info?.label ?? data.service.type} screen yet`} hint="You can still manage it in its own web app.">
			{#snippet action()}
				<Button variant="tonal" href={webUrl} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} /> Open {info?.label ?? 'it'}</Button>
			{/snippet}
		</Empty>
	{/if}
{/key}

<style>
	header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		height: 2.5rem;
		margin: 0 -0.5rem 0.75rem -0.5rem;
		min-width: 0;
	}
	h1 {
		margin: 0;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.type {
		flex: 1;
		min-width: 0;
		padding-top: 0.25rem;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	h1.grow {
		flex: 1;
	}
	/* Type badge, same as the Settings service rows: brand tint + initial, a rounded square so it
	   never reads as a status dot. */
	.badge {
		display: grid;
		place-items: center;
		flex: none;
		width: 2rem;
		height: 2rem;
		border-radius: 0.5rem;
		background: color-mix(in oklab, var(--c, var(--m3c-primary)) 18%, var(--m3c-surface-container-highest));
		color: color-mix(in oklab, var(--c, var(--m3c-primary)) 70%, var(--m3c-on-surface));
		font-weight: 600;
		font-size: 0.875rem;
		text-transform: uppercase;
	}
</style>
