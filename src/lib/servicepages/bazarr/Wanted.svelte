<script lang="ts">
	import { Button, Icon, LoadingIndicator, snackbar } from 'm3-svelte';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import { live } from '$lib/live.svelte';
	import iDone from '@ktibow/iconset-material-symbols/task-alt-rounded';
	import iTv from '@ktibow/iconset-material-symbols/tv-rounded';
	import iMovie from '@ktibow/iconset-material-symbols/movie-rounded';
	import Art from '../shared/Art.svelte';
	import State from '../shared/State.svelte';
	import Pager from '../shared/Pager.svelte';
	import LangTags from './LangTags.svelte';
	import { bazarrAct } from './api';
	import type { BazarrKind, WantedItem, WantedPage } from './types';

	let {
		serviceId,
		kind,
		page,
		onpage,
		isAdmin,
		onchange
	}: { serviceId: number; kind: BazarrKind; page: number; onpage: (p: number) => void; isAdmin: boolean; onchange?: () => void } = $props();

	// The parent re-creates this component when kind/page change, so the URL is fixed per instance.
	// svelte-ignore state_referenced_locally
	const data = live<WantedPage>(`/api/svc/bazarr/${serviceId}/wanted?kind=${kind}&page=${page}`, 30_000);
	const d = $derived(data.state.data);
	let busy = $state<Record<string, boolean>>({});

	async function search(it: WantedItem) {
		busy[it.key] = true;
		const r = await bazarrAct(serviceId, { action: 'search', kind: it.kind, id: it.id });
		snackbar(r.message, {}, true);
		if (!r.ok) {
			busy[it.key] = false;
			return;
		}
		// The search runs in the background on the server; check back once it has had time to finish.
		setTimeout(async () => {
			await data.refresh();
			busy[it.key] = false;
			onchange?.();
		}, SETTLE_MS);
	}
	const SETTLE_MS = 8_000;
</script>

<State
	loading={data.state.loading}
	error={data.state.error}
	empty={!d || d.items.length === 0}
	icon={iDone}
	title={d?.total ? 'Nothing on this page' : kind === 'episodes' ? 'No episodes are missing subtitles' : 'No movies are missing subtitles'}
	onretry={() => data.refresh()}
>
	{#if d}
			<ul class="list">
				{#each d.items as it (it.key)}
					<li class="row">
						<a class="art" href={it.href ?? undefined} aria-label={it.href ? `Open ${it.title}` : undefined} tabindex={it.href ? undefined : -1}>
							<Art src={it.poster} width={44} icon={kind === 'episodes' ? iTv : iMovie} />
						</a>
						<div class="body">
							<div class="title">{it.title}</div>
							{#if it.subtitle}<div class="sub">{it.subtitle}</div>{/if}
							<LangTags langs={it.missing} />
						</div>
						{#if isAdmin}
							<div class="act">
								{#if busy[it.key]}
									<LoadingIndicator size={32} aria-label={`Searching subtitles for ${it.title}`} />
								{:else}
									<Button variant="text" iconType="full" aria-label={`Search subtitles for ${it.title}${it.subtitle ? ' ' + it.subtitle : ''}`} title="Search subtitles" onclick={() => search(it)}>
										<Icon icon={iSearch} />
									</Button>
								{/if}
							</div>
						{/if}
					</li>
				{/each}
			</ul>
		<Pager {page} pages={Math.max(1, Math.ceil(d.total / d.pageSize))} total={d.total} noun={kind === 'episodes' ? 'episode' : 'movie'} {onpage} />
	{/if}
</State>

<style>
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.25rem;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 26rem), 1fr));
	}
	.row {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		gap: 0.75rem;
		align-items: center;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m, 0.75rem);
		background: var(--m3c-surface-container-low);
	}
	.art {
		display: block;
		border-radius: 0.5rem;
	}
	.body {
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.title {
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sub {
		color: var(--m3c-on-surface-variant);
		font-size: 0.8125rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.act {
		display: grid;
		place-items: center;
		min-width: 2.5rem;
	}
</style>
