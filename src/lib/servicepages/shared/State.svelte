<script lang="ts">
	// Loading / error / empty wrapper for the lists on service screens (the dashboard widgets have their own).
	import type { Snippet } from 'svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { LoadingIndicator } from 'm3-svelte';
	import Empty from './Empty.svelte';
	import { sentence } from './format';

	let {
		loading,
		error,
		empty,
		icon,
		title = 'Nothing here yet',
		hint,
		onretry,
		action,
		children
	}: {
		loading: boolean;
		error: string | null;
		empty: boolean;
		icon?: IconifyIcon;
		title?: string;
		hint?: string | null;
		onretry?: () => void;
		action?: Snippet;
		children: Snippet;
	} = $props();
</script>

{#if error && empty}
	<Empty error title="Couldn't load this" hint={sentence(error)} {onretry} />
{:else if empty && loading}
	<div class="center"><LoadingIndicator aria-label="Loading" /></div>
{:else if empty}
	<Empty {icon} {title} {hint} {action} />
{:else}
	{@render children()}
	{#if error}<p class="stale" title={error}>Couldn't refresh — showing the last data we had.</p>{/if}
{/if}

<style>
	.center {
		display: grid;
		place-items: center;
		min-height: 8rem;
	}
	.stale {
		margin: 0;
		font-size: 0.75rem;
		color: var(--m3c-error);
	}
</style>
