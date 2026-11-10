<script lang="ts">
	// Section card used inside service tabs. One title style for every screen.
	import type { Snippet } from 'svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { Icon } from 'm3-svelte';

	let {
		title,
		icon,
		count,
		wide = false,
		actions,
		children
	}: { title?: string; icon?: IconifyIcon; count?: number | null; wide?: boolean; actions?: Snippet; children: Snippet } = $props();
</script>

<section class="card" class:wide>
	{#if title || actions}
		<div class="head">
			{#if title}
				<h2>
					{#if icon}<Icon icon={icon} size={20} />{/if}
					<span class="t">{title}</span>
					{#if count}<span class="count">{count}</span>{/if}
				</h2>
			{/if}
			{#if actions}<div class="actions">{@render actions()}</div>{/if}
		</div>
	{/if}
	{@render children()}
</section>

<style>
	.card {
		min-width: 0;
		padding: 1rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		align-content: start;
	}
	.card.wide {
		grid-column: 1 / -1;
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		min-height: 2.5rem;
		margin: -0.25rem 0 -0.25rem;
	}
	h2 {
		margin: 0;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		font-size: 1rem;
		line-height: 1.5rem;
		font-weight: 500;
		color: var(--m3c-on-surface);
	}
	h2 :global(svg) {
		flex: none;
		color: var(--m3c-primary);
	}
	.t {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.count {
		flex: none;
		font-size: 0.75rem;
		line-height: 1;
		padding: 0.25rem 0.5rem;
		border-radius: 1rem;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.actions {
		flex: none;
		display: flex;
		gap: 0.25rem;
		margin-right: -0.5rem;
	}
</style>
