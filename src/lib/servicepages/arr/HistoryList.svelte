<script lang="ts">
	import { untrack } from 'svelte';
	import { Icon } from 'm3-svelte';
	import iGrab from '@ktibow/iconset-material-symbols/download-rounded';
	import iImported from '@ktibow/iconset-material-symbols/download-done-rounded';
	import iFailed from '@ktibow/iconset-material-symbols/error-rounded';
	import iOther from '@ktibow/iconset-material-symbols/history-rounded';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import State from '$lib/widgets/State.svelte';
	import Pager from './Pager.svelte';
	import { dateTime } from './format';
	import type { HistoryItem, Paged } from './types';

	let { url, onpage }: { url: string; onpage: (p: number) => void } = $props();

	const q = live<Paged<HistoryItem>>(untrack(() => url), 30_000);
	const data = $derived(q.state.data);
	const items = $derived(data?.records ?? []);
	const ICON = { grab: iGrab, ok: iImported, error: iFailed, other: iOther } as const;
	let open = $state<Record<number, boolean>>({});
</script>

<State loading={q.state.loading} error={q.state.error} empty={items.length === 0} emptyText="No history yet">
	<ul class="list">
		{#each items as h (h.id)}
			<li class="row tone-{h.tone}">
				<span class="ic"><Icon icon={ICON[h.tone]} size={20} /></span>
				<div class="body">
					<div class="line1">
						{#if h.href}<a class="title" href={h.href}>{h.title}</a>{:else}<span class="title">{h.title}</span>{/if}
						<time datetime={h.date} title={dateTime(h.date)}>{ago(h.date)}</time>
					</div>
					{#if h.subtitle}<div class="sub">{h.subtitle}</div>{/if}
					<div class="meta">
						<span class="event">{h.eventLabel}</span>
						{#if h.quality}<span>{h.quality}</span>{/if}
						{#if h.detail && h.tone !== 'error'}<span class="detail">{h.detail}</span>{/if}
					</div>
					{#if h.tone === 'error' && h.detail}<p class="err">{h.detail}</p>{/if}
					{#if h.source}
						<button class="src-toggle" aria-expanded={!!open[h.id]} onclick={() => (open[h.id] = !open[h.id])}>
							{open[h.id] ? 'Hide release' : 'Show release'}
						</button>
						{#if open[h.id]}<p class="src">{h.source}</p>{/if}
					{/if}
				</div>
			</li>
		{/each}
	</ul>
	{#if data}
		<Pager page={data.page} pages={data.pages} total={data.total} noun="event" {onpage} />
	{/if}
</State>

<style>
	.list {
		list-style: none;
		margin: 0 0 0.5rem;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.row {
		display: flex;
		gap: 0.75rem;
		padding: 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 2rem;
		height: 2rem;
		border-radius: 50%;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.tone-grab .ic {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.tone-ok .ic {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.tone-error .ic {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.125rem;
	}
	.line1 {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
	}
	.title {
		flex: 1;
		min-width: 0;
		font-weight: 500;
		color: inherit;
		text-decoration: none;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	a.title:hover {
		text-decoration: underline;
	}
	time {
		flex: none;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		gap: 0.125rem 0.75rem;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.event {
		font-weight: 500;
		color: var(--m3c-on-surface);
	}
	.tone-error .event,
	.err {
		color: var(--m3c-error);
	}
	.detail {
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.err {
		margin: 0.125rem 0 0;
		font-size: 0.8125rem;
	}
	.src-toggle {
		justify-self: start;
		padding: 0;
		border: 0;
		background: none;
		color: var(--m3c-primary);
		font: inherit;
		font-size: 0.75rem;
		cursor: pointer;
	}
	.src {
		margin: 0;
		font-size: 0.75rem;
		font-family: ui-monospace, monospace;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
	}
</style>
