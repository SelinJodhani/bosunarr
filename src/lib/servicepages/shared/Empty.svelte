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

<div class="empty" class:error role={error ? 'alert' : undefined}>
	<span class="ic"><Icon icon={icon ?? (error ? iError : iInbox)} size={40} /></span>
	<p class="title">{title}</p>
	{#if hint}<p class="hint">{hint}</p>{/if}
	{#if action}
		<div class="act">{@render action()}</div>
	{:else if onretry}
		<div class="act"><Button variant="tonal" onclick={onretry}><Icon icon={iRetry} /> Try again</Button></div>
	{/if}
</div>

<style>
	.empty {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		justify-items: center;
		gap: 0.25rem;
		padding: 2.5rem 1rem;
		text-align: center;
		min-width: 0;
	}
	.ic {
		display: grid;
		margin-bottom: 0.5rem;
		color: var(--m3c-on-surface-variant);
	}
	.title {
		margin: 0;
		font-size: 1rem;
		line-height: 1.5rem;
		font-weight: 500;
		overflow-wrap: anywhere;
		max-width: 32rem;
	}
	.error .ic {
		color: var(--m3c-error);
	}
	.hint {
		margin: 0;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
		max-width: 32rem;
	}
	.act {
		margin-top: 0.75rem;
	}
</style>
