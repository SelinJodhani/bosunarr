<script lang="ts">
	import { untrack } from 'svelte';
	import { Button, Dialog, Icon, LoadingIndicator, TextField, TextFieldMultiline, snackbar } from 'm3-svelte';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import iRestart from '@ktibow/iconset-material-symbols/restart-alt-rounded';
	import iUpdate from '@ktibow/iconset-material-symbols/system-update-rounded';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '$lib/servicepages/types';
	import Sessions from './Sessions.svelte';
	import Libraries from './Libraries.svelte';
	import Latest from './Latest.svelte';
	import Manage from './Manage.svelte';
	import { act } from './api';
	import type { JfAction, JfAdminData, JfLibrary, JfSession, JfSummary, JfTask } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// The page is re-created per service ({#key} in the route), so reading the id once is intended.
	const base = `/api/svc/jellyfin/${untrack(() => service.id)}`;
	const now = live<{ sessions: JfSession[] }>(`${base}/sessions`, 5_000, { persist: false });
	const sum = live<JfSummary>(`${base}/summary`, 20_000);
	const man = live<JfAdminData>(`${base}/manage`, 10_000, { persist: false });

	const sessions = $derived(now.state.data?.sessions ?? []);
	const s = $derived(sum.state.data);
	const m = $derived(man.state.data);

	const fmt = new Intl.NumberFormat();
	const countChips = $derived.by(() => {
		const c = s?.counts;
		if (!c) return [];
		return [
			[c.movies, 'Movies'],
			[c.series, 'Series'],
			[c.episodes, 'Episodes'],
			[c.albums, 'Albums'],
			[c.songs, 'Songs'],
			[c.books, 'Books']
		].filter(([n]) => Number(n) > 0) as [number, string][];
	});

	/* ---------------- confirm + message dialogs ---------------- */
	type Pending = { headline: string; body: string; confirm: string; action: JfAction; done: string; after: () => void };
	let pending = $state<Pending | null>(null);
	let confirmOpen = $state(false);
	let busy = $state(false);

	function ask(p: Pending) {
		pending = p;
		confirmOpen = true;
	}

	async function run(action: JfAction, done: string, after: () => void) {
		busy = true;
		const err = await act(service.id, action);
		busy = false;
		snackbar(err ?? done, {}, true);
		after();
		return err;
	}

	async function confirm() {
		if (!pending) return;
		const p = pending;
		const err = await run(p.action, p.done, p.after);
		if (!err) confirmOpen = false;
	}

	const stop = (x: JfSession) =>
		ask({
			headline: 'Stop playback?',
			body: `${x.user} is watching ${x.title}${x.subtitle ? ` (${x.subtitle})` : ''} on ${x.device || x.client || 'a device'}. Playback stops for them right away.`,
			confirm: 'Stop',
			action: { action: 'stop', session: x.id },
			done: `Stopped playback for ${x.user}`,
			after: now.refresh
		});
	const scan = (l: JfLibrary) =>
		ask({
			headline: `Scan ${l.name}?`,
			body: 'Jellyfin looks for new, changed and removed files in this library. Large libraries can take a while.',
			confirm: 'Scan',
			action: { action: 'scan', library: l.id },
			done: `Scanning ${l.name}`,
			after: sum.refresh
		});
	const scanAll = () =>
		ask({
			headline: 'Scan all libraries?',
			body: 'Jellyfin scans every library for new, changed and removed files. This can take a while and uses disk and CPU.',
			confirm: 'Scan all',
			action: { action: 'scanAll' },
			done: 'Scanning all libraries',
			after: () => (sum.refresh(), man.refresh())
		});
	const runTask = (t: JfTask) =>
		ask({
			headline: `Run ${t.name}?`,
			body: 'The task starts now instead of waiting for its schedule.',
			confirm: 'Run now',
			action: { action: 'runTask', task: t.id },
			done: `Started ${t.name}`,
			after: man.refresh
		});

	let msgOpen = $state(false);
	let msgTarget = $state<JfSession | null>(null);
	let msgHeader = $state('');
	let msgText = $state('');
	const msgValid = $derived(msgText.trim().length > 0 && msgText.length <= 500 && msgHeader.length <= 80);

	function message(x: JfSession) {
		msgTarget = x;
		msgHeader = '';
		msgText = '';
		msgOpen = true;
	}
	async function sendMessage() {
		if (!msgTarget || !msgValid) return;
		const err = await run(
			{ action: 'message', session: msgTarget.id, header: msgHeader.trim() || undefined, text: msgText.trim() },
			`Message sent to ${msgTarget.user}`,
			() => {}
		);
		if (!err) msgOpen = false;
	}
</script>

