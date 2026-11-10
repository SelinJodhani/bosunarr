<script lang="ts" module>
	// Remembered while moving around the app.
	const ui = $state({ tab: 'playing' });
</script>

<script lang="ts">
	import { untrack } from 'svelte';
	import { Button, Dialog, Icon, TextField, TextFieldMultiline, snackbar } from 'm3-svelte';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import iRestart from '@ktibow/iconset-material-symbols/restart-alt-rounded';
	import iTv from '@ktibow/iconset-material-symbols/live-tv-rounded';
	import iFolder from '@ktibow/iconset-material-symbols/video-library-rounded';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Card from '../shared/Card.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import ConfirmDialog from '../shared/ConfirmDialog.svelte';
	import { sentence } from '../shared/format';
	import type { SummaryNotice } from '../shared/types';
	import Sessions from './Sessions.svelte';
	import Libraries from './Libraries.svelte';
	import Latest from './Latest.svelte';
	import Manage from './Manage.svelte';
	import { act } from './api';
	import type { JfAdminData, JfLibrary, JfSession, JfSummary, JfTask } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// The page is re-created per service ({#key} in the route), so reading the id once is intended.
	const base = `/api/svc/jellyfin/${untrack(() => service.id)}`;
	const now = live<{ sessions: JfSession[] }>(`${base}/sessions`, 5_000, { persist: false });
	const sum = live<JfSummary>(`${base}/summary`, 20_000);
	const man = live<JfAdminData>(`${base}/manage`, 10_000, { persist: false });

	const sessions = $derived(now.state.data?.sessions ?? []);
	const s = $derived(sum.state.data);
	const m = $derived(man.state.data);

	const tabs = $derived([
		{ name: 'Playing', value: 'playing' },
		{ name: 'Libraries', value: 'libraries' },
		{ name: 'Tasks', value: 'tasks' },
		...(m?.users ? [{ name: 'Users', value: 'users' }] : []),
		...(m?.activity ? [{ name: 'Activity', value: 'activity' }] : [])
	]);
	$effect(() => {
		if (m && !tabs.some((t) => t.value === ui.tab)) ui.tab = 'playing';
	});

	const fmt = new Intl.NumberFormat();
	const stats = $derived.by(() => {
		const c = s?.counts;
		const out: Array<{ value: string; label: string; onclick?: () => void; tone?: 'info' }> = [
			{ value: fmt.format(sessions.length), label: 'Playing now', onclick: () => (ui.tab = 'playing'), tone: sessions.length ? 'info' : undefined }
		];
		if (!c) return out;
		for (const [n, label] of [
			[c.movies, 'Movies'],
			[c.series, 'Series'],
			[c.episodes, 'Episodes'],
			[c.albums, 'Albums'],
			[c.songs, 'Songs'],
			[c.books, 'Books']
		] as Array<[number, string]>) {
			if (Number(n) > 0 && out.length < 4) out.push({ value: fmt.format(n), label, onclick: () => (ui.tab = 'libraries') });
		}
		return out;
	});
	const notices = $derived.by((): SummaryNotice[] => {
		if (!s) return [];
		const out: SummaryNotice[] = [];
		if (s.server.shuttingDown) out.push({ tone: 'warn', icon: iRestart, text: 'The server is shutting down.' });
		if (s.server.restartPending) out.push({ tone: 'warn', icon: iRestart, text: 'Jellyfin needs a restart to finish applying changes.' });
		if (s.server.updateAvailable) out.push({ tone: 'update', text: 'A Jellyfin update is available.' });
		return out;
	});

	/* ---------------- actions ---------------- */
	/** Harmless actions run straight away; the snackbar says what happened. */
	async function quick(action: Parameters<typeof act>[1], done: string, fail: string, after: () => void) {
		const err = await act(service.id, action);
		snackbar(err ? `${fail} ${sentence(err)}` : done, {}, true);
		after();
	}
	let scanning = $state<Record<string, boolean>>({});
	async function scan(l: JfLibrary) {
		scanning[l.id] = true;
		await quick({ action: 'scan', library: l.id }, `Scanning ${l.name}`, `Couldn't scan ${l.name}.`, sum.refresh);
		scanning[l.id] = false;
	}
	let scanningAll = $state(false);
	async function scanAll() {
		scanningAll = true;
		await quick({ action: 'scanAll' }, 'Scanning all libraries', "Couldn't start the scan.", () => (sum.refresh(), man.refresh()));
		scanningAll = false;
	}
	const runTask = (t: JfTask) => quick({ action: 'runTask', task: t.id }, `Started ${t.name}`, `Couldn't start ${t.name}.`, man.refresh);

	// Stopping someone's playback interrupts them, so that one asks first.
	let stopTarget = $state<JfSession | null>(null);
	let stopOpen = $state(false);
	function stop(x: JfSession) {
		stopTarget = x;
		stopOpen = true;
	}
	async function confirmStop(): Promise<string | null> {
		const x = stopTarget;
		if (!x) return null;
		const err = await act(service.id, { action: 'stop', session: x.id });
		if (err) return err;
		snackbar(`Stopped playback for ${x.user}`, {}, true);
		now.refresh();
		return null;
	}

	let msgOpen = $state(false);
	let msgTarget = $state<JfSession | null>(null);
	let msgHeader = $state('');
	let msgText = $state('');
	let msgBusy = $state(false);
	let msgError = $state<string | null>(null);
	const msgValid = $derived(msgText.trim().length > 0 && msgText.length <= 500 && msgHeader.length <= 80);

	function message(x: JfSession) {
		msgTarget = x;
		msgHeader = '';
		msgText = '';
		msgError = null;
		msgOpen = true;
	}
	async function sendMessage() {
		if (!msgTarget || !msgValid || msgBusy) return;
		msgBusy = true;
		const err = await act(service.id, { action: 'message', session: msgTarget.id, header: msgHeader.trim() || undefined, text: msgText.trim() });
		msgBusy = false;
		if (err) {
			msgError = sentence(err);
			return;
		}
		msgOpen = false;
		snackbar(`Message sent to ${msgTarget.user}`, {}, true);
	}
