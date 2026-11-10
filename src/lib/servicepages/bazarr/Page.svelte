<script lang="ts">
	import { Button, Chip, Icon, snackbar } from 'm3-svelte';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import iSearchAll from '@ktibow/iconset-material-symbols/travel-explore-rounded';
	import iReset from '@ktibow/iconset-material-symbols/restart-alt-rounded';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iOk from '@ktibow/iconset-material-symbols/check-circle-rounded';
	import iWait from '@ktibow/iconset-material-symbols/hourglass-rounded';
	import iCc from '@ktibow/iconset-material-symbols/closed-caption-rounded';
	import iLang from '@ktibow/iconset-material-symbols/translate-rounded';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import type { ServicePageProps } from '../types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import ConfirmDialog from '../shared/ConfirmDialog.svelte';
	import { sentence } from '../shared/format';
	import type { SummaryNotice } from '../shared/types';
	import Wanted from './Wanted.svelte';
	import History from './History.svelte';
	import LangTags from './LangTags.svelte';
	import { bazarrAct } from './api';
	import { providerIssue, providerName } from './providers';
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
	const tabs = [
		{ name: 'Wanted', value: 'wanted' },
		{ name: 'History', value: 'history' },
		{ name: 'Providers', value: 'providers' },
		{ name: 'Profiles', value: 'profiles' }
	];

	function setKind(k: BazarrKind) {
		if (kind === k) return;
		kind = k;
		wantedPage = 1;
		historyPage = 1;
	}
	function showWanted(k: BazarrKind) {
		setKind(k);
		tab = 'wanted';
	}

	const started = $derived(o?.startTime ? ago(new Date(o.startTime * 1000).toISOString()) : null);
	const notices = $derived.by((): SummaryNotice[] => {
		if (!o) return [];
		const out: SummaryNotice[] = [];
		for (const [app, version, sig] of [
			['Sonarr', o.sonarrVersion, o.sonarrSignalr],
			['Radarr', o.radarrVersion, o.radarrSignalr]
		] as const) {
			if (version && sig === 'DOWN') out.push({ tone: 'warn', text: `Live sync with ${app} is down, so new ${app === 'Sonarr' ? 'episodes' : 'movies'} reach Bazarr late.` });
		}
		for (const h of o.health) out.push({ tone: 'warn', source: h.object, text: h.issue });
		if (o.errors.length) out.push({ tone: 'warn', text: `Some parts didn't load (${o.errors.map((e) => e.split(':')[0]).join(', ')}). Trying again shortly.` });
		return out;
	});
	const wantedCount = $derived(o ? o.wanted[kind] : 0);

	// Confirm dialogs
	let confirm = $state<'searchAll' | 'reset' | null>(null);
	let confirmOpen = $state(false);
	function ask(what: 'searchAll' | 'reset') {
		confirm = what;
		confirmOpen = true;
	}
	async function run(): Promise<string | null> {
		if (!confirm) return null;
		const r = await bazarrAct(service.id, confirm === 'reset' ? { action: 'resetProviders' } : { action: 'searchAll', kind });
		if (!r.ok) return r.message;
		snackbar(r.message, {}, true);
		ov.refresh();
		return null;
	}
</script>

