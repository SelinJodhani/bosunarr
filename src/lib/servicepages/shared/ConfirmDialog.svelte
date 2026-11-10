<script lang="ts">
	// Confirmation dialog for actions that are hard to undo. `onconfirm` returns an error message (shown
	// inline, the dialog stays open) or null (the dialog closes).
	import type { Snippet } from 'svelte';
	import { Button, Dialog } from 'm3-svelte';
	import { sentence } from './format';

	let {
		open = $bindable(false),
		headline,
		confirm,
		danger = false,
		disabled = false,
		onconfirm,
		children
	}: {
		open: boolean;
		headline: string;
		confirm: string;
		danger?: boolean;
		disabled?: boolean;
		onconfirm: () => Promise<string | null>;
		children: Snippet;
	} = $props();

	let busy = $state(false);
	let error = $state<string | null>(null);
	$effect(() => {
		if (open) error = null;
	});

	async function go() {
		if (busy) return;
		busy = true;
		error = null;
		try {
			const err = await onconfirm();
			if (err) error = sentence(err);
			else open = false;
		} finally {
			busy = false;
		}
	}
</script>

<Dialog {headline} bind:open>
	<div class="body">
		{@render children()}
		{#if error}<p class="err" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" data-danger={danger || undefined} disabled={busy || disabled} onclick={go}>{confirm}</Button>
	{/snippet}
</Dialog>

<style>
	.body {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		overflow-wrap: anywhere;
	}
	.body :global(p) {
		margin: 0;
	}
	.err {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
</style>
