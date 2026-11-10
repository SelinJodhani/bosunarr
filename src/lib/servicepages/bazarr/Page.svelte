<script lang="ts">
	import { Button, Chip, Dialog, Icon, LoadingIndicator, Tabs, snackbar } from 'm3-svelte';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import iSearchAll from '@ktibow/iconset-material-symbols/travel-explore-rounded';
	import iReset from '@ktibow/iconset-material-symbols/restart-alt-rounded';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iOk from '@ktibow/iconset-material-symbols/check-circle-rounded';
	import iWait from '@ktibow/iconset-material-symbols/hourglass-rounded';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import type { ServicePageProps } from '../types';
	import Wanted from './Wanted.svelte';
	import History from './History.svelte';
	import LangTags from './LangTags.svelte';
	import { bazarrAct } from './api';
	import type { BazarrKind, BazarrOverview } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// svelte-ignore state_referenced_locally
	const ov = live<BazarrOverview>(`/api/svc/bazarr/${service.id}/overview`, 30_000);
	const o = $derived(ov.state.data);

	let tab = $state('wanted');
	let kind = $state<BazarrKind>('episodes');
	let wantedPage = $state(1);
	let historyPage = $state(1);

	const issues = $derived(o?.providers.filter((p) => p.issue) ?? []);
	const tabs = $derived([
		{ name: 'Wanted', value: 'wanted' },
		{ name: 'History', value: 'history' },
		{ name: issues.length ? `Providers (${issues.length})` : 'Providers', value: 'providers' },
		{ name: 'Profiles', value: 'profiles' }
	]);

	function setKind(k: BazarrKind) {
		if (kind === k) return;
		kind = k;
		wantedPage = 1;
		historyPage = 1;
	}

	const started = $derived(o?.startTime ? ago(new Date(o.startTime * 1000).toISOString()) : null);
	const syncDown = $derived(
		[o?.sonarrVersion && o.sonarrSignalr === 'DOWN' ? 'Sonarr' : null, o?.radarrVersion && o.radarrSignalr === 'DOWN' ? 'Radarr' : null].filter(Boolean)
	);

	// Confirm dialogs
	let confirm = $state<'searchAll' | 'reset' | null>(null);
	let confirmOpen = $state(false);
	let busy = $state(false);
	function ask(what: 'searchAll' | 'reset') {
		confirm = what;
		confirmOpen = true;
	}
	async function run() {
		if (!confirm) return;
		busy = true;
		const r = await bazarrAct(service.id, confirm === 'reset' ? { action: 'resetProviders' } : { action: 'searchAll', kind });
		busy = false;
		confirmOpen = false;
		snackbar(r.message, {}, true);
		ov.refresh();
	}
</script>

