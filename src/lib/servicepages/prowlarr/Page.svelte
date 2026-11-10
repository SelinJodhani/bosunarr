<script lang="ts" module>
	// Survives leaving and returning to the page.
	let tab = $state('search');
</script>

<script lang="ts">
	import { Icon, LoadingIndicator, Tabs } from 'm3-svelte';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iError from '@ktibow/iconset-material-symbols/error-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import iOpen from '@ktibow/iconset-material-symbols/open-in-new-rounded';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '../types';
	import type { Overview } from './types';
	import { base } from './api';
	import SearchTab from './SearchTab.svelte';
	import IndexersTab from './IndexersTab.svelte';
	import StatsTab from './StatsTab.svelte';
	import HistoryTab from './HistoryTab.svelte';

	let { service, isAdmin }: ServicePageProps = $props();

	// The page is re-created per service ({#key} in /services/[id]), so reading the id once is fine.
	// svelte-ignore state_referenced_locally
	const api = base(service.id);
	const ov = live<Overview>(`${api}/overview`, 30_000);
	const d = $derived(ov.state.data);
	const enabled = $derived(d?.indexers.filter((i) => i.enabled) ?? []);
	const failing = $derived(enabled.filter((i) => i.failure).length);

	const items = $derived([
		{ name: 'Search', value: 'search' },
		{ name: `Indexers${d ? ` (${d.indexers.length})` : ''}`, value: 'indexers' },
		{ name: 'Stats', value: 'stats' },
		{ name: 'History', value: 'history' }
	]);
	const ICON = { error: iError, warning: iWarning, notice: iInfo };
</script>

{#if d}
	<section class="status" aria-label="Status">
		<p class="summary body-medium">
			{#if d.version}<span>v{d.version}</span>{/if}
			<span>{enabled.length} of {d.indexers.length} indexers enabled</span>
			{#if failing}<span class="bad">{failing} failing</span>{/if}
		</p>
		{#if d.health.length}
			<ul class="health">
				{#each d.health as h, i (i)}
					<li class="h-{h.type}">
						<Icon icon={ICON[h.type]} size={20} />
						<span class="msg">{h.message}</span>
						{#if h.wikiUrl}
							<a href={h.wikiUrl} target="_blank" rel="noopener noreferrer" aria-label="More about this issue"><Icon icon={iOpen} size={18} /></a>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	</section>
{:else if ov.state.error}
	<p class="error" role="alert">{ov.state.error}</p>
{:else}
	<div class="center"><LoadingIndicator aria-label="Loading Prowlarr" /></div>
{/if}

<Tabs {items} bind:tab secondary />

<div class="content">
	{#if tab === 'search'}
		<SearchTab {api} {isAdmin} indexers={d?.indexers ?? []} />
	{:else if tab === 'indexers'}
		{#if d}
			<IndexersTab {api} {isAdmin} indexers={d.indexers} refresh={ov.refresh} />
		{:else if !ov.state.error}
			<div class="center"><LoadingIndicator aria-label="Loading indexers" /></div>
		{/if}
	{:else if tab === 'stats'}
		<StatsTab {api} />
	{:else}
		<HistoryTab {api} />
	{/if}
</div>

<style>
	.status {
		display: grid;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	.summary {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1rem;
		margin: 0;
		color: var(--m3c-on-surface-variant);
	}
	.bad {
		color: var(--m3c-error);
		font-weight: 600;
	}
	.health {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.health li {
		display: flex;
		align-items: center;
		gap: 0.625rem;
		padding: 0.625rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		font-size: 0.875rem;
	}
	.health .msg {
		flex: 1;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.health a {
		display: inline-flex;
		color: var(--m3c-on-surface-variant);
		padding: 0.25rem;
	}
	.h-warning {
		color: var(--m3c-on-surface);
	}
	.h-warning > :global(svg) {
		color: var(--m3c-tertiary);
	}
	.h-error {
		background: var(--m3c-error-container) !important;
		color: var(--m3c-on-error-container);
	}
	.h-notice > :global(svg) {
		color: var(--m3c-primary);
	}
	.content {
		padding-top: 0.75rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 2rem 0;
	}
	.error {
		color: var(--m3c-error);
	}
</style>
