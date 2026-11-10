<script lang="ts">
	// Empty / error state: centred 40 px icon, a title-medium line, an optional hint and an optional action.
	import type { Snippet } from 'svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { Button, Icon } from 'm3-svelte';
	import iInbox from '@ktibow/iconset-material-symbols/inbox-rounded';
	import iError from '@ktibow/iconset-material-symbols/cloud-off-rounded';
	import iRetry from '@ktibow/iconset-material-symbols/refresh-rounded';

	let {
		icon,
		title,
		hint,
		error = false,
		onretry,
		action
	}: {
		icon?: IconifyIcon;
		title: string;
		hint?: string | null;
		/** Shows the error icon and colours the title. */
		error?: boolean;
		onretry?: () => void;
		action?: Snippet;
	} = $props();
</script>

<!-- Look comes from the global `.empty-state` (app.css), shared with Activity, Discover and widgets. -->
<div class="empty-state" class:error role={error ? 'alert' : undefined}>
	<span class="es-icon"><Icon icon={icon ?? (error ? iError : iInbox)} size={40} /></span>
	<p class="es-title">{title}</p>
	{#if hint}<p class="es-hint">{hint}</p>{/if}
	{#if action}
		<div class="es-action">{@render action()}</div>
	{:else if onretry}
		<div class="es-action"><Button variant="tonal" onclick={onretry}><Icon icon={iRetry} /> Try again</Button></div>
	{/if}
</div>