{#if !o}
	{#if ov.state.error}
		<p class="error" role="alert">{ov.state.error}</p>
	{:else}
		<div class="center"><LoadingIndicator aria-label="Loading Bazarr" /></div>
	{/if}
{:else}
	<section class="status" aria-label="Status">
		<div class="head">
			<span class="ver">{o.version ? `Bazarr ${o.version.replace(/^v/, '')}` : 'Bazarr'}</span>
			{#if started}<span class="muted">Started {started}</span>{/if}
		</div>
		<div class="stats">
			<button class="stat" class:hot={o.wanted.episodes > 0} onclick={() => ((tab = 'wanted'), setKind('episodes'))}>
				<span class="num">{o.wanted.episodes}</span>
				<span class="lbl">Episode subs missing</span>
			</button>
			<button class="stat" class:hot={o.wanted.movies > 0} onclick={() => ((tab = 'wanted'), setKind('movies'))}>
				<span class="num">{o.wanted.movies}</span>
				<span class="lbl">Movie subs missing</span>
			</button>
			<button class="stat" class:bad={issues.length > 0} onclick={() => (tab = 'providers')}>
				<span class="num">{issues.length}</span>
				<span class="lbl">Provider issues</span>
			</button>
		</div>
		{#if o.health.length || syncDown.length}
			<ul class="health">
				{#each syncDown as name (name)}
					<li><Icon icon={iWarning} size={18} /><span>{name} live sync is down</span></li>
				{/each}
				{#each o.health as h, i (i)}
					<li><Icon icon={iWarning} size={18} /><span><b>{h.object}</b> {h.issue}</span></li>
				{/each}
			</ul>
		{/if}
		{#if o.errors.length}
			<p class="partial">Some parts didn't load — {o.errors.join('; ')}</p>
		{/if}
	</section>

	<Tabs items={tabs} bind:tab secondary />

	<div class="content" data-pager-top>
		{#if tab === 'wanted' || tab === 'history'}
			<div class="toolbar">
				<div class="chips" role="group" aria-label="Media type">
					<Chip variant="general" icon={iTv} selected={kind === 'episodes'} onclick={() => setKind('episodes')}>Episodes</Chip>
					<Chip variant="general" icon={iMovie} selected={kind === 'movies'} onclick={() => setKind('movies')}>Movies</Chip>
				</div>
				{#if tab === 'wanted' && isAdmin}
					<Button variant="tonal" onclick={() => ask('searchAll')}><Icon icon={iSearchAll} /> Search all</Button>
				{/if}
			</div>
			{#if tab === 'wanted'}
				{#key `${kind}:${wantedPage}`}
					<Wanted serviceId={service.id} {kind} bind:page={wantedPage} {isAdmin} onchange={() => ov.refresh()} />
				{/key}
			{:else}
				{#key `${kind}:${historyPage}`}
					<History serviceId={service.id} {kind} bind:page={historyPage} />
				{/key}
			{/if}
		{:else if tab === 'providers'}
			{#if isAdmin && o.providers.length}
				<div class="toolbar end">
					<Button variant="tonal" onclick={() => ask('reset')}><Icon icon={iReset} /> Reset providers</Button>
				</div>
			{/if}
			{#if o.providers.length === 0}
				<p class="empty">No subtitle providers are enabled in Bazarr.</p>
			{:else}
				<ul class="list">
					{#each o.providers as p (p.name)}
						<li class="row" class:bad={p.issue}>
							<span class="pic">
								{#if p.issue}<Icon icon={iWarning} size={20} />{:else}<Icon icon={iOk} size={20} />{/if}
							</span>
							<span class="pname">{p.name}</span>
							<span class="pstate">
								{#if p.issue}
									<span class="reason">{p.issue}</span>
									{#if p.until}<span class="until"><Icon icon={iWait} size={14} /> {p.until}</span>{/if}
								{:else}
									Good
								{/if}
							</span>
						</li>
					{/each}
				</ul>
			{/if}
		{:else}
			{#if o.profiles.length === 0}
				<p class="empty">No languages profiles yet. Create one in Bazarr's settings.</p>
			{:else}
				<ul class="profiles">
					{#each o.profiles as p (p.id)}
						<li class="profile">
							<div class="pn">{p.name}</div>
							<LangTags langs={p.languages} />
							{#if p.cutoff}<div class="muted small">Cutoff: {p.cutoff}</div>{/if}
						</li>
					{/each}
				</ul>
			{/if}
		{/if}
	</div>
{/if}

<Dialog headline={confirm === 'reset' ? 'Reset providers?' : `Search all wanted ${kind}?`} bind:open={confirmOpen}>
	{#if confirm === 'reset'}
		Clears every throttled or blocked provider in Bazarr so they're used again right away.
	{:else}
		Bazarr will search its providers for every {kind === 'episodes' ? 'episode' : 'movie'} missing subtitles. This runs in the background and can take a while.
	{/if}
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="tonal" disabled={busy} onclick={run}>{confirm === 'reset' ? 'Reset' : 'Search'}</Button>
	{/snippet}
</Dialog>

<style>
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error,
	.partial {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0;
	}
	.muted {
		color: var(--m3c-on-surface-variant);
	}
	.small {
		font-size: 0.8125rem;
	}
	.status {
		display: grid;
		gap: 0.75rem;
		padding: 1rem;
		margin-bottom: 0.75rem;
		border-radius: var(--radius-l, 1rem);
		background: var(--m3c-surface-container-low);
	}
	.head {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		justify-content: space-between;
		gap: 0.25rem 1rem;
	}
	.ver {
		font-weight: 500;
	}
	.head .muted {
		font-size: 0.875rem;
	}
	.stats {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0.5rem;
	}
	.stat {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		display: grid;
		gap: 0.125rem;
		padding: 0.625rem 0.75rem;
		border-radius: var(--radius-m, 0.75rem);
		background: var(--m3c-surface-container-high);
		min-width: 0;
	}
	.stat:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.stat .num {
		font-size: 1.5rem;
		line-height: 2rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.stat .lbl {
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.stat.hot .num {
		color: var(--m3c-primary);
	}
	.stat.bad {
		background: var(--m3c-error-container);
	}
	.stat.bad .num,
	.stat.bad .lbl {
		color: var(--m3c-on-error-container);
	}
	.health {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.375rem;
		font-size: 0.875rem;
	}
	.health li {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr);
		gap: 0.5rem;
		align-items: start;
		color: var(--m3c-on-surface);
		overflow-wrap: anywhere;
	}
	.health li :global(svg) {
		color: var(--m3c-error);
		margin-top: 0.0625rem;
	}
	.content {
		padding-top: 1rem;
	}
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	.toolbar.end {
		justify-content: flex-end;
	}
	.chips {
		display: flex;
		gap: 0.5rem;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.list,
	.profiles {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.25rem;
	}
	.row {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.75rem;
		padding: 0.75rem;
		border-radius: var(--radius-m, 0.75rem);
		background: var(--m3c-surface-container-low);
	}
	.pic {
		display: grid;
		color: var(--m3c-primary);
	}
	.row.bad .pic {
		color: var(--m3c-error);
	}
	.pname {
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pstate {
		display: grid;
		justify-items: end;
		gap: 0.125rem;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		text-align: right;
		max-width: 12rem;
	}
	.reason {
		color: var(--m3c-error);
		overflow-wrap: anywhere;
	}
	.until {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
	}
	.profiles {
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 18rem), 1fr));
		gap: 0.5rem;
	}
	.profile {
		display: grid;
		gap: 0.5rem;
		align-content: start;
		padding: 0.875rem 1rem;
		border-radius: var(--radius-m, 0.75rem);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.pn {
		font-weight: 500;
	}
</style>
