<script lang="ts">
	import { Button, Dialog, Select, TextField } from 'm3-svelte';
	import { SERVICE_TYPES, SERVICE_TYPE_LIST, type PublicService, type ServiceTypeId } from '$lib/services/catalog';
	import { iCheck, iError, iTest } from '$lib/icons';
	import { Icon } from 'm3-svelte';

	let {
		open = $bindable(false),
		editing = null,
		onsaved
	}: { open: boolean; editing: PublicService | null; onsaved: () => void } = $props();

	let type = $state<ServiceTypeId>('sonarr');
	let name = $state('');
	let url = $state('');
	let externalUrl = $state('');
	let apiKey = $state('');
	let username = $state('');
	let password = $state('');
	let test = $state<{ status: 'idle' | 'running' | 'ok' | 'error'; message: string }>({ status: 'idle', message: '' });
	let saving = $state(false);
	let error = $state('');

	const info = $derived(SERVICE_TYPES[type]);

	// Keep the name in sync with the type until the user types their own.
	let lastAuto = '';
	$effect(() => {
		const label = SERVICE_TYPES[type].label;
		if (!name || name === lastAuto) name = label;
		lastAuto = label;
	});

	// Reset the form whenever the dialog opens.
	$effect(() => {
		if (!open) return;
		type = editing?.type ?? 'sonarr';
		name = editing?.name ?? '';
		url = editing?.url ?? '';
		externalUrl = editing?.externalUrl ?? '';
		apiKey = username = password = '';
		test = { status: 'idle', message: '' };
		error = '';
	});

	const payload = () => ({
		id: editing?.id,
		type,
		name: name || info.label,
		url,
		externalUrl: externalUrl || null,
		credentials: info.credentials === 'userPass' ? { username, password } : { apiKey }
	});

	async function runTest() {
		test = { status: 'running', message: 'Testing…' };
		const r = await fetch('/api/services/test', { method: 'POST', body: JSON.stringify(payload()) });
		const body = await r.json().catch(() => ({}));
		test = r.ok
			? { status: 'ok', message: `Connected${body.version ? ` · v${body.version}` : ''} · ${body.ms} ms` }
			: { status: 'error', message: body.error ?? `HTTP ${r.status}` };
	}

	async function save() {
		saving = true;
		error = '';
		const r = await fetch(editing ? `/api/services/${editing.id}` : '/api/services', {
			method: editing ? 'PUT' : 'POST',
			body: JSON.stringify(payload())
		});
		saving = false;
		if (!r.ok) {
			error = (await r.json().catch(() => ({}))).error ?? `HTTP ${r.status}`;
			return;
		}
		open = false;
		onsaved();
	}

	const keep = $derived(editing ? ' (leave blank to keep)' : '');
</script>

<Dialog headline={editing ? `Edit ${editing.name}` : 'Add a service'} bind:open>
	<div class="form">
		<Select label="Type" bind:value={type} options={SERVICE_TYPE_LIST.map((t) => ({ value: t.id, text: t.label }))} />
		<TextField label="Name" bind:value={name} />
		<TextField label="URL" bind:value={url} placeholder={`http://192.168.1.10:${info.defaultPort}`} inputmode="url" autocomplete="off" />
		<TextField label="Public URL (optional, for 'open' links)" bind:value={externalUrl} inputmode="url" autocomplete="off" />
		{#if info.credentials === 'apiKey'}
			<TextField label={`API key${keep}`} bind:value={apiKey} type="password" autocomplete="off" />
		{:else if info.credentials === 'userPass'}
			<TextField label={`Username${keep}`} bind:value={username} autocomplete="off" />
			<TextField label={`Password${keep}`} bind:value={password} type="password" autocomplete="new-password" />
		{/if}
		<p class="hint">{info.keyHint}</p>
		{#if test.status !== 'idle'}
			<p class="test {test.status}" role="status">
				{#if test.status === 'ok'}<Icon icon={iCheck} />{:else if test.status === 'error'}<Icon icon={iError} />{/if}
				{test.message}
			</p>
		{/if}
		{#if error}<p class="test error" role="alert">{error}</p>{/if}
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={runTest} disabled={!url || test.status === 'running'}>
			<Icon icon={iTest} /> Test
		</Button>
		<Button variant="text" onclick={() => (open = false)}>Cancel</Button>
		<Button variant="filled" onclick={save} disabled={!url || saving}>{saving ? 'Saving…' : 'Save'}</Button>
	{/snippet}
</Dialog>

<style>
	.form {
		display: grid;
		gap: 0.75rem;
		min-width: min(28rem, 80vw);
	}
	.hint {
		margin: 0;
		font-size: 0.8rem;
		color: var(--m3c-on-surface-variant);
	}
	.test {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		margin: 0;
		font-size: 0.875rem;
	}
	.test.ok {
		color: var(--m3c-primary);
	}
	.test.error {
		color: var(--m3c-error);
	}
</style>
