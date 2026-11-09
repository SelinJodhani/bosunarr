<script lang="ts">
	import { BADGE, detailHref } from './client';
	import type { DiscoverItem } from './types';

	let { item, width }: { item: DiscoverItem; width?: number } = $props();
	let failed = $state(false);
	const badge = $derived(BADGE[item.status] ?? (item.status4k !== 'none' ? `4K ${BADGE[item.status4k]}` : null));
</script>

<a class="card" href={detailHref(item)} data-sveltekit-preload-code="hover" style:width={width ? `${width}px` : undefined}>
	<div class="art">
		{#if item.poster && !failed}
			<img src={item.poster} alt="" loading="lazy" decoding="async" onerror={() => (failed = true)} />
		{:else}
			<span class="fallback">{item.title}</span>
		{/if}
		<span class="type">{item.mediaType === 'movie' ? 'Movie' : 'Series'}</span>
		{#if badge}<span class="badge s-{item.status === 'none' ? item.status4k : item.status}">{badge}</span>{/if}
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
	.type,
	.badge {
		position: absolute;
		padding: 0.125rem 0.4rem;
		border-radius: 0.5rem;
		font-size: 0.6875rem;
		font-weight: 600;
		line-height: 1rem;
	}
	.type {
		bottom: 0.375rem;
		left: 0.375rem;
		background: color-mix(in oklab, var(--m3c-inverse-surface) 80%, transparent);
		color: var(--m3c-inverse-on-surface);
	}
	.badge {
		top: 0.375rem;
		right: 0.375rem;
	}
	.s-available {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
	}
	.s-partial {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.s-processing {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.s-pending {
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface);
		box-shadow: inset 0 0 0 1px var(--m3c-outline);
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
