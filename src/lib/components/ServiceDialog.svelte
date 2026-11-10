<script lang="ts">
	import { tick, untrack } from 'svelte';
	import { Button, Dialog, Select, TextField } from 'm3-svelte';
	import { SERVICE_TYPES, SERVICE_TYPE_LIST, type PublicService, type ServiceTypeId } from '$lib/services/catalog';
	import { iCheck, iError, iTest } from '$lib/icons';
	import { Icon } from 'm3-svelte';

	let {
		open = $bindable(false),
		editing = null,
		onsaved
	}: { open: boolean; editing: PublicService | null; onsaved: (name: string, added: boolean) => void } = $props();

	let type = $state<ServiceTypeId>('sonarr');
	let name = $state('');
	let url = $state('');
	let externalUrl = $state('');
	let apiKey = $state('');
	let username = $state('');
	let password = $state('');

	/** The one message slot under the form: test result, validation or save error. */
	type Status = { kind: 'idle' | 'busy' | 'ok' | 'error'; message: string };
	let status = $state<Status>({ kind: 'idle', message: '' });
	let busy = $state<'' | 'test' | 'save'>('');
	/** Set when Save's connection test failed: the next Save stores the form without testing. */
	let saveAnyway = $state(false);
	/** Which field the current error belongs to, for its red outline. */
	let badField = $state<'' | 'url' | 'externalUrl' | 'apiKey' | 'username' | 'password' | 'name'>('');
	/** Bumped on every edit so a late test result for old values is dropped. */
	let seq = 0;

	const info = $derived(SERVICE_TYPES[type]);
	/** Default name for a type: "Jellyseerr / Seerr" → "Jellyseerr". */
	const defaultName = (t: ServiceTypeId) => SERVICE_TYPES[t].label.split(' / ')[0];

	// Keep the name in sync with the type until the user types their own.
	let lastAuto = '';
	$effect(() => {
		const label = defaultName(type);
		untrack(() => {
			if (!name || name === lastAuto) name = label;
		});
		lastAuto = label;
	});

	// Reset the form whenever the dialog opens.
	$effect(() => {
		if (!open) return;
		untrack(() => {
			type = editing?.type ?? 'sonarr';
			name = editing?.name ?? '';
			url = editing?.url ?? '';
			externalUrl = editing?.externalUrl ?? '';
			apiKey = username = password = '';
		});
	});

	// Any edit makes the last test / error stale: clear it.
	$effect(() => {
		void [type, name, url, externalUrl, apiKey, username, password, open];
		untrack(() => {
			seq++;
			status = { kind: 'idle', message: '' };
			saveAnyway = false;
			badField = '';
		});
	});

	/** "192.168.1.10:8989" → "http://192.168.1.10:8989". */
	const withScheme = (u: string) => {
		const t = u.trim();
		return !t || /^[a-z][a-z\d+.-]*:\/\//i.test(t) ? t : `http://${t}`;
	};
	/** Adds the scheme in place; waits for the clear-on-edit effect so it can't wipe what comes next. */
	async function fixUrls() {
		if (withScheme(url) !== url) url = withScheme(url);
		if (withScheme(externalUrl) !== externalUrl) externalUrl = withScheme(externalUrl);
		await tick();
	}

	const payload = () => ({
		id: editing?.id,
		type,
		name: name.trim() || defaultName(type),
		url: withScheme(url),
		externalUrl: withScheme(externalUrl) || null,
		credentials: info.credentials === 'userPass' ? { username, password } : { apiKey }
	});

	let formEl = $state<HTMLFormElement>();
	function fail(message: string, field: typeof badField = '') {
		status = { kind: 'error', message };
		badField = field;
		if (field) tick().then(() => (formEl?.elements.namedItem(`svc-${field}`) as HTMLInputElement | null)?.focus());
	}

	/** Client-side checks before talking to the server. */
	function validate(): boolean {
		if (!url.trim()) return (fail('Enter the URL', 'url'), false);
		if (!editing) {
			if (info.credentials === 'apiKey' && !apiKey.trim()) return (fail('Enter the API key', 'apiKey'), false);
			if (info.credentials === 'userPass') {
				if (!username.trim()) return (fail('Enter the username', 'username'), false);
				if (!password) return (fail('Enter the password', 'password'), false);
			}
		}
		return true;
	}

	/** Runs the connection test; returns whether it passed. Ignores results for values edited meanwhile. */
	async function test(): Promise<boolean | null> {
		const mine = ++seq;
		status = { kind: 'busy', message: 'Testing connection…' };
		const r = await fetch('/api/services/test', { method: 'POST', body: JSON.stringify(payload()) }).catch(() => null);
		const body = r ? await r.json().catch(() => ({})) : { error: 'Bosunarr is unreachable' };
		if (mine !== seq) return null;
		if (r?.ok) {
			const v = body.version ? ` · v${String(body.version).replace(/^v/i, '')}` : '';
			status = { kind: 'ok', message: `Connected${v} · ${body.ms} ms` };
			return true;
		}
		fail(body.error ?? `HTTP ${r?.status}`);
		return false;
	}

	async function runTest() {
		await fixUrls();
		if (!validate()) return;
		busy = 'test';
		await test();
		busy = '';
	}

	async function save() {
		await fixUrls();
		if (!validate()) return;
		busy = 'save';
		try {
			if (!saveAnyway) {
				const ok = await test();
				if (ok === null) return;
				if (!ok) {
					status = { kind: 'error', message: `Couldn't connect: ${status.message}` };
					saveAnyway = true;
					return;
				}
			}
			status = { kind: 'busy', message: 'Saving…' };
			const r = await fetch(editing ? `/api/services/${editing.id}` : '/api/services', {
				method: editing ? 'PUT' : 'POST',
				body: JSON.stringify(payload())
			}).catch(() => null);
			if (!r?.ok) {
				fail((r && (await r.json().catch(() => ({}))).error) ?? (r ? `HTTP ${r.status}` : 'Bosunarr is unreachable'));
				return;
			}
			const saved = payload().name;
			open = false;
			onsaved(saved, !editing);
		} finally {
			busy = '';
		}
	}

	const publicHint = $derived(
		type === 'wizarr'
			? 'Invite links you share are built from this. Use the address your invitees can reach.'
			: 'Used for “Open” links. Leave blank if the URL above works from your phone.'
	);
	const keepHint = $derived(editing ? ' Leave blank to keep the saved one.' : '');
	/** Required fields are filled (when editing, blank credentials keep the saved ones). */
	const complete = $derived(
		url.trim() !== '' &&
			(editing != null ||
				(info.credentials === 'apiKey' ? apiKey.trim() !== '' : info.credentials === 'userPass' ? username.trim() !== '' && password !== '' : true))
	);
	const missingHint = 'Fill in the URL and credentials first';
