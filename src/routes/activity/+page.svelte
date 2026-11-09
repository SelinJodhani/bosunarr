<script lang="ts" module>
	let tab = $state('queue');
</script>

<script lang="ts">
	import { page } from '$app/state';
	import { Button, Icon, LoadingIndicator, Tabs, snackbar } from 'm3-svelte';
	import { speed } from '$lib/format';
	import { act, liveActivity } from '$lib/activity/live.svelte';
	import QueueList from '$lib/activity/QueueList.svelte';
	import TorrentList from '$lib/activity/TorrentList.svelte';
	import AddTorrent from '$lib/activity/AddTorrent.svelte';
	import { iAdd, iDown, iPause, iResume, iUp } from '$lib/icons';

	const live = liveActivity();
	const d = $derived(live.state.data);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const hasClients = $derived((d?.clients.length ?? 0) > 0);
	let addOpen = $state(false);

	const items = $derived([
		{ name: `Queue${d ? ` (${d.queue.length})` : ''}`, value: 'queue' },
		{ name: `Torrents${d ? ` (${d.torrents.length})` : ''}`, value: 'torrents' }
	]);

	const clientName = (id: number) => d?.clients.find((c) => c.id === id)?.name ?? '';

	async function all(action: 'pauseAll' | 'resumeAll') {
		if (!d) return;
		const err = await act({ action, ids: d.clients.filter((c) => !c.error).map((c) => `${c.id}:all`) });
		snackbar(err ?? (action === 'pauseAll' ? 'Paused all torrents' : 'Resumed all torrents'), {}, true);
	}
</script>

<svelte:head><title>Activity · Bosunarr</title></svelte:head>

<header>
	<div class="head">
		<h1 class="title-large">Activity</h1>
		<span class="live" class:on={live.state.connected} title={live.state.connected ? 'Live' : 'Reconnecting'}></span>
	</div>
	{#if d && hasClients}
		<div class="speeds" aria-label="Total speed">
			<span><Icon icon={iDown} size={18} />{speed(d.speeds.down)}</span>
			<span><Icon icon={iUp} size={18} />{speed(d.speeds.up)}</span>
		</div>
	{/if}
</header>

<Tabs {items} bind:tab secondary />

<div class="content">
	{#if !d}
		{#if live.state.error}
			<p class="error" role="alert">{live.state.error}</p>
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading activity" /></div>
		{/if}
	{:else}
		{#if d.errors.length}
			<p class="partial">Couldn't reach {d.errors.map((e) => e.service).join(', ')}: {d.errors[0].error}</p>
		{/if}

		{#if tab === 'queue'}
			{#if d.queue.length}
				<QueueList items={d.queue} {isAdmin} />
			{:else}
				<p class="empty">Nothing in the Sonarr or Radarr queues.</p>
			{/if}
		{:else if !hasClients}
			<p class="empty">Add a qBittorrent service in Settings to manage torrents here.</p>
		{:else}
			{#if isAdmin}
				<div class="bulk">
					<Button variant="filled" onclick={() => (addOpen = true)}><Icon icon={iAdd} /> Add</Button>
					<Button variant="tonal" aria-label="Resume all" onclick={() => all('resumeAll')}><Icon icon={iResume} /><span class="lbl">Resume all</span></Button>
					<Button variant="tonal" aria-label="Pause all" onclick={() => all('pauseAll')}><Icon icon={iPause} /><span class="lbl">Pause all</span></Button>
				</div>
			{/if}
			<TorrentList torrents={d.torrents} {isAdmin} showClient={clientName} />
		{/if}
	{/if}
</div>

{#if d}<AddTorrent bind:open={addOpen} clients={d.clients.filter((c) => !c.error)} />{/if}

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		margin-bottom: 0.5rem;
	}
	.head {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.live {
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: var(--m3c-outline);
	}
	.live.on {
		background: var(--m3c-primary);
		box-shadow: 0 0 0 0.25rem color-mix(in oklab, var(--m3c-primary) 25%, transparent);
	}
	.speeds {
		display: flex;
		gap: 1rem;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
	}
	.speeds span {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
	}
	.content {
		padding-top: 1rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error,
	.partial {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0 0 0.75rem;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.bulk {
		display: flex;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	@media (width < 34rem) {
		.lbl {
			display: none;
		}
	}
</style>
