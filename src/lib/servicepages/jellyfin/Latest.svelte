<script lang="ts">
	import { ago } from '$lib/format';
	import type { JfLatest } from './types';

	let { items }: { items: JfLatest[] } = $props();
	let failed = $state<Record<string, boolean>>({});
</script>

<ul class="scroller">
	{#each items as it (it.id)}
		<li>
			<div class="poster">
				{#if it.poster && !failed[it.id]}
					<img src={it.poster} alt="" loading="lazy" decoding="async" onerror={() => (failed[it.id] = true)} />
				{:else}
					<span class="ph">{it.title.slice(0, 1)}</span>
				{/if}
				{#if it.unplayed}<span class="badge" aria-label={`${it.unplayed} new`}>{it.unplayed}</span>{/if}
			</div>
			<div class="t">{it.title}</div>
			<div class="s">{[it.year, it.added ? ago(it.added) : null].filter(Boolean).join(' · ')}</div>
		</li>
	{/each}
</ul>

<style>
	/* Bleeds to the screen edge on phones (matches the shell's main padding). */
	.scroller {
		list-style: none;
		margin: 0 calc(-1 * max(1rem, env(safe-area-inset-left)));
		padding: 0.25rem max(1rem, env(safe-area-inset-left)) 0.5rem;
		display: grid;
		grid-auto-flow: column;
		grid-auto-columns: 7rem;
		gap: 0.75rem;
		overflow-x: auto;
		overscroll-behavior-x: contain;
		scroll-snap-type: x proximity;
		scroll-padding-inline: max(1rem, env(safe-area-inset-left));
		scrollbar-width: none;
	}
	.scroller::-webkit-scrollbar {
		display: none;
	}
	@media (width >= 52.5rem) {
		.scroller {
			margin: 0;
			padding: 0.25rem 0 0.5rem;
			scroll-padding-inline: 0;
			grid-auto-columns: 8.5rem;
			scrollbar-width: thin;
		}
	}
	li {
		scroll-snap-align: start;
		min-width: 0;
	}
	.poster {
		position: relative;
		aspect-ratio: 2 / 3;
		border-radius: 0.75rem;
		overflow: hidden;
		background: var(--m3c-surface-container-highest);
		display: grid;
		place-items: center;
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
	.ph {
		font-size: 2rem;
		color: var(--m3c-on-surface-variant);
	}
	.badge {
		position: absolute;
		top: 0.375rem;
		right: 0.375rem;
		min-width: 1.25rem;
		height: 1.25rem;
		padding: 0 0.25rem;
		border-radius: 0.625rem;
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
		font-size: 0.75rem;
		font-weight: 600;
		display: grid;
		place-items: center;
	}
	.t,
	.s {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.t {
		margin-top: 0.375rem;
		font-size: 0.875rem;
		font-weight: 500;
	}
	.s {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
</style>
