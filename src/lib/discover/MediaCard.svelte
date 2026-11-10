<script lang="ts">
	import { AVAILABILITY_TONE, BADGE, detailHref, headline, warmSeed } from './client';
	import StatusBadge from './StatusBadge.svelte';
	import type { DiscoverItem } from './types';

	/** `showType`: false where the list already says what it is ("Action movies", "Popular series"). */
	let { item, showType = true }: { item: DiscoverItem; showType?: boolean } = $props();
	let failed = $state(false);
	// The further-along copy wins: a title on disk only in 4K reads "In 4K", not "Requested" for its HD copy.
	const h = $derived(headline(item.status, item.status4k));
	const st = $derived(h.status);
	const badge = $derived(BADGE[st] ? (h.uhd ? (st === 'available' ? 'In 4K' : `4K ${BADGE[st]}`) : BADGE[st]) : null);
	const tone = $derived(AVAILABILITY_TONE[st]);
	const warm = () => warmSeed(item.poster);
</script>

<a class="card" href={detailHref(item)} data-sveltekit-preload-code="hover" onpointerenter={warm} onpointerdown={warm} onfocus={warm}>
	<div class="art">
		{#if item.poster && !failed}
			<img src={item.poster} alt="" loading="lazy" decoding="async" onerror={() => (failed = true)} />
		{:else}
			<span class="fallback">{item.title}</span>
		{/if}
		{#if showType}<span class="type">{item.mediaType === 'movie' ? 'Movie' : 'Series'}</span>{/if}
		{#if badge && tone}<span class="badge"><StatusBadge {tone} label={badge} /></span>{/if}
	</div>
	<span class="t">{item.title}</span>
	<span class="s">{item.year ?? '—'}{item.rating ? ` · ★ ${item.rating}` : ''}</span>
</a>

<style>
	.card {
		display: flex;
		flex-direction: column;
		min-width: 0;
		color: inherit;
		text-decoration: none;
		border-radius: 0.75rem;
		outline-offset: 2px;
	}
	.art {
		position: relative;
		aspect-ratio: 2 / 3;
		border-radius: 0.75rem;
		overflow: hidden;
		background: var(--m3c-surface-container-highest);
		transition: transform 150ms;
	}
	.card:hover .art,
	.card:focus-visible .art {
		transform: scale(1.02);
	}
	.card:active .art {
		transform: scale(0.98);
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
	.fallback {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		padding: 0.5rem;
		text-align: center;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.type {
		position: absolute;
		bottom: 0.375rem;
		left: 0.375rem;
		padding: 0 0.4rem;
		border-radius: 0.5rem;
		font-size: 0.6875rem;
		font-weight: 600;
		line-height: 1.25rem;
		background: color-mix(in oklab, var(--m3c-inverse-surface) 80%, transparent);
		color: var(--m3c-inverse-on-surface);
	}
	.badge {
		position: absolute;
		top: 0.375rem;
		right: 0.375rem;
		display: flex;
		box-shadow: 0 1px 4px rgb(0 0 0 / 0.25);
		border-radius: 0.5rem;
	}
	.t,
	.s {
		overflow: hidden;
		white-space: nowrap;
		text-overflow: ellipsis;
	}
	.t {
		margin-top: 0.375rem;
		font-size: 0.8125rem;
		font-weight: 500;
		line-height: 1.125rem;
	}
	.s {
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
