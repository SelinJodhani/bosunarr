<script lang="ts" module>
	export type IndexerFilter = 'all' | 'enabled' | 'disabled' | 'failing';
	/** Shared with the page so the summary tiles can open a filtered list. */
	export const indexerView = $state({ filter: 'all' as IndexerFilter });
</script>

<script lang="ts">
	import { Chip, snackbar } from 'm3-svelte';
	import iSearchOff from '@ktibow/iconset-material-symbols/search-off-rounded';
	import { ago } from '$lib/format';
	import Empty from '../shared/Empty.svelte';
	import ConfirmDialog from '../shared/ConfirmDialog.svelte';
	import { protocolLabel } from '../shared/format';
	import type { Indexer } from './types';
	import { implementationLabel, post } from './api';
	import '../shared/chips.css';

	let { api, isAdmin, indexers, refresh }: { api: string; isAdmin: boolean; indexers: Indexer[]; refresh: () => Promise<void> } = $props();

	const FILTERS: Array<{ value: IndexerFilter; label: string; test: (i: Indexer) => boolean }> = [
		{ value: 'all', label: 'All', test: () => true },
		{ value: 'enabled', label: 'Enabled', test: (i) => i.enabled },
		{ value: 'disabled', label: 'Disabled', test: (i) => !i.enabled },
		{ value: 'failing', label: 'Failing', test: (i) => i.enabled && i.failure !== null }
	];
	const counts = $derived(Object.fromEntries(FILTERS.map((f) => [f.value, indexers.filter(f.test).length])) as Record<IndexerFilter, number>);
	const list = $derived(indexers.filter((FILTERS.find((f) => f.value === indexerView.filter) ?? FILTERS[0]).test));

	/* Toggling asks first; the switch shows the real state until Prowlarr confirms. */
	let pending = $state<Indexer | null>(null);
	let confirmOpen = $state(false);
	let busyId = $state<number | null>(null);

	function ask(i: Indexer) {
		pending = i;
		confirmOpen = true;
	}

	async function apply(): Promise<string | null> {
		const i = pending;
		if (!i) return null;
		busyId = i.id;
		const err = await post(`${api}/indexers/${i.id}`, { enable: !i.enabled });
		if (!err) await refresh();
		busyId = null;
		if (!err) snackbar(`${i.name} ${i.enabled ? 'disabled' : 'enabled'}`, {}, true);
		return err;
	}

	const PRIVACY: Record<string, string> = { public: 'Public', private: 'Private', semiPrivate: 'Semi-private' };
	const until = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
</script>

<div class="chip-row scroll" role="toolbar" aria-label="Filter indexers">
	{#each FILTERS as f (f.value)}
		{#if f.value === 'all' || counts[f.value] > 0 || indexerView.filter === f.value}
			<Chip variant="general" selected={indexerView.filter === f.value} onclick={() => (indexerView.filter = f.value)}>{f.label} {counts[f.value]}</Chip>
		{/if}
	{/each}
</div>

{#if list.length === 0}
	{#if indexers.length}
		<Empty icon={iSearchOff} title="No indexers match this filter" />
	{:else}
		<Empty icon={iSearchOff} title="No indexers yet" hint="Add indexers in Prowlarr; they show up here." />
	{/if}
{:else}
	<ul class="list">
		{#each list as i (i.id)}
			{@const impl = implementationLabel(i.implementation)}
			<li class="row" class:off={!i.enabled}>
				<div class="body">
					<span class="name">{i.name}</span>
					<span class="meta">
						<span class="proto p-{i.protocol}">{protocolLabel(i.protocol)}</span>
						{#if i.privacy}<span>{PRIVACY[i.privacy] ?? i.privacy}</span>{/if}
						<span>Priority {i.priority}</span>
						{#if impl}<span>{impl}</span>{/if}
					</span>
					{#if !i.enabled}
						<span class="state">Disabled</span>
					{:else if i.failure}
						<span class="state bad">
							Failing{i.failure.last ? ` · last error ${ago(i.failure.last)}` : ''}{i.failure.disabledTill && +new Date(i.failure.disabledTill) > Date.now()
								? ` · paused until ${until(i.failure.disabledTill)}`
								: ''}
						</span>
					{:else}
						<span class="state ok">Healthy</span>
					{/if}
				</div>
				{#if isAdmin}
					<!-- A plain switch button: it never flips itself; the state only changes once Prowlarr confirms. -->
					<button
						class="switch"
						class:on={i.enabled}
						role="switch"
						aria-checked={i.enabled}
						aria-label={i.name}
						disabled={busyId === i.id}
						onclick={() => ask(i)}
					>
						<span class="handle"></span>
					</button>
				{/if}
			</li>
		{/each}
	</ul>
{/if}

<ConfirmDialog
	bind:open={confirmOpen}
	headline={pending?.enabled ? 'Disable indexer?' : 'Enable indexer?'}
	confirm={pending?.enabled ? 'Disable' : 'Enable'}
	danger={pending?.enabled}
	onconfirm={apply}
>
	<p class="dlg-title">{pending?.name}</p>
	<p class="dlg-sub">
		{pending?.enabled
			? 'Prowlarr stops searching it and stops syncing it to your apps.'
			: 'Prowlarr tests the indexer first; it stays disabled if the test fails.'}
	</p>
</ConfirmDialog>

<style>
		.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.75rem 0.75rem 0.75rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.name {
		font-size: 0.875rem;
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.off .name {
		color: var(--m3c-on-surface-variant);
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.125rem 0.75rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.proto {
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.p-usenet {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.state {
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--m3c-on-surface-variant);
	}
	.state.ok {
		color: var(--m3c-primary);
	}
	.state.bad {
		color: var(--m3c-error);
	}
	/* M3 switch look (52×32 track, 16→24 px handle). */
	.switch {
		all: unset;
		box-sizing: border-box;
		flex: none;
		position: relative;
		width: 3.25rem;
		height: 2rem;
		border-radius: 1rem;
		border: 2px solid var(--m3c-outline);
		background: var(--m3c-surface-container-highest);
		cursor: pointer;
		transition: background-color 150ms, border-color 150ms;
	}
	.switch .handle {
		position: absolute;
		top: 50%;
		left: 0.375rem;
		width: 1rem;
		height: 1rem;
		border-radius: 50%;
		background: var(--m3c-outline);
		transform: translateY(-50%);
		transition: left 150ms, width 150ms, height 150ms, background-color 150ms;
	}
	.switch.on {
		border-color: var(--m3c-primary);
		background: var(--m3c-primary);
	}
	.switch.on .handle {
		left: calc(100% - 1.5rem - 0.125rem);
		width: 1.5rem;
		height: 1.5rem;
		background: var(--m3c-on-primary);
	}
	.switch:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.switch:disabled {
		opacity: 0.38;
		cursor: default;
	}
	.dlg-title {
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.dlg-sub {
		margin: 0;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
</style>