{#if !o}
	{#if ov.state.error}
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(ov.state.error)} onretry={() => ov.refresh()} />
	{:else}
		<Loading label="Loading Bazarr" />
	{/if}
{:else}
	<Summary
		meta={[o.version ? `Bazarr ${o.version.replace(/^v/, '')}` : 'Bazarr', started && `Started ${started}`]}
		stats={[
			{ value: o.wanted.episodes.toLocaleString(), label: 'Episodes missing', tone: o.wanted.episodes ? 'info' : undefined, onclick: () => showWanted('episodes') },
			{ value: o.wanted.movies.toLocaleString(), label: 'Movies missing', tone: o.wanted.movies ? 'info' : undefined, onclick: () => showWanted('movies') },
			{ value: issues.length, label: 'Provider issues', tone: issues.length ? 'bad' : undefined, onclick: () => (tab = 'providers') }
		]}
		{notices}
		stale={ov.state.error}
	/>

	<ServiceTabs items={tabs} bind:tab>
		{#if tab === 'wanted' || tab === 'history'}
			<div class="toolbar">
				<div class="chips" role="group" aria-label="Media type">
					<Chip variant="general" icon={iTv} selected={kind === 'episodes'} onclick={() => setKind('episodes')}>Episodes</Chip>
					<Chip variant="general" icon={iMovie} selected={kind === 'movies'} onclick={() => setKind('movies')}>Movies</Chip>
				</div>
				{#if tab === 'wanted' && isAdmin && wantedCount > 0}
					<Button variant="tonal" onclick={() => ask('searchAll')}><Icon icon={iSearchAll} /> Search all</Button>
				{/if}
			</div>
			{#if tab === 'wanted'}
				{#key `${kind}:${wantedPage}`}
					<Wanted serviceId={service.id} {kind} page={wantedPage} onpage={(p) => (wantedPage = p)} {isAdmin} onchange={() => ov.refresh()} />
				{/key}
			{:else}
				{#key `${kind}:${historyPage}`}
					<History serviceId={service.id} {kind} page={historyPage} onpage={(p) => (historyPage = p)} />
				{/key}
			{/if}
		{:else if tab === 'providers'}
			{#if o.providers.length === 0}
				<Empty icon={iCc} title="No subtitle providers enabled" hint="Turn providers on in Bazarr's settings." />
			{:else}
				{#if isAdmin && issues.length}
					<div class="toolbar">
						<p class="muted">{issues.length} provider{issues.length === 1 ? ' is' : 's are'} paused. Bazarr retries on its own, or reset them now.</p>
						<Button variant="tonal" onclick={() => ask('reset')}><Icon icon={iReset} /> Reset providers</Button>
					</div>
				{/if}
				<ul class="list">
					{#each o.providers as p (p.name)}
						<li class="row" class:bad={p.issue}>
							<span class="pic">
								{#if p.issue}<Icon icon={iWarning} size={20} />{:else}<Icon icon={iOk} size={20} />{/if}
							</span>
							<span class="pbody">
								<span class="pname">{providerName(p.name)}</span>
								{#if p.issue}
									<span class="reason" title={p.issue}>{providerIssue(p.issue)}</span>
									{#if p.until}<span class="until"><Icon icon={iWait} size={14} /> Retrying {p.until}</span>{/if}
								{:else}
									<span class="good">Working</span>
								{/if}
							</span>
						</li>
					{/each}
				</ul>
			{/if}
		{:else if o.profiles.length === 0}
			<Empty icon={iLang} title="No languages profiles yet" hint="Create one in Bazarr's settings." />
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
	</ServiceTabs>
{/if}

<ConfirmDialog
	bind:open={confirmOpen}
	headline={confirm === 'reset' ? 'Reset providers?' : `Search all wanted ${kind}?`}
	confirm={confirm === 'reset' ? 'Reset' : 'Search'}
	onconfirm={run}
>
	{#if confirm === 'reset'}
		<p>Clears every throttled or blocked provider in Bazarr so they're used again right away.</p>
	{:else}
		<p>Bazarr searches its providers for every {kind === 'episodes' ? 'episode' : 'movie'} missing subtitles ({wantedCount.toLocaleString()}). This runs in the background and can take a while.</p>
	{/if}
</ConfirmDialog>

<style>
	.muted {
		margin: 0;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	.small {
		font-size: 0.8125rem;
	}
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem 1rem;
	}
	.toolbar p {
		flex: 1 1 14rem;
		min-width: 0;
	}
	.chips {
		display: flex;
		gap: 0.5rem;
	}
	.list,
	.profiles {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.list {
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
	}
	.row {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr);
		align-items: start;
		gap: 0.75rem;
		padding: 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
	}
	.pic {
		display: grid;
		color: var(--m3c-primary);
	}
	.row.bad .pic {
		color: var(--m3c-error);
	}
	.pbody {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.125rem;
		min-width: 0;
	}
	.pname {
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.good,
	.until {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.reason {
		font-size: 0.8125rem;
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
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
		align-content: start;
		padding: 0.875rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.pn {
		font-weight: 500;
		overflow-wrap: anywhere;
	}
</style>
