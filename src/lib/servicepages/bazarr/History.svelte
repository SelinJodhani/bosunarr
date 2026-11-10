<script lang="ts">
	import type { IconifyIcon } from '@iconify/types';
	import { Icon } from 'm3-svelte';
	import iDownloaded from '@ktibow/iconset-material-symbols/download-done-rounded';
	import iDeleted from '@ktibow/iconset-material-symbols/delete-rounded';
	import iManual from '@ktibow/iconset-material-symbols/back-hand-rounded';
	import iUpgraded from '@ktibow/iconset-material-symbols/upgrade-rounded';
	import iUploaded from '@ktibow/iconset-material-symbols/upload-rounded';
	import iSynced from '@ktibow/iconset-material-symbols/sync-rounded';
	import iTranslated from '@ktibow/iconset-material-symbols/translate-rounded';
	import iExtracted from '@ktibow/iconset-material-symbols/unarchive-rounded';
	import iOther from '@ktibow/iconset-material-symbols/closed-caption-rounded';
	import { live } from '$lib/live.svelte';
	import State from '../shared/State.svelte';
	import Pager from '../shared/Pager.svelte';
	import LangTags from './LangTags.svelte';
	import { providerName } from './providers';
	import type { BazarrKind, HistoryAction, HistoryPage } from './types';

	let { serviceId, kind, page, onpage }: { serviceId: number; kind: BazarrKind; page: number; onpage: (p: number) => void } = $props();

	// Re-created by the parent when kind/page change.
	// svelte-ignore state_referenced_locally
	const data = live<HistoryPage>(`/api/svc/bazarr/${serviceId}/history?kind=${kind}&page=${page}`, 60_000);
	const d = $derived(data.state.data);

	const ACTIONS: Record<HistoryAction, { icon: IconifyIcon; label: string }> = {
		downloaded: { icon: iDownloaded, label: 'Downloaded' },
		upgraded: { icon: iUpgraded, label: 'Upgraded' },
		manual: { icon: iManual, label: 'Manual' },
		uploaded: { icon: iUploaded, label: 'Uploaded' },
		deleted: { icon: iDeleted, label: 'Deleted' },
		synced: { icon: iSynced, label: 'Synced' },
		translated: { icon: iTranslated, label: 'Translated' },
		extracted: { icon: iExtracted, label: 'Extracted' },
		other: { icon: iOther, label: 'Event' }
	};
</script>

<State
	loading={data.state.loading}
	error={data.state.error}
	empty={!d || d.items.length === 0}
	icon={iOther}
	title="No subtitle history yet"
	hint="Downloaded, upgraded and deleted subtitles show up here."
	onretry={() => data.refresh()}
>
	{#if d}
			<ul class="list">
				{#each d.items as h (h.key)}
					{@const a = ACTIONS[h.action]}
					<li class="row">
						<span class="ic ic-{h.action}" title={a.label}><Icon icon={a.icon} size={20} /></span>
						<div class="body">
							<div class="line1">
								<span class="title">{h.title}</span>
								{#if h.when}<span class="when" title={h.at ?? undefined}>{h.when}</span>{/if}
							</div>
							{#if h.subtitle}<div class="sub">{h.subtitle}</div>{/if}
							<div class="meta">
								<span class="act">{a.label}</span>
								{#if h.language}<LangTags langs={[h.language]} />{/if}
								{#if h.provider}<span>{providerName(h.provider)}</span>{/if}
								{#if h.score}<span class="score">{h.score}</span>{/if}
								{#if h.upgradable}<span class="upg">Upgradable</span>{/if}
							</div>
						</div>
					</li>
				{/each}
			</ul>
		<Pager {page} pages={Math.max(1, Math.ceil(d.total / d.pageSize))} total={d.total} noun="event" {onpage} />
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
		grid-template-columns: auto minmax(0, 1fr);
		gap: 0.75rem;
		align-items: start;
		padding: 0.625rem 0.75rem;
		border-radius: var(--radius-m, 0.75rem);
		background: var(--m3c-surface-container-low);
	}
	.ic {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 50%;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.ic-downloaded,
	.ic-upgraded {
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.ic-deleted {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.body {
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.line1 {
		display: flex;
		gap: 0.5rem;
		align-items: baseline;
		min-width: 0;
	}
	.title {
		flex: 1;
		min-width: 0;
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.when {
		flex: none;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.sub {
		color: var(--m3c-on-surface-variant);
		font-size: 0.8125rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.25rem 0.625rem;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		min-width: 0;
	}
	.meta > :global(*) {
		min-width: 0;
	}
	.act {
		font-weight: 500;
		color: var(--m3c-on-surface);
	}
	.score {
		font-variant-numeric: tabular-nums;
	}
	.upg {
		color: var(--m3c-tertiary);
	}
</style>
