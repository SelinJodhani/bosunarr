<script lang="ts">
	// Shared loading / error / empty states so every widget behaves the same.
	import type { Snippet } from 'svelte';
	import { LoadingIndicator } from 'm3-svelte';

	let {
		loading,
		error,
		empty,
		emptyText = 'Nothing here right now',
		children
	}: { loading: boolean; error: string | null; empty: boolean; emptyText?: string; children: Snippet } = $props();
</script>

{#if error && empty}
	<p class="msg error" role="alert">{error}</p>
{:else if empty && loading}
	<div class="center"><LoadingIndicator aria-label="Loading" /></div>
{:else if empty}
	<p class="msg">{emptyText}</p>
{:else}
	{@render children()}
	{#if error}<p class="stale" title={error}>Showing last known data</p>{/if}
{/if}

<style>
	.msg {
		margin: 0.5rem 0;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	.error {
		color: var(--m3c-error);
	}
	.center {
		display: grid;
		place-items: center;
		min-height: 4rem;
	}
	.stale {
		margin: 0.5rem 0 0;
		font-size: 0.75rem;
		color: var(--m3c-error);
	}
</style>
