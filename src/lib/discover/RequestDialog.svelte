<script lang="ts">
	import { Button, Checkbox, Dialog, Switch, snackbar } from 'm3-svelte';
	import { untrack } from 'svelte';
	import { AVAILABILITY_LABEL, post } from './client';
	import type { CreateRequest, DiscoverDetail } from './types';

	let { open = $bindable(false), detail, ondone }: { open: boolean; detail: DiscoverDetail; ondone: () => void } = $props();

	let is4k = $state(false);
	let picked = $state<Record<number, boolean>>({});
	let busy = $state(false);

	const status = (s: { status: DiscoverDetail['status']; status4k: DiscoverDetail['status'] }) => (is4k ? s.status4k : s.status);
	const requestable = $derived(detail.seasons.filter((s) => status(s) === 'none'));
	const chosen = $derived(requestable.filter((s) => picked[s.number]).map((s) => s.number));
	const allPicked = $derived(requestable.length > 0 && chosen.length === requestable.length);
	const movieBlocked = $derived(detail.mediaType === 'movie' && status(detail) !== 'none');
	const canSubmit = $derived(!busy && (detail.mediaType === 'movie' ? !movieBlocked : chosen.length > 0));

	// Fresh selection each time the dialog opens: everything that can still be requested.
	// Default to 4K only when the regular copy of a movie is already taken care of.
	$effect(() => {
		if (!open) return;
		untrack(() => (is4k = detail.can4k && detail.mediaType === 'movie' && detail.status !== 'none' && detail.status4k === 'none'));
	});
	// Re-runs when the 4K switch flips, since that changes which seasons can be requested.
	$effect(() => {
		if (!open) return;
		void is4k;
		untrack(() => {
			const next: Record<number, boolean> = {};
			for (const s of requestable) next[s.number] = true;
			picked = next;
		});
	});

	function toggleAll() {
		const v = !allPicked;
		const next: Record<number, boolean> = {};
		for (const s of requestable) next[s.number] = v;
		picked = next;
	}

	async function submit() {
		busy = true;
		const body: CreateRequest = { serviceId: detail.serviceId, mediaType: detail.mediaType, tmdbId: detail.tmdbId, is4k };
		if (detail.mediaType === 'tv') body.seasons = chosen;
		const err = await post('/api/discover/request', body);
		busy = false;
		if (!err) open = false;
		snackbar(err ?? `Requested ${detail.title}${is4k ? ' in 4K' : ''}`, {}, true);
		if (!err) ondone();
	}
</script>

<Dialog headline={`Request ${detail.title}`} bind:open>
	<div class="body">
		{#if detail.can4k}
			<label class="row switch">
				<span>
					<span class="lbl">4K</span>
					<span class="hint">Send to the 4K {detail.mediaType === 'movie' ? 'Radarr' : 'Sonarr'}</span>
				</span>
				<Switch bind:checked={is4k} />
			</label>
		{/if}

		{#if detail.mediaType === 'movie'}
			{#if movieBlocked}
				<p class="note">Already {AVAILABILITY_LABEL[status(detail)].toLowerCase()}{is4k ? ' in 4K' : ''}.</p>
			{:else}
				<p class="note">{detail.title}{detail.year ? ` (${detail.year})` : ''} will be sent to {detail.serviceName}{is4k ? ' as a 4K request' : ''}.</p>
			{/if}
		{:else if detail.seasons.length === 0}
			<p class="note">No seasons listed yet.</p>
		{:else}
			{#if requestable.length > 1}
				<label class="row">
					<Checkbox><input type="checkbox" checked={allPicked} onchange={toggleAll} /></Checkbox>
					<span class="lbl">All seasons</span>
				</label>
				<hr />
			{/if}
			<ul class="seasons">
				{#each detail.seasons as s (s.number)}
					{@const st = status(s)}
					<li>
						<label class="row" class:disabled={st !== 'none'}>
							<Checkbox>
								<input type="checkbox" disabled={st !== 'none'} checked={st !== 'none' || !!picked[s.number]} onchange={(e) => (picked[s.number] = e.currentTarget.checked)} />
							</Checkbox>
							<span class="grow">
								<span class="lbl">{s.name}</span>
								<span class="hint">{s.episodes} episode{s.episodes === 1 ? '' : 's'}{s.airDate ? ` · ${s.airDate.slice(0, 4)}` : ''}</span>
							</span>
							{#if st !== 'none'}<span class="chip">{AVAILABILITY_LABEL[st]}</span>{/if}
						</label>
					</li>
				{/each}
			</ul>
			{#if requestable.length === 0}<p class="note">Every season is already requested or available{is4k ? ' in 4K' : ''}.</p>{/if}
		{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" disabled={!canSubmit} onclick={submit}>
			{busy ? 'Requesting…' : detail.mediaType === 'tv' && chosen.length ? `Request ${chosen.length} season${chosen.length === 1 ? '' : 's'}` : 'Request'}
		</Button>
	{/snippet}
</Dialog>

<style>
	.body {
		display: grid;
		gap: 0.25rem;
		min-width: min(22rem, 70vw);
	}
	.row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		min-height: 3rem;
		cursor: pointer;
	}
	.row.disabled {
		cursor: default;
		opacity: 0.7;
	}
	.switch {
		justify-content: space-between;
	}
	.switch > span,
	.grow {
		display: grid;
		flex: 1;
		min-width: 0;
	}
	.lbl {
		font-weight: 500;
	}
	.hint {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.note {
		margin: 0.25rem 0;
		color: var(--m3c-on-surface-variant);
	}
	.seasons {
		list-style: none;
		margin: 0;
		padding: 0;
		max-height: 45vh;
		overflow-y: auto;
	}
	hr {
		border: none;
		border-top: 1px solid var(--m3c-outline-variant);
		margin: 0.25rem 0;
		width: 100%;
	}
	.chip {
		flex: none;
		font-size: 0.75rem;
		font-weight: 500;
		padding: 0.125rem 0.5rem;
		border-radius: 0.375rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
</style>
