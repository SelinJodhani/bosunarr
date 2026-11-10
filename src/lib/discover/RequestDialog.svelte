<script lang="ts">
	import { Button, Checkbox, Dialog, Switch, snackbar } from 'm3-svelte';
	import { untrack } from 'svelte';
	import { AVAILABILITY_LABEL, AVAILABILITY_TONE, post } from './client';
	import StatusBadge from './StatusBadge.svelte';
	import type { CreateRequest, DiscoverDetail, SeasonInfo } from './types';

	/** `uhd`: open preset to the 4K (true) or regular (false) copy; undefined picks whichever is still possible. */
	let { open = $bindable(false), detail, uhd, ondone }: { open: boolean; detail: DiscoverDetail; uhd?: boolean; ondone: () => void } = $props();

	let is4k = $state(false);
	let picked = $state<Record<number, boolean>>({});
	let busy = $state(false);
	let error = $state<string | null>(null);

	/** What can still be requested as a regular / 4K copy. */
	const regularOpen = $derived(detail.mediaType === 'movie' ? detail.status === 'none' : detail.seasons.some((s) => s.status === 'none'));
	const uhdOpen = $derived(
		detail.can4k && (detail.mediaType === 'movie' ? detail.status4k === 'none' : detail.seasons.some((s) => s.status4k === 'none'))
	);
	/** Offer the 4K switch only when both copies are still possible; otherwise there's nothing to choose. */
	const showSwitch = $derived(regularOpen && uhdOpen);

	const status = (s: Pick<SeasonInfo, 'status' | 'status4k'>) => (is4k ? s.status4k : s.status);
	const requestable = $derived(detail.seasons.filter((s) => status(s) === 'none'));
	const chosen = $derived(requestable.filter((s) => picked[s.number]).map((s) => s.number));
	const allPicked = $derived(requestable.length > 0 && chosen.length === requestable.length);
	const somePicked = $derived(chosen.length > 0 && !allPicked);
	const canSubmit = $derived(!busy && (detail.mediaType === 'movie' ? status(detail) === 'none' : chosen.length > 0));

	/** Default season: the latest one that can still be requested (never every season — a talk show can have 25). */
	function defaultPick(): Record<number, boolean> {
		const regular = requestable.filter((s) => s.number > 0);
		const latest = (regular.length ? regular : requestable).at(-1);
		return latest ? { [latest.number]: true } : {};
	}

	$effect(() => {
		if (!open) return;
		untrack(() => {
			is4k = uhd ?? (!regularOpen && uhdOpen);
			if (!uhdOpen) is4k = false;
			if (!regularOpen && uhdOpen) is4k = true;
			error = null;
		});
	});
	// Fresh selection when the dialog opens or the 4K switch flips (that changes which seasons are open).
	$effect(() => {
		if (!open) return;
		void is4k;
		untrack(() => (picked = defaultPick()));
	});

	function toggleAll() {
		const v = !allPicked;
		const next: Record<number, boolean> = {};
		for (const s of requestable) next[s.number] = v;
		picked = next;
	}

	async function submit() {
		busy = true;
		error = null;
		const body: CreateRequest = { serviceId: detail.serviceId, mediaType: detail.mediaType, tmdbId: detail.tmdbId, is4k };
		if (detail.mediaType === 'tv') body.seasons = chosen;
		const err = await post('/api/discover/request', body);
		busy = false;
		// Errors stay in the dialog: it sits above the snackbar layer.
		if (err) {
			error = err;
			return;
		}
		open = false;
		snackbar(`Requested ${detail.title}${is4k ? ' in 4K' : ''}`, {}, true);
		ondone();
	}

	const submitLabel = $derived.by(() => {
		if (busy) return 'Requesting…';
		const q = is4k ? ' in 4K' : '';
		if (detail.mediaType === 'tv') return chosen.length ? `Request ${chosen.length} season${chosen.length === 1 ? '' : 's'}${q}` : 'Request';
		return `Request${q}`;
	});
</script>

