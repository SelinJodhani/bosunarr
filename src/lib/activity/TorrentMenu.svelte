<script lang="ts">
	// The ⋮ menu at the end of the torrent toolbar: Add, Resume all, Pause all, and (on Activity) links
	// to each download client's page. Used by Activity and the qBittorrent service screen.
	import type { Snippet } from 'svelte';
	import { goto } from '$app/navigation';
	import { Button, Icon, LoadingIndicator, Menu, MenuItem, snackbar } from 'm3-svelte';
	import iMore from '@ktibow/iconset-material-symbols/more-vert';
	import { iAdd, iDisk, iPause, iResume } from '$lib/icons';
	import { act } from './live.svelte';
	import { closeOnBack } from './closeOnBack.svelte';
	import { isStopped } from './format';
	import type { ClientSummary, Torrent } from './types';

	let {
		torrents,
		clients,
		isAdmin,
		onadd,
		links = false,
		extra
	}: {
		/** Torrents the bulk actions apply to (decides whether Resume / Pause all are enabled). */
		torrents: Torrent[];
		/** Clients the actions go to; offline ones are skipped. */
		clients: ClientSummary[];
		isAdmin: boolean;
		onadd: () => void;
		/** List each client's service page at the bottom. */
		links?: boolean;
		/** More items, rendered after the bulk actions (call `close` when one is picked). */
		extra?: Snippet<[() => void]>;
	} = $props();

	let open = $state(false);
	let root = $state<HTMLElement>();
	const close = () => (open = false);
	closeOnBack(() => open, close);

	$effect(() => {
		if (!open) return;
		const onDown = (e: PointerEvent) => root && !root.contains(e.target as Node) && close();
		const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close();
		document.addEventListener('pointerdown', onDown);
		document.addEventListener('keydown', onKey);
		return () => {
			document.removeEventListener('pointerdown', onDown);
			document.removeEventListener('keydown', onKey);
		};
	});

	const online = $derived(clients.filter((c) => !c.error));
	const canPauseAll = $derived(torrents.some((t) => !isStopped(t.state)));
	const canResumeAll = $derived(torrents.some((t) => isStopped(t.state) || t.group === 'errored'));

	let bulk = $state<'pauseAll' | 'resumeAll' | null>(null);
	async function all(action: 'pauseAll' | 'resumeAll') {
		close();
		if (bulk || !online.length) return;
		bulk = action;
		const err = await act({ action, ids: online.map((c) => `${c.id}:all`) });
		bulk = null;
		snackbar(err ?? (action === 'pauseAll' ? 'Paused all torrents' : 'Resumed all torrents'), {}, true);
	}
</script>

<div class="more" bind:this={root}>
	<Button
		variant="tonal"
		iconType="full"
		aria-label={bulk ? (bulk === 'pauseAll' ? 'Pausing all torrents' : 'Resuming all torrents') : 'More actions'}
		aria-haspopup="menu"
		aria-expanded={open}
		disabled={!!bulk}
		onclick={() => (open = !open)}
	>
		{#if bulk}<LoadingIndicator size={24} aria-label="Working" />{:else}<Icon icon={iMore} />{/if}
	</Button>
	{#if open}
		<div class="pop" role="menu" aria-label="Torrent actions">
			<Menu>
				{#if isAdmin}
					<MenuItem
						icon={iAdd}
						disabled={!online.length}
						onclick={() => {
							close();
							onadd();
						}}>Add torrent</MenuItem
					>
					<MenuItem icon={iResume} disabled={!canResumeAll || !online.length} onclick={() => all('resumeAll')}>Resume all</MenuItem>
					<MenuItem icon={iPause} disabled={!canPauseAll || !online.length} onclick={() => all('pauseAll')}>Pause all</MenuItem>
				{/if}
				{@render extra?.(close)}
				{#if links && clients.length}
					{#if isAdmin || extra}<hr />{/if}
					{#each clients as c (c.id)}
						<MenuItem
							icon={iDisk}
							onclick={() => {
								close();
								goto(`/services/${c.id}`);
							}}>{c.name}{c.error ? ' · offline' : ''}</MenuItem
						>
					{/each}
				{/if}
			</Menu>
		</div>
	{/if}
</div>

<style>
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
	.pop :global(hr) {
		border: none;
		border-top: 1px solid var(--m3c-outline-variant);
		margin: 0.5rem 0;
	}
</style>
