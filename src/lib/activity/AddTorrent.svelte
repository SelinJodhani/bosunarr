<script lang="ts">
	import { Button, Dialog, Select, Switch, TextFieldMultiline, snackbar } from 'm3-svelte';
	import { categoryLabel } from './format';
	import { act } from './live.svelte';
	import type { ClientSummary } from './types';

	let { open = $bindable(false), clients }: { open: boolean; clients: ClientSummary[] } = $props();

	let serviceId = $state('');
	let urls = $state('');
	let category = $state('');
	let paused = $state(false);
	let cats = $state<string[]>([]);
	let error = $state<string | null>(null);
	let busy = $state(false);

	$effect(() => {
		if (open && !serviceId && clients[0]) serviceId = String(clients[0].id);
	});
	// A stale error from an earlier attempt goes away once the user edits or reopens.
	$effect(() => {
		void urls;
		void open;
		error = null;
	});
	$effect(() => {
		const id = serviceId;
		if (!open || !id) return;
		cats = [];
		fetch(`/api/activity/categories/${id}`)
			.then((r) => (r.ok ? r.json() : []))
			.then((c: string[]) => id === serviceId && (cats = c))
			.catch(() => {});
	});

	async function submit() {
		busy = true;
		error = await act({ add: true, serviceId: Number(serviceId), urls, category: category || undefined, paused });
		busy = false;
		if (!error) {
			const n = urls.split('\n').filter((l) => l.trim()).length;
			snackbar(n > 1 ? `Added ${n} torrents` : 'Torrent added', {}, true);
			urls = '';
			open = false;
		}
	}
</script>

<Dialog headline="Add torrent" bind:open>
	<div class="form">
		{#if clients.length > 1}
			<Select label="Download client" options={clients.map((c) => ({ value: String(c.id), text: c.name }))} bind:value={serviceId} />
		{/if}
		<div class="ta">
			<TextFieldMultiline label="Magnet links or .torrent URLs" bind:value={urls} rows={4} spellcheck="false" autocomplete="off" disabled={busy} error={!!error} />
			<p class="support">One per line. Up to 50 at a time.</p>
		</div>
		{#if cats.length}
			<Select label="Category" options={[{ value: '', text: 'None' }, ...cats.map((c) => ({ value: c, text: categoryLabel(c) }))]} bind:value={category} />
		{/if}
		<label class="sw">
			<span>Add paused</span>
			<Switch bind:checked={paused} disabled={busy} />
		</label>
		{#if error}<p class="err" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" disabled={busy} onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !urls.trim() || !serviceId} onclick={submit}>{busy ? 'Adding…' : 'Add'}</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: grid;
		gap: 1rem;
		width: min(28rem, calc(100vw - 6rem));
	}
	.ta :global(.m3-container) {
		width: 100%;
		min-width: 0;
	}
	.ta :global(textarea) {
		font-family: ui-monospace, monospace;
		font-size: 0.875rem;
		line-height: 1.25rem;
		overflow-wrap: anywhere;
	}
	.support {
		margin: 0.25rem 1rem 0;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.sw {
		display: flex;
		justify-content: space-between;
		align-items: center;
		min-height: 2.5rem;
		cursor: pointer;
	}
	.err {
		margin: 0;
		color: var(--m3c-error);
		font-size: 0.875rem;
		overflow-wrap: anywhere;
	}
</style>