</script>

{#if !s}
	{#if sum.state.error}
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(sum.state.error)} onretry={() => sum.refresh()} />
	{:else}
		<Loading label="Loading Jellyfin" />
	{/if}
{:else}
	<Summary meta={[s.server.name, s.server.version && `Jellyfin ${s.server.version}`, s.server.os]} {stats} {notices} stale={sum.state.error} />

	<ServiceTabs items={tabs} bind:tab={ui.tab}>
		{#if ui.tab === 'playing'}
			{#if now.state.data}
				{#if sessions.length}
					<Sessions {sessions} {isAdmin} onstop={stop} onmessage={message} />
				{:else}
					<Empty icon={iTv} title="No one is watching right now" />
				{/if}
			{:else if now.state.error}
				<Empty error title="Couldn't load sessions" hint={sentence(now.state.error)} onretry={() => now.refresh()} />
			{:else}
				<Loading label="Loading sessions" />
			{/if}
			{#if s.latest.length}
				<Card title="Recently added">
					<Latest items={s.latest} />
				</Card>
			{/if}
		{:else if ui.tab === 'libraries'}
			{#if s.libraries.length}
				<Card title="Libraries" count={s.libraries.length}>
					{#snippet actions()}
						{#if isAdmin}
							<Button variant="tonal" disabled={scanningAll} onclick={scanAll}><Icon icon={iRefresh} /> Scan all</Button>
						{/if}
					{/snippet}
					<Libraries libraries={s.libraries.map((l) => (scanning[l.id] ? { ...l, scanning: true } : l))} {isAdmin} onscan={scan} />
				</Card>
			{:else}
				<Empty icon={iFolder} title="No libraries yet" hint="Add libraries in Jellyfin's dashboard." />
			{/if}
		{:else if m}
			<Card>
				<Manage data={m} view={ui.tab === 'users' ? 'users' : ui.tab === 'activity' ? 'activity' : 'tasks'} {isAdmin} onrun={runTask} />
			</Card>
		{:else if man.state.error}
			<Empty error title="Couldn't load this" hint={sentence(man.state.error)} onretry={() => man.refresh()} />
		{:else}
			<Loading />
		{/if}
	</ServiceTabs>
{/if}

<ConfirmDialog bind:open={stopOpen} headline="Stop playback?" confirm="Stop" danger onconfirm={confirmStop}>
	{#if stopTarget}
		<p>
			{stopTarget.user} is watching {stopTarget.title}{stopTarget.subtitle ? ` (${stopTarget.subtitle})` : ''} on {stopTarget.device || stopTarget.client || 'a device'}.
			Playback stops for them right away.
		</p>
	{/if}
</ConfirmDialog>

<Dialog headline="Send a message" bind:open={msgOpen}>
	<p class="dlg">Shows on {msgTarget?.user ?? 'their'}'s screen ({msgTarget?.device || msgTarget?.client || 'device'}) for 10 seconds.</p>
	<div class="fields">
		<TextField label="Title (optional)" bind:value={msgHeader} maxlength={80} autocomplete="off" />
		<TextFieldMultiline label="Message" bind:value={msgText} maxlength={500} required />
	</div>
	{#if msgError}<p class="err" role="alert">{msgError}</p>{/if}
	{#snippet buttons()}
		<Button variant="text" onclick={() => (msgOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={msgBusy || !msgValid} onclick={sendMessage}>Send</Button>
	{/snippet}
</Dialog>

<style>
	.dlg {
		margin: 0 0 0.75rem;
		overflow-wrap: anywhere;
	}
	.err {
		margin: 0.75rem 0 0;
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.fields {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.fields :global(.m3-container) {
		min-width: 0;
		width: 100%;
	}
</style>
