<script lang="ts">
	import { untrack } from 'svelte';
	import { Button, Chip, Dialog, Select, Switch, TextField } from 'm3-svelte';
	import type { CreatedInvite, CreateInviteRequest, WizarrLibrary, WizarrServer } from './types';
	import { send } from './client';

	let {
		open = $bindable(false),
		serviceId,
		servers,
		libraries,
		created
	}: {
		open: boolean;
		serviceId: number;
		servers: WizarrServer[];
		libraries: WizarrLibrary[];
		created: (inv: CreatedInvite) => void;
	} = $props();

	const usable = $derived(servers.filter((s) => s.verified));

	let serverIds = $state<number[]>([]);
	let libraryIds = $state<number[]>([]);
	let expiry = $state('7');
	let unlimited = $state(false);
	let duration = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);

	// Reset the form each time the dialog opens (untrack: only `open` should trigger this).
	$effect(() => {
		if (!open) return;
		untrack(() => {
			serverIds = usable.length === 1 ? [usable[0].id] : [];
			libraryIds = [];
			expiry = '7';
			unlimited = false;
			duration = '';
			error = null;
		});
	});

	const libs = $derived(libraries.filter((l) => l.enabled && (l.serverId === null || serverIds.includes(l.serverId))));
	const durationError = $derived(duration.trim() !== '' && (!/^\d{1,4}$/.test(duration.trim()) || Number(duration) < 1 || Number(duration) > 3650));

	function toggle(list: number[], id: number) {
		return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
	}
	function toggleServer(id: number) {
		serverIds = toggle(serverIds, id);
		// Drop libraries that no longer belong to a chosen server.
		const allowed = new Set(libraries.filter((l) => l.serverId === null || serverIds.includes(l.serverId)).map((l) => l.id));
		libraryIds = libraryIds.filter((l) => allowed.has(l));
	}

	async function submit() {
		busy = true;
		error = null;
		const req: CreateInviteRequest = {
			serverIds,
			libraryIds,
			expiresInDays: expiry === 'never' ? null : (Number(expiry) as 1 | 7 | 30),
			unlimited,
			durationDays: duration.trim() ? Number(duration) : null
		};
		const res = await send<CreatedInvite>('POST', `/api/svc/wizarr/${serviceId}/invitations`, req);
		busy = false;
		if (res.error || !res.data) {
			error = res.error ?? 'Could not create the invite';
			return;
		}
		open = false;
		created(res.data);
	}
</script>

<Dialog headline="New invite" bind:open>
	<div class="form">
		{#if usable.length === 0}
			<p class="err" role="alert">Wizarr has no verified media server. Add one in Wizarr first.</p>
		{:else}
			<fieldset>
				<legend>Server</legend>
				<div class="chips">
					{#each usable as s (s.id)}
						<Chip variant="general" selected={serverIds.includes(s.id)} onclick={() => toggleServer(s.id)}>{s.name}</Chip>
					{/each}
				</div>
			</fieldset>

			<div class="row2">
				<Select
					label="Invite expires"
					options={[
						{ value: '1', text: 'In 1 day' },
						{ value: '7', text: 'In 7 days' },
						{ value: '30', text: 'In 30 days' },
						{ value: 'never', text: 'Never' }
					]}
					bind:value={expiry}
				/>
				<div class="field">
					<TextField label="Access (days)" inputmode="numeric" autocomplete="off" bind:value={duration} error={durationError} />
					<p class="hint">{durationError ? '1–3650 days' : 'How long new users keep access. Blank = forever.'}</p>
				</div>
			</div>

			<label class="sw">
				<span>
					<span class="sw-title">Unlimited uses</span>
					<span class="hint">{unlimited ? 'Anyone with the link can join until it expires' : 'Single use: the first person to join uses it up'}</span>
				</span>
				<Switch bind:checked={unlimited} />
			</label>

			<fieldset>
				<legend>Libraries</legend>
				{#if !serverIds.length}
					<p class="hint">Choose a server to pick libraries.</p>
				{:else if libs.length === 0}
					<p class="hint">No libraries listed for this server. New users get the server's defaults.</p>
				{:else}
					<div class="chips">
						{#each libs as l (l.id)}
							<Chip variant="general" selected={libraryIds.includes(l.id)} onclick={() => (libraryIds = toggle(libraryIds, l.id))}>{l.name}</Chip>
						{/each}
					</div>
					<p class="hint">{libraryIds.length ? `${libraryIds.length} selected` : 'None selected: Wizarr uses its default libraries.'}</p>
				{/if}
			</fieldset>
		{/if}
		{#if error}<p class="err" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !serverIds.length || durationError} onclick={submit}>Create</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: grid;
		gap: 1rem;
		width: min(30rem, 78vw);
	}
	fieldset {
		border: 0;
		margin: 0;
		padding: 0;
		min-width: 0;
		display: grid;
		gap: 0.5rem;
	}
	legend {
		padding: 0;
		margin-bottom: 0.5rem;
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--m3c-on-surface-variant);
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.row2 {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(11rem, 100%), 1fr));
		gap: 0.75rem;
		align-items: start;
	}
	.row2 :global(.m3-container) {
		width: 100%;
		min-width: 0;
	}
	.field {
		display: grid;
		gap: 0.25rem;
		min-width: 0;
	}
	.hint {
		margin: 0;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.sw {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
	}
	.sw > span {
		display: grid;
		gap: 0.125rem;
		min-width: 0;
	}
	.sw-title {
		font-size: 0.875rem;
	}
	.err {
		margin: 0;
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
</style>
