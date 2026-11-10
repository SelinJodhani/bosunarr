<script lang="ts">
	import { Button, Dialog } from 'm3-svelte';
	import type { ConfirmTarget } from './types';

	let {
		open = $bindable(false),
		target,
		busy = false,
		error = null,
		onconfirm
	}: { open: boolean; target: ConfirmTarget | null; busy?: boolean; error?: string | null; onconfirm: () => void } = $props();

	const del = $derived(target?.action === 'delete');
</script>

<Dialog headline={del ? 'Delete this request?' : 'Decline this request?'} bind:open>
	<div class="request-confirm">
	<p class="dlg">
		{target?.title}{target?.is4k ? ' (4K)' : ''} — requested by {target?.requestedBy}.
		{#if del}
			The request is removed from {target?.serviceName}. Anything already downloaded stays.
		{:else}
			The requester will see it as declined.
		{/if}
	</p>
	{#if error}<p class="error" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" data-danger disabled={!target || busy} onclick={onconfirm}>
			{busy ? (del ? 'Deleting…' : 'Declining…') : del ? 'Delete' : 'Decline'}
		</Button>
	{/snippet}
</Dialog>

<style>
	.dlg {
		margin: 0;
	}
	.error {
		margin: 0.75rem 0 0;
		padding: 0.625rem 0.75rem;
		border-radius: 0.75rem;
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
		font-size: 0.875rem;
	}
	/* app.css's [data-danger] ties with m3-svelte's own variant rules on specificity and loses on
	   load order; repeat it with more weight until the shared rule is fixed. */
	:global(dialog:has(.request-confirm) button.m3-container.filled[data-danger]:not(:disabled)) {
		background-color: var(--m3c-error);
		color: var(--m3c-on-error);
	}
</style>