<div class="page">
	<!-- Server -->
	<section class="card server" aria-label="Server">
		{#if s}
			<div class="srv">
				<div class="srv-text">
					<h2 class="title-medium">{s.server.name}</h2>
					<p class="body-small">{[s.server.version ? `Jellyfin ${s.server.version}` : null, s.server.os].filter(Boolean).join(' · ')}</p>
				</div>
			</div>
			{#if s.server.restartPending || s.server.updateAvailable || s.server.shuttingDown}
				<div class="flags">
					{#if s.server.shuttingDown}<span class="flag warn"><Icon icon={iRestart} size={16} /> Shutting down</span>{/if}
					{#if s.server.restartPending}<span class="flag warn"><Icon icon={iRestart} size={16} /> Restart pending</span>{/if}
					{#if s.server.updateAvailable}<span class="flag"><Icon icon={iUpdate} size={16} /> Update available</span>{/if}
				</div>
			{/if}
			{#if countChips.length}
				<dl class="counts">
					{#each countChips as [n, label] (label)}
						<div><dt>{label}</dt><dd>{fmt.format(n)}</dd></div>
					{/each}
				</dl>
			{/if}
		{:else if sum.state.error}
			<p class="error" role="alert">{sum.state.error}</p>
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading server info" /></div>
		{/if}
	</section>

	<!-- Now playing -->
	<section aria-labelledby="jf-now">
		<h2 class="title-medium" id="jf-now">Now playing{sessions.length ? ` (${sessions.length})` : ''}</h2>
		{#if now.state.data}
			{#if sessions.length}
				<Sessions {sessions} {isAdmin} onstop={stop} onmessage={message} />
			{:else}
				<p class="empty">No one is watching right now.</p>
			{/if}
		{:else if now.state.error}
			<p class="error" role="alert">{now.state.error}</p>
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading sessions" /></div>
		{/if}
	</section>

	<!-- Recently added -->
	{#if s?.latest.length}
		<section aria-labelledby="jf-latest">
			<h2 class="title-medium" id="jf-latest">Recently added</h2>
			<Latest items={s.latest} />
		</section>
	{/if}

	<div class="split">
		<!-- Libraries -->
		<section class="card" aria-labelledby="jf-libs">
			<div class="sec-head">
				<h2 class="title-medium" id="jf-libs">Libraries</h2>
				{#if isAdmin && s?.libraries.length}
					<Button variant="tonal" onclick={scanAll}><Icon icon={iRefresh} /> Scan all</Button>
				{/if}
			</div>
			{#if s}
				{#if s.libraries.length}
					<Libraries libraries={s.libraries} {isAdmin} onscan={scan} />
				{:else}
					<p class="empty">No libraries yet.</p>
				{/if}
			{:else if !sum.state.error}
				<div class="center"><LoadingIndicator aria-label="Loading libraries" /></div>
			{/if}
		</section>

		<!-- Users / tasks / activity -->
		<section class="card" aria-label="Users, tasks and activity">
			{#if m}
				<Manage data={m} {isAdmin} onrun={runTask} />
			{:else if man.state.error}
				<p class="error" role="alert">{man.state.error}</p>
			{:else}
				<div class="center"><LoadingIndicator aria-label="Loading tasks" /></div>
			{/if}
		</section>
	</div>
</div>

<Dialog headline={pending?.headline ?? ''} bind:open={confirmOpen}>
	<p class="dlg">{pending?.body}</p>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !pending} onclick={confirm}>{pending?.confirm ?? 'OK'}</Button>
	{/snippet}
</Dialog>

<Dialog headline="Send a message" bind:open={msgOpen}>
	<p class="dlg">Shows on {msgTarget?.user ?? 'their'}'s screen ({msgTarget?.device || msgTarget?.client || 'device'}) for 10 seconds.</p>
	<div class="fields">
		<TextField label="Title (optional)" bind:value={msgHeader} maxlength={80} autocomplete="off" />
		<TextFieldMultiline label="Message" bind:value={msgText} maxlength={500} required />
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (msgOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !msgValid} onclick={sendMessage}>Send</Button>
	{/snippet}
</Dialog>

<style>
	.page {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 1.25rem;
	}
	section {
		min-width: 0;
	}
	h2 {
		margin: 0 0 0.5rem;
	}
	.card {
		padding: 1rem;
		border-radius: var(--radius-l, 1rem);
		background: var(--m3c-surface-container-low);
	}
	.srv h2 {
		margin: 0;
	}
	.srv p {
		margin: 0.125rem 0 0;
		color: var(--m3c-on-surface-variant);
	}
	.flags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 0.75rem;
	}
	.flag {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		padding: 0.25rem 0.625rem;
		border-radius: 0.5rem;
		font-size: 0.8125rem;
		font-weight: 500;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.flag.warn {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.counts {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 5.5rem), 1fr));
		gap: 0.75rem;
		margin: 1rem 0 0;
	}
	.counts div {
		min-width: 0;
	}
	.counts dt {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.counts dd {
		margin: 0;
		font-size: 1.25rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.split {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 1.25rem;
		align-items: start;
	}
	@media (width >= 64rem) {
		.split {
			grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		}
	}
	.sec-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		margin-bottom: 0.25rem;
	}
	.sec-head h2 {
		margin: 0;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 2rem 0;
	}
	.empty {
		margin: 0;
		color: var(--m3c-on-surface-variant);
	}
	.error {
		margin: 0;
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.dlg {
		margin: 0 0 0.75rem;
		overflow-wrap: anywhere;
	}
	.fields {
		display: grid;
		gap: 0.75rem;
	}
	.fields :global(.m3-container) {
		min-width: 0;
		width: 100%;
	}
</style>
