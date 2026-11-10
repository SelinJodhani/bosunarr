<script lang="ts">
	import { Button, Icon, LinearProgress } from 'm3-svelte';
	import Empty from '../shared/Empty.svelte';
	import iNone from '@ktibow/iconset-material-symbols/notifications-off-rounded';
	import iRun from '@ktibow/iconset-material-symbols/play-arrow-rounded';
	import iOk from '@ktibow/iconset-material-symbols/check-circle-rounded';
	import iFail from '@ktibow/iconset-material-symbols/error-rounded';
	import iCancel from '@ktibow/iconset-material-symbols/cancel-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import iWarn from '@ktibow/iconset-material-symbols/warning-rounded';
	import iAdmin from '@ktibow/iconset-material-symbols/admin-panel-settings-rounded';
	import iPerson from '@ktibow/iconset-material-symbols/person-rounded';
	import { ago } from '$lib/format';
	import type { JfAdminData, JfTask } from './types';

	let {
		data,
		view,
		isAdmin,
		onrun
	}: { data: JfAdminData; view: 'users' | 'tasks' | 'activity'; isAdmin: boolean; onrun: (t: JfTask) => void } = $props();

	const current = $derived(view);

	const groups = $derived.by(() => {
		const m = new Map<string, JfTask[]>();
		for (const t of data.tasks) m.set(t.category, [...(m.get(t.category) ?? []), t]);
		return [...m];
	});

	let shown = $state(15);
	const when = (iso: string | null) => (iso ? ago(iso) : 'Never');
	const STATUS_ICON = { Completed: iOk, Failed: iFail, Cancelled: iCancel, Aborted: iFail } as const;
</script>

<div class="pane">
	{#if current === 'users' && data.users}
		<ul class="list">
			{#each data.users as u (u.id)}
				<li class:off={u.disabled}>
					<span class="avatar" aria-hidden="true"><Icon icon={u.admin ? iAdmin : iPerson} /></span>
					<div class="text">
						<div class="row1">
							<span class="name">{u.name}</span>
							{#if u.admin}<span class="badge">Admin</span>{/if}
							{#if u.disabled}<span class="badge muted">Disabled</span>{/if}
						</div>
						<div class="sub">Active {when(u.lastActivity).toLowerCase()} · Login {when(u.lastLogin).toLowerCase()}</div>
					</div>
				</li>
			{/each}
		</ul>
	{:else if current === 'activity' && data.activity}
		{#if data.activity.length === 0}
			<Empty icon={iNone} title="No recent activity" />
		{:else}
			<ul class="list">
				{#each data.activity.slice(0, shown) as e (e.id)}
					<li>
						<span class="sev {e.severity}" aria-label={e.severity}><Icon icon={e.severity === 'info' ? iInfo : e.severity === 'warning' ? iWarn : iFail} size={20} /></span>
						<div class="text">
							<div class="wrap">{e.name}</div>
							<div class="sub">{ago(e.date)}{e.overview ? ` · ${e.overview}` : ''}</div>
						</div>
					</li>
				{/each}
			</ul>
			{#if data.activity.length > shown}
				<div class="more"><Button variant="text" onclick={() => (shown += 25)}>Show more</Button></div>
			{/if}
		{/if}
	{:else}
		{#each groups as [cat, tasks], gi (cat)}
			<h3 class="cat" class:first={gi === 0}>{cat}</h3>
			<ul class="list">
				{#each tasks as t (t.id)}
					<li>
						<div class="text">
							<div class="name">{t.name}</div>
							{#if t.state !== 'idle'}
								<div class="sub">{t.state === 'running' ? 'Running' : 'Cancelling'}{t.progress !== null ? ` · ${Math.round(t.progress)}%` : '…'}</div>
								<LinearProgress percent={t.progress ?? 0} aria-label={`${t.name} progress`} />
							{:else}
								<div class="sub status">
									{#if t.lastStatus}
										<span class="st {t.lastStatus.toLowerCase()}"><Icon icon={STATUS_ICON[t.lastStatus as keyof typeof STATUS_ICON] ?? iInfo} size={16} />{t.lastStatus}</span>
										<span>· {when(t.lastEnded)}</span>
									{:else}
										<span>Never run</span>
									{/if}
								</div>
								{#if t.lastError}<div class="err">{t.lastError}</div>{/if}
							{/if}
						</div>
						{#if isAdmin}
							<Button variant="text" iconType="full" disabled={t.state !== 'idle'} aria-label={`Run ${t.name} now`} onclick={() => onrun(t)}><Icon icon={iRun} /></Button>
						{/if}
					</li>
				{/each}
			</ul>
		{/each}
	{/if}
</div>

<style>
	.cat.first {
		margin-top: 0;
	}
	.cat {
		margin: 0.75rem 0 0;
		font-size: 0.875rem;
		font-weight: 500;
		color: var(--m3c-primary);
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	li {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0;
		min-width: 0;
	}
	li + li {
		border-top: 1px solid var(--m3c-outline-variant);
	}
	li.off {
		opacity: 0.6;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.row1 {
		display: flex;
		align-items: center;
		gap: 0.375rem;
		min-width: 0;
	}
	.name {
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.wrap {
		font-size: 0.875rem;
		overflow-wrap: anywhere;
	}
	.sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.status {
		display: flex;
		gap: 0.25rem;
		align-items: center;
	}
	.st {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
	}
	.st.completed {
		color: var(--m3c-primary);
	}
	.st.failed,
	.st.aborted,
	.err {
		color: var(--m3c-error);
	}
	.err {
		font-size: 0.75rem;
		overflow-wrap: anywhere;
	}
	.badge {
		flex: none;
		font-size: 0.6875rem;
		font-weight: 600;
		padding: 0.0625rem 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.badge.muted {
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.avatar,
	.sev {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--m3c-surface-container-high);
		color: var(--m3c-on-surface-variant);
	}
	.sev {
		width: 2rem;
		height: 2rem;
		background: none;
	}
	.sev.warning {
		color: var(--m3c-tertiary);
	}
	.sev.error {
		color: var(--m3c-error);
	}
	.more {
		display: flex;
		justify-content: center;
		padding-top: 0.25rem;
	}
</style>
