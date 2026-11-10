<script lang="ts">
	// Empty state shared by the Activity lists. Look comes from the global `.empty-state` (app.css) so every
	// screen's empty state is identical: centred 40 px icon, title-medium, body-small hint, optional tonal action.
	import type { IconifyIcon } from '@iconify/types';
	import { Button, Icon } from 'm3-svelte';

	let {
		icon,
		title,
		hint,
		action
	}: { icon: IconifyIcon; title: string; hint?: string; action?: { label: string; icon?: IconifyIcon; onclick: () => void } } = $props();
</script>

<div class="empty-state">
	<span class="es-icon"><Icon {icon} size={40} /></span>
	<p class="es-title">{title}</p>
	{#if hint}<p class="es-hint">{hint}</p>{/if}
	{#if action}
		<div class="es-action">
			<Button variant="tonal" onclick={action.onclick}>{#if action.icon}<Icon icon={action.icon} />{/if}{action.label}</Button>
		</div>
	{/if}
</div>
