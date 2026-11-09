<script lang="ts">
	import { Button, Dialog, Select, Switch, snackbar } from 'm3-svelte';
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
		<label class="ta">
			<span>Magnet links or .torrent URLs — one per line</span>
			<textarea bind:value={urls} rows="4" spellcheck="false" autocomplete="off" placeholder="magnet:?xt=urn:btih:…"></textarea>
		</label>
		{#if cats.length}
			<Select label="Category" options={[{ value: '', text: 'None' }, ...cats.map((c) => ({ value: c, text: c }))]} bind:value={category} />
		{/if}
		<label class="sw">
			<span>Add paused</span>
			<Switch bind:checked={paused} />
		</label>
		{#if error}<p class="err" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !urls.trim() || !serviceId} onclick={submit}>Add</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: grid;
		gap: 1rem;
		min-width: min(28rem, 80vw);
	}
	.ta {
		display: grid;
		gap: 0.375rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	textarea {
		font: inherit;
		font-family: ui-monospace, monospace;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface);
		background: var(--m3c-surface-container-highest);
		border: none;
		border-bottom: 1px solid var(--m3c-on-surface-variant);
		border-radius: 0.25rem 0.25rem 0 0;
		padding: 0.75rem;
		resize: vertical;
	}
	textarea:focus {
		outline: none;
		border-bottom: 2px solid var(--m3c-primary);
	}
	.sw {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}
	.err {
		margin: 0;
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
</style>