</script>

<!-- The last field's supporting text doubles as the status line (test result, validation or save error): the
     message fades in over it instead of reserving an empty band above the buttons. -->
{#snippet helper(text: string)}
	<div class="helper">
		<p class="hint" class:gone={status.kind !== 'idle'} aria-hidden={status.kind !== 'idle'}>{text}</p>
		<p class="status {status.kind}" class:gone={status.kind === 'idle'} role={status.kind === 'error' ? 'alert' : 'status'} aria-live="polite">
			{#if status.kind === 'ok'}<Icon icon={iCheck} size={16} />{:else if status.kind === 'error'}<Icon icon={iError} size={16} />{/if}
			<span>{status.message}</span>
		</p>
	</div>
{/snippet}

<Dialog headline={editing ? `Edit ${editing.name}` : 'Add a service'} bind:open>
	<form
		class="form"
		novalidate
		bind:this={formEl}
		onsubmit={(e) => {
			e.preventDefault();
			save();
		}}
	>
		<Select
			label="Type"
			aria-label="Type"
			width="100%"
			bind:value={type}
			options={SERVICE_TYPE_LIST.map((t) => ({ value: t.id, text: t.label }))}
		/>
		<TextField name="svc-name" label="Name" bind:value={name} error={badField === 'name'} />
		<!-- No placeholder: m3-svelte's floating label sits on top of it. The example goes underneath instead. -->
		<div class="field">
			<TextField
				name="svc-url"
				label="URL"
				bind:value={url}
				inputmode="url"
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
				error={badField === 'url'}
				aria-invalid={badField === 'url'}
				onblur={fixUrls}
			/>
			<p class="hint">e.g. http://{type}:{info.defaultPort} or 192.168.1.10:{info.defaultPort}</p>
		</div>
		<div class="field">
			<TextField
				name="svc-externalUrl"
				label="Public URL (optional)"
				bind:value={externalUrl}
				inputmode="url"
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
				onblur={fixUrls}
			/>
			{#if info.credentials === 'none'}{@render helper(publicHint)}{:else}<p class="hint">{publicHint}</p>{/if}
		</div>
		{#if info.credentials === 'apiKey'}
			<div class="field">
				<TextField
					name="svc-apiKey"
					label="API key"
					bind:value={apiKey}
					type="password"
					autocomplete="off"
					error={badField === 'apiKey'}
					aria-invalid={badField === 'apiKey'}
				/>
				{@render helper(`${info.keyHint}.${keepHint}`)}
			</div>
		{:else if info.credentials === 'userPass'}
			<TextField
				name="svc-username"
				label="Username"
				bind:value={username}
				autocomplete="off"
				autocapitalize="off"
				error={badField === 'username'}
				aria-invalid={badField === 'username'}
			/>
			<div class="field">
				<TextField
					name="svc-password"
					label="Password"
					bind:value={password}
					type="password"
					autocomplete="new-password"
					error={badField === 'password'}
					aria-invalid={badField === 'password'}
				/>
				{@render helper(`${info.keyHint}.${keepHint}`)}
			</div>
		{/if}
		<!-- Enter in a field submits. -->
		<button type="submit" hidden aria-hidden="true" tabindex="-1"></button>
	</form>
	{#snippet buttons()}
		<!-- After a failed save the test result is already showing; Test makes room for "Save anyway". -->
		{#if !saveAnyway}
			<Button variant="text" onclick={runTest} disabled={busy !== '' || !complete} title={complete ? undefined : missingHint}>
				<Icon icon={iTest} /> Test
			</Button>
		{/if}
		<span class="spacer"></span>
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" onclick={save} disabled={busy !== '' || !complete} title={complete ? undefined : missingHint}>
			{busy === 'save' ? 'Saving…' : saveAnyway ? 'Save anyway' : 'Save'}
		</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: grid;
		gap: 0.75rem;
		width: min(28rem, 100%);
		min-width: 0;
	}
	.form :global(.m3-container) {
		min-width: 0;
		width: 100%;
	}
	.field {
		display: grid;
		gap: 0.25rem;
	}
	/* M3 supporting text: body-small, aligned with the field's 16 px inner padding. */
	.hint {
		margin: 0;
		padding-inline: 1rem;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
	/* Helper text and status share one cell and cross-fade: no reserved gap, and no jump for one-line messages. */
	.helper {
		display: grid;
	}
	.helper > p {
		grid-area: 1 / 1;
		transition: opacity 150ms;
	}
	.helper > .gone {
		opacity: 0;
	}
	/* The status line stays in the accessibility tree (live region); the hidden hint does not. */
	.helper > .hint.gone {
		visibility: hidden;
	}
	.status {
		display: flex;
		align-items: flex-start;
		gap: 0.375rem;
		margin: 0;
		padding-inline: 1rem;
		font-size: 0.75rem;
		line-height: 1rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
	}
	.status :global(svg) {
		flex: none;
	}
	.status.ok {
		color: var(--m3c-primary);
	}
	.status.error {
		color: var(--m3c-error);
	}
	.spacer {
		flex: 1;
	}
</style>
