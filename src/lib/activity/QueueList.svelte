<script lang="ts">
	import { Button, Dialog, Icon, LinearProgress, snackbar, Checkbox } from 'm3-svelte';
	import { bytes } from '$lib/format';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iDelete, iWarning, iError } from '$lib/icons';
	import { act } from './live.svelte';
	import type { QueueEntry } from './types';
	import { arrTimeleft as timeleft, queueState } from './format';

	let { items, isAdmin }: { items: QueueEntry[]; isAdmin: boolean } = $props();

	let target = $state<QueueEntry | null>(null);
	let dialogOpen = $state(false);
	let removeFromClient = $state(true);
	let blocklist = $state(false);
	let busy = $state(false);
	let expanded = $state<Record<string, boolean>>({});

	function ask(q: QueueEntry) {
		target = q;
		removeFromClient = true;
		blocklist = q.health === 'error';
		dialogOpen = true;
	}

	async function remove() {
		if (!target) return;
		busy = true;
		const err = await act({ action: 'removeQueue', ids: [target.id], removeFromClient, blocklist });
		busy = false;
		dialogOpen = false;
		snackbar(err ?? `Removed ${target.title}${blocklist ? ' and blocklisted the release' : ''}`, {}, true);
	}
</script>

<ul class="list">
	{#each items as q (q.id)}
		<li class="row" class:warn={q.health !== 'ok'}>
			<a class="art" href={q.href ?? undefined} aria-label={q.href ? `Open ${q.title}` : undefined}>
				<Poster src={q.poster} width={44} alt="" />
			</a>
			<div class="body">
				<div class="line1">
					<span class="title">{q.title}</span>
					{#if isAdmin}
						<Button variant="text" iconType="full" aria-label={`Remove ${q.title} from queue`} onclick={() => ask(q)}><Icon icon={iDelete} /></Button>
					{/if}
				</div>
				{#if q.subtitle}<div class="sub">{q.subtitle}</div>{/if}
				<div class="bar"><LinearProgress percent={q.progress * 100} aria-label={`${q.title} progress`} /></div>
				<div class="meta">
					<span class="state state-{q.health}">
						{#if q.health === 'error'}<Icon icon={iError} size={16} />{:else if q.health === 'warning'}<Icon icon={iWarning} size={16} />{/if}
						{queueState(q.state)}
					</span>
					<span>{Math.round(q.progress * 100)}% of {bytes(q.size)}</span>
					{#if q.state === 'downloading' && timeleft(q.timeleft)}<span>{timeleft(q.timeleft)} left</span>{/if}
				</div>
				<div class="meta sub2">
					{#if q.quality}<span>{q.quality}</span>{/if}
					<span class="svc">{q.service}</span>
				</div>
				{#if q.messages.length}
					<button class="msgs-toggle" onclick={() => (expanded[q.id] = !expanded[q.id])} aria-expanded={!!expanded[q.id]}>
						{expanded[q.id] ? 'Hide details' : q.messages[0]}
					</button>
					{#if expanded[q.id]}
						<ul class="msgs">
							{#each q.messages as m, i (i)}<li>{m}</li>{/each}
						</ul>
					{/if}
				{/if}
			</div>
		</li>
	{/each}
</ul>

<Dialog headline="Remove from queue?" bind:open={dialogOpen}>
	<p class="dlg-title">{target?.title}{target?.subtitle ? ` · ${target.subtitle}` : ''}</p>
	<label class="check">
		<Checkbox><input type="checkbox" bind:checked={removeFromClient} /></Checkbox>
		<span>Also remove it from the download client</span>
	</label>
	<label class="check">
		<Checkbox><input type="checkbox" bind:checked={blocklist} /></Checkbox>
		<span>Blocklist this release so it isn't grabbed again</span>
	</label>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (dialogOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy} onclick={remove}>Remove</Button>
	{/snippet}
</Dialog>

<style>
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.row {
		display: flex;
		gap: 0.875rem;
		padding: 0.875rem 0.75rem 0.875rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		content-visibility: auto;
		contain-intrinsic-size: auto 6.5rem;
	}
	.row.warn {
		background: color-mix(in oklab, var(--m3c-error-container) 22%, var(--m3c-surface-container-low));
	}
	.art {
		flex: none;
		align-self: flex-start;
	}
	.body {
		flex: 1;
		min-width: 0;
	}
	.line1 {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		margin: -0.375rem 0 -0.25rem;
	}
	.title {
		flex: 1;
		min-width: 0;
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.bar {
		margin: 0.5rem 0 0.375rem;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.125rem 0.75rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.state {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		font-weight: 600;
		color: var(--m3c-primary);
	}
	.state-warning {
		color: var(--m3c-tertiary);
	}
	.state-error {
		color: var(--m3c-error);
	}
	.sub2 {
		margin-top: 0.125rem;
	}
	.svc {
		margin-left: auto;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.msgs-toggle {
		all: unset;
		cursor: pointer;
		display: block;
		margin-top: 0.375rem;
		font-size: 0.75rem;
		color: var(--m3c-error);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}
	.msgs {
		margin: 0.25rem 0 0;
		padding-left: 1.125rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.dlg-title {
		margin: 0 0 0.75rem;
		font-weight: 500;
	}
	.check {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.25rem 0;
		cursor: pointer;
	}
</style>
