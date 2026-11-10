<script lang="ts">
	// Empty state shared by the Activity lists: centred icon, one title line, optional hint and action.
	import type { IconifyIcon } from '@iconify/types';
	import { Button, Icon } from 'm3-svelte';

	let {
		icon,
		title,
		hint,
		action
	}: { icon: IconifyIcon; title: string; hint?: string; action?: { label: string; icon?: IconifyIcon; onclick: () => void } } = $props();
</script>

<div class="empty">
	<Icon {icon} size={40} />
	<p class="t title-medium">{title}</p>
	{#if hint}<p class="h body-small">{hint}</p>{/if}
	{#if action}
		<div class="a">
			<Button variant="tonal" onclick={action.onclick}>{#if action.icon}<Icon icon={action.icon} />{/if}{action.label}</Button>
		</div>
	{/if}
</div>

<style>
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: 0.25rem;
		padding: 2.5rem 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.t {
		margin: 0.5rem 0 0;
		color: var(--m3c-on-surface);
	}
	.h {
		margin: 0;
		max-width: 22rem;
	}
	.a {
		margin-top: 1rem;
	}
</style>
