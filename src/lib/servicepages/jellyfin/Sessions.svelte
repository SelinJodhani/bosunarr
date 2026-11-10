<script lang="ts">
	import { Button, Icon, LinearProgress } from 'm3-svelte';
	import iStop from '@ktibow/iconset-material-symbols/stop-circle-rounded';
	import iChat from '@ktibow/iconset-material-symbols/chat-rounded';
	import iPause from '@ktibow/iconset-material-symbols/pause-rounded';
	import iPlay from '@ktibow/iconset-material-symbols/play-arrow-rounded';
	import iBolt from '@ktibow/iconset-material-symbols/bolt-rounded';
	import Poster from '$lib/widgets/Poster.svelte';
	import { clock } from './api';
	import type { JfPlayMethod, JfSession } from './types';

	let {
		sessions,
		isAdmin,
		onstop,
		onmessage
	}: { sessions: JfSession[]; isAdmin: boolean; onstop: (s: JfSession) => void; onmessage: (s: JfSession) => void } = $props();

	const METHOD: Record<JfPlayMethod, string> = { 'direct-play': 'Direct play', 'direct-stream': 'Direct stream', transcode: 'Transcode' };
</script>

<ul class="sessions">
	{#each sessions as s (s.id)}
		<li class="card" class:paused={s.paused}>
			<Poster src={s.poster} width={56} alt="" />
			<div class="body">
				<div class="top">
					<div class="titles">
						<div class="title">
							<span class="state" title={s.paused ? 'Paused' : 'Playing'}><Icon icon={s.paused ? iPause : iPlay} size={18} /></span>
							<span class="ellipsis">{s.title}</span>
						</div>
						{#if s.subtitle}<div class="sub ellipsis">{s.subtitle}</div>{/if}
					</div>
					{#if isAdmin && s.canControl}
						<div class="actions">
							<Button variant="text" iconType="full" aria-label={`Send a message to ${s.user}`} onclick={() => onmessage(s)}><Icon icon={iChat} /></Button>
							<Button variant="text" iconType="full" aria-label={`Stop playback for ${s.user}`} onclick={() => onstop(s)}><Icon icon={iStop} /></Button>
						</div>
					{/if}
				</div>
				<div class="who ellipsis">{s.user}{s.client ? ` · ${s.client}` : ''}{s.device ? ` · ${s.device}` : ''}</div>
				<LinearProgress percent={s.runtimeSec ? Math.min(100, (s.positionSec / s.runtimeSec) * 100) : 0} aria-label={`${s.title} playback position`} />
				<div class="meta">
					<span class="time">{clock(s.positionSec)}{s.runtimeSec ? ` / ${clock(s.runtimeSec)}` : ''}{s.paused ? ' · Paused' : ''}</span>
					<span class="method" class:transcode={s.method === 'transcode'}>
						{#if s.hwAccel}<Icon icon={iBolt} size={14} />{/if}<span class="ellipsis">{METHOD[s.method]}{s.hwAccel ? ` (${s.hwAccel})` : ''}</span>
					</span>
				</div>
				{#if s.detail || s.reasons.length}
					<div class="why">
						{#if s.reasons.length}<span>{s.reasons.join(', ')}</span>{/if}
						{#if s.detail}<span class="detail">{s.detail}</span>{/if}
					</div>
				{/if}
			</div>
		</li>
	{/each}
</ul>

<style>
	.sessions {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
		gap: 0.75rem;
	}
	.card {
		display: flex;
		gap: 0.875rem;
		padding: 0.875rem;
		border-radius: var(--radius-l, 1rem);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
		align-content: start;
	}
	.top {
		display: flex;
		gap: 0.25rem;
		align-items: flex-start;
	}
	.titles {
		flex: 1;
		min-width: 0;
	}
	.title {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		font-weight: 500;
	}
	.state {
		display: inline-flex;
		color: var(--m3c-primary);
	}
	.paused .state {
		color: var(--m3c-on-surface-variant);
	}
	.ellipsis {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.sub,
	.who,
	.why {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.actions {
		display: flex;
		flex: none;
		margin: -0.5rem -0.5rem 0 0;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		gap: 0.25rem 0.5rem;
		font-size: 0.75rem;
		font-variant-numeric: tabular-nums;
	}
	.time {
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
	}
	.method {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
		padding: 0 0.5rem;
		border-radius: 0.5rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
		font-weight: 500;
		min-width: 0;
		overflow: hidden;
	}
	.method :global(svg) {
		flex: none;
	}
	.method.transcode {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.why {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.125rem;
		font-size: 0.75rem;
	}
	.detail {
		opacity: 0.85;
	}
</style>
