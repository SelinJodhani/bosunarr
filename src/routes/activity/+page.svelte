<script lang="ts" module>
	let tab = $state('queue');
</script>

<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Button, Icon, LoadingIndicator, Menu, MenuItem, Tabs, snackbar } from 'm3-svelte';
	import iMore from '@ktibow/iconset-material-symbols/more-vert';
	import iOffline from '@ktibow/iconset-material-symbols/sync-problem-rounded';
	import iDone from '@ktibow/iconset-material-symbols/download-done-rounded';
	import { speed } from '$lib/format';
	import { act, liveActivity } from '$lib/activity/live.svelte';
	import QueueList from '$lib/activity/QueueList.svelte';
	import TorrentList from '$lib/activity/TorrentList.svelte';
	import AddTorrent from '$lib/activity/AddTorrent.svelte';
	import Empty from '$lib/activity/Empty.svelte';
	import { iAdd, iDisk, iDown, iError, iPause, iResume, iServices, iUp } from '$lib/icons';

	const live = liveActivity();
	const d = $derived(live.state.data);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));
	const hasClients = $derived((d?.clients.length ?? 0) > 0);
	const onlineClients = $derived(d?.clients.filter((c) => !c.error) ?? []);
	let addOpen = $state(false);

	const items = $derived([
		{ name: `Queue${d ? ` (${d.queue.length})` : ''}`, value: 'queue' },
		{ name: `Torrents${d ? ` (${d.torrents.length})` : ''}`, value: 'torrents' }
	]);

	const clientName = (id: number) => d?.clients.find((c) => c.id === id)?.name ?? '';

	/* "Reconnecting" only after the stream has been down for a moment — not on every page open. */
	let stale = $state(false);
	$effect(() => {
		if (live.state.connected) {
			stale = false;
			return;
		}
		const timer = setTimeout(() => (stale = true), 4_000);
		return () => clearTimeout(timer);
	});

	/* Overflow menu (Add, Resume all, Pause all, client pages). */
	let menuOpen = $state(false);
	let menuRoot = $state<HTMLElement>();
	$effect(() => {
		if (!menuOpen) return;
		const onDown = (e: PointerEvent) => menuRoot && !menuRoot.contains(e.target as Node) && (menuOpen = false);
		const onKey = (e: KeyboardEvent) => e.key === 'Escape' && (menuOpen = false);
		document.addEventListener('pointerdown', onDown);
		document.addEventListener('keydown', onKey);
		return () => {
			document.removeEventListener('pointerdown', onDown);
			document.removeEventListener('keydown', onKey);
		};
	});

	let bulk = $state<'pauseAll' | 'resumeAll' | null>(null);
	const canPauseAll = $derived(Boolean(d?.torrents.some((t) => !/^(paused|stopped)/.test(t.state))));
	const canResumeAll = $derived(Boolean(d?.torrents.some((t) => /^(paused|stopped)/.test(t.state) || t.group === 'errored')));

	async function all(action: 'pauseAll' | 'resumeAll') {
		menuOpen = false;
		if (!d || bulk) return;
		bulk = action;
		const err = await act({ action, ids: onlineClients.map((c) => `${c.id}:all`) });
		bulk = null;
		snackbar(err ?? (action === 'pauseAll' ? 'Paused all torrents' : 'Resumed all torrents'), {}, true);
	}
	function openAdd() {
		menuOpen = false;
		addOpen = true;
	}
</script>

<svelte:head><title>Activity · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Activity</h1>
	{#if d && stale}
		<span class="status" role="status"><Icon icon={iOffline} size={18} />Reconnecting…</span>
	{:else if d && hasClients}
		<div class="speeds" aria-label="Total speed">
			<span title="Download speed"><Icon icon={iDown} size={18} />{speed(d.speeds.down)}</span>
			<span title="Upload speed"><Icon icon={iUp} size={18} />{speed(d.speeds.up)}</span>
		</div>
	{/if}
</header>

<Tabs {items} bind:tab secondary />

<div class="content">
	{#if !d}
		{#if live.state.error}
			<Empty icon={iError} title="Couldn't load activity" hint={live.state.error} />
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading activity" /></div>
		{/if}
	{:else}
		{#if d.errors.length}
			<p class="partial" role="alert">Couldn't reach {d.errors.map((e) => e.service).join(', ')}: {d.errors[0].error}</p>
		{/if}

		{#if tab === 'queue'}
			{#if d.queue.length}
				<QueueList items={d.queue} {isAdmin} />
			{:else}
				<Empty icon={iDone} title="Nothing downloading" hint="The Sonarr and Radarr queues are empty." />
			{/if}
		{:else if !hasClients}
			<Empty
				icon={iServices}
				title="No download client"
				hint="Add a qBittorrent service in Settings to manage torrents here."
				action={isAdmin ? { label: 'Open Settings', onclick: () => goto('/settings') } : undefined}
			/>
		{:else}
			<TorrentList torrents={d.torrents} {isAdmin} showClient={clientName} onAdd={onlineClients.length ? openAdd : undefined}>
				{#snippet toolbar()}
					<div class="more" bind:this={menuRoot}>
						<Button
							variant="tonal"
							iconType="full"
							aria-label={bulk ? (bulk === 'pauseAll' ? 'Pausing all torrents' : 'Resuming all torrents') : 'More actions'}
							aria-haspopup="menu"
							aria-expanded={menuOpen}
							disabled={!!bulk}
							onclick={() => (menuOpen = !menuOpen)}
						>
							{#if bulk}<LoadingIndicator size={24} aria-label="Working" />{:else}<Icon icon={iMore} />{/if}
						</Button>
						{#if menuOpen}
							<div class="pop" role="menu" aria-label="Torrent actions">
								<Menu>
									{#if isAdmin}
										<MenuItem icon={iAdd} disabled={!onlineClients.length} onclick={openAdd}>Add torrent</MenuItem>
										<MenuItem icon={iResume} disabled={!canResumeAll || !onlineClients.length} onclick={() => all('resumeAll')}>Resume all</MenuItem>
										<MenuItem icon={iPause} disabled={!canPauseAll || !onlineClients.length} onclick={() => all('pauseAll')}>Pause all</MenuItem>
										<hr />
									{/if}
									{#each d.clients as c (c.id)}
										<MenuItem
											icon={iDisk}
											onclick={() => {
												menuOpen = false;
												goto(`/services/${c.id}`);
											}}>{c.name}{c.error ? ' · offline' : ''}</MenuItem
										>
									{/each}
								</Menu>
							</div>
						{/if}
					</div>
				{/snippet}
			</TorrentList>
		{/if}
	{/if}
</div>

{#if d}<AddTorrent bind:open={addOpen} clients={onlineClients} />{/if}

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 2.5rem;
		margin-bottom: 0.75rem;
	}
	.speeds {
		display: flex;
		gap: 0.75rem;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		font-size: 0.875rem;
		white-space: nowrap;
	}
	/* Fixed width per figure so "0 B/s" → "12.4 MB/s" never shifts the header. */
	.speeds span {
		display: inline-flex;
		align-items: center;
		justify-content: flex-end;
		gap: 0.125rem;
		min-width: 5.75rem;
	}
	.status {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
	.content {
		padding-top: 1rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.partial {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0 0 0.75rem;
	}
	.more {
		position: relative;
		flex: none;
	}
	.pop {
		position: absolute;
		top: calc(100% + 0.25rem);
		right: 0;
		z-index: 20;
		min-width: 13rem;
	}
	.pop hr {
		border: none;
		border-top: 1px solid var(--m3c-outline-variant);
		margin: 0.5rem 0;
	}
</style>