<Dialog headline={`Request ${detail.title}`} bind:open>
	<div class="body">
		{#if showSwitch}
			<label class="row switch">
				<span>
					<span class="lbl">4K</span>
					<span class="hint">Request the 4K copy instead of the regular one</span>
				</span>
				<Switch bind:checked={is4k} />
			</label>
		{/if}

		{#if detail.mediaType === 'movie'}
			{#if status(detail) !== 'none'}
				<p class="note">Already {AVAILABILITY_LABEL[status(detail)].toLowerCase()}{is4k ? ' in 4K' : ''}.</p>
			{:else}
				<p class="note">
					{detail.title}{detail.year ? ` (${detail.year})` : ''} will be requested{is4k ? ' in 4K' : ''} through {detail.serviceName}.
				</p>
			{/if}
		{:else if detail.seasons.length === 0}
			<p class="note">No seasons listed yet.</p>
		{:else}
			{#if !showSwitch && is4k}<p class="note">Requesting the 4K copy — the regular one is already taken care of.</p>{/if}
			{#if requestable.length > 1}
				<label class="row all">
					<Checkbox><input type="checkbox" checked={allPicked} indeterminate={somePicked} onchange={toggleAll} /></Checkbox>
					<span class="lbl">All seasons</span>
					<span class="hint count">{chosen.length} of {requestable.length}</span>
				</label>
				<hr />
			{/if}
			<ul class="seasons">
				{#each detail.seasons as s (s.number)}
					{@const st = status(s)}
					{@const tone = AVAILABILITY_TONE[st]}
					<li>
						<label class="row" class:disabled={st !== 'none'}>
							<Checkbox>
								<input
									type="checkbox"
									disabled={st !== 'none'}
									checked={st !== 'none' || !!picked[s.number]}
									onchange={(e) => (picked = { ...picked, [s.number]: e.currentTarget.checked })}
								/>
							</Checkbox>
							<span class="grow">
								<span class="lbl">{s.name}</span>
								<span class="hint">{s.episodes} episode{s.episodes === 1 ? '' : 's'}{s.airDate ? ` · ${s.airDate.slice(0, 4)}` : ''}</span>
							</span>
							{#if tone}<StatusBadge {tone} label={AVAILABILITY_LABEL[st]} />{/if}
						</label>
					</li>
				{/each}
			</ul>
			{#if requestable.length === 0}<p class="note">Every season is already requested or available{is4k ? ' in 4K' : ''}.</p>{/if}
		{/if}

		{#if error}<p class="error" role="alert">Couldn't request: {error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" disabled={!canSubmit} onclick={submit}>{submitLabel}</Button>
	{/snippet}
</Dialog>

<style>
	.body {
		display: grid;
		gap: 0.25rem;
		min-width: min(22rem, calc(100vw - 6rem));
	}
	.row {
		display: flex;
		align-items: center;
		gap: 1rem;
		min-height: 3rem;
		cursor: pointer;
	}
	.row :global(.m3-container:has(> input[type='checkbox'])) {
		margin: 0 0.6875rem;
		flex: none;
	}
	.row.disabled {
		cursor: default;
	}
	.row.disabled .grow {
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
	.all .lbl {
		flex: 1;
	}
	.hint {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.note {
		margin: 0.25rem 0;
		color: var(--m3c-on-surface-variant);
	}
	.error {
		margin: 0.5rem 0 0;
		padding: 0.625rem 0.75rem;
		border-radius: 0.75rem;
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
		font-size: 0.875rem;
	}
	.seasons {
		list-style: none;
		margin: 0;
		padding: 0;
		max-height: 45vh;
		max-height: 45dvh;
		overflow-y: auto;
	}
	hr {
		border: none;
		border-top: 1px solid var(--m3c-outline-variant);
		margin: 0.25rem 0;
		width: 100%;
	}
	/* m3-svelte's checkbox has no indeterminate look: primary box with a dash. */
	.all :global(input:indeterminate + .layer-container) {
		color: var(--m3c-primary);
	}
	.all :global(input:indeterminate + .layer-container .checkbox-box) {
		background-color: var(--m3c-primary);
	}
	.all :global(input:indeterminate + .layer-container .checkbox-box::after) {
		content: '';
		position: absolute;
		left: 0.125rem;
		right: 0.125rem;
		top: calc(50% - 1px);
		height: 2px;
		border-radius: 1px;
		background: var(--m3c-on-primary);
	}
</style>
