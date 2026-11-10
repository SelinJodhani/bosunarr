<script lang="ts">
	import { Button, Chip, Dialog, Icon, LoadingIndicator, Tabs, TextField, snackbar } from 'm3-svelte';
	import iGroup from '@ktibow/iconset-material-symbols/group-rounded';
	import iPending from '@ktibow/iconset-material-symbols/hourglass-top-rounded';
	import iUsed from '@ktibow/iconset-material-symbols/check-circle-rounded';
	import iExpired from '@ktibow/iconset-material-symbols/event-busy-rounded';
	import iCopy from '@ktibow/iconset-material-symbols/content-copy-rounded';
	import iDelete from '@ktibow/iconset-material-symbols/delete-rounded';
	import iAdd from '@ktibow/iconset-material-symbols/person-add-rounded';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iClose from '@ktibow/iconset-material-symbols/close-rounded';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '../types';
	import type { CreatedInvite, InviteStatus, WizarrInvite, WizarrOverview, WizarrUser } from './types';
	import { copyText, fmtDate, inviteLink, isPast, send } from './client';
	import CreateInvite from './CreateInvite.svelte';

	let { service, isAdmin }: ServicePageProps = $props();

	const api = $derived(`/api/svc/wizarr/${service.id}`);
	// The page is keyed by service id, so the URL is fixed for this instance.
	// svelte-ignore state_referenced_locally
	// persist: false — emails and invite codes must never land in localStorage.
	const data = live<WizarrOverview>(`/api/svc/wizarr/${service.id}`, 30_000, { persist: false });
	const ov = $derived(data.state.data);
	// Only the public URL makes a usable link; the internal one (e.g. http://wizarr:5690) is useless to invitees.
	const base = $derived(service.externalUrl);

	let tab = $state('invites');
	let q = $state('');
	let filter = $state<'all' | InviteStatus>('all');
	let busy = $state(false);
	let createOpen = $state(false);

	let delInvite = $state<WizarrInvite | null>(null);
	let delInviteOpen = $state(false);
	let delUser = $state<WizarrUser | null>(null);
	let delUserOpen = $state(false);
	let confirmName = $state('');

	const users = $derived.by(() => {
		const all = ov?.users ?? [];
		const needle = q.trim().toLowerCase();
		if (!needle) return all;
		return all.filter((u) => [u.username, u.email, u.server].some((v) => v?.toLowerCase().includes(needle)));
	});
	const invites = $derived((ov?.invitations ?? []).filter((i) => filter === 'all' || i.status === filter));
	const inviteCounts = $derived.by(() => {
		const c = { all: 0, pending: 0, used: 0, expired: 0 };
		for (const i of ov?.invitations ?? []) {
			c.all++;
			c[i.status]++;
		}
		return c;
	});

	const STATUS_LABEL: Record<InviteStatus, string> = { pending: 'Pending', used: 'Used', expired: 'Expired' };
	const FILTERS = [
		{ value: 'all', label: 'All' },
		{ value: 'pending', label: 'Pending' },
		{ value: 'used', label: 'Used' },
		{ value: 'expired', label: 'Expired' }
	] as const;

	function usesLeft(i: WizarrInvite) {
		if (!i.active) return 'No uses left';
		return i.unlimited ? 'Unlimited uses' : '1 use left';
	}
	function statusLabel(i: WizarrInvite) {
		return i.unlimited && i.active && i.status === 'used' ? 'Active' : STATUS_LABEL[i.status];
	}
	function access(i: WizarrInvite) {
		return i.durationDays ? `${i.durationDays}-day access` : 'Permanent access';
	}

	async function copy(i: { path: string | null }) {
		if (!base || !i.path) return;
		const link = inviteLink(base, i.path);
		const ok = await copyText(link);
		snackbar(ok ? `Invite link copied` : `Couldn't copy — the link is ${link}`, {}, true);
	}
	const label = (i: WizarrInvite) => i.code ?? `Invite #${i.id}`;

	function onCreated(inv: CreatedInvite) {
		data.refresh();
		snackbar(`Invite ${inv.code} created`, base ? { 'Copy link': () => copy(inv) } : {}, true, 8000);
	}

	async function removeInvite() {
		const i = delInvite;
		if (busy || !i) return;
		busy = true;
		const res = await send('DELETE', `${api}/invitations/${i.id}`);
		busy = false;
		snackbar(res.error ?? `Deleted invite ${label(i)}`, {}, true);
		if (!res.error) {
			delInviteOpen = false;
			data.refresh();
		}
	}

	async function removeUser() {
		const u = delUser;
		if (busy || !u || confirmName.trim() !== u.username) return;
		busy = true;
		const res = await send('DELETE', `${api}/users/${u.id}`);
		busy = false;
		snackbar(res.error ?? `Removed ${u.username}`, {}, true);
		if (!res.error) {
			delUserOpen = false;
			data.refresh();
		}
	}

	const initials = (name: string) =>
		name
			.split(/[\s._@-]+/)
			.filter(Boolean)
			.slice(0, 2)
			.map((p) => p[0]?.toUpperCase())
			.join('') || '?';
</script>

{#if !ov}
	{#if data.state.error}
		<section class="card msg">
			<p class="err">{data.state.error}</p>
			<Button variant="tonal" onclick={() => data.refresh()}><Icon icon={iRefresh} /> Retry</Button>
		</section>
	{:else}
		<div class="center"><LoadingIndicator aria-label="Loading Wizarr" /></div>
	{/if}
{:else}
	{#if data.state.error}<p class="stale" role="status">Showing saved data — {data.state.error}</p>{/if}

	<ul class="stats" aria-label="Summary">
		<li><Icon icon={iGroup} /><span class="n">{ov.counts.users}</span><span class="l">Users</span></li>
		<li class="pending"><Icon icon={iPending} /><span class="n">{ov.counts.pending}</span><span class="l">Pending</span></li>
		<li class="used"><Icon icon={iUsed} /><span class="n">{ov.counts.used}</span><span class="l">Used</span></li>
		<li class="expired"><Icon icon={iExpired} /><span class="n">{ov.counts.expired}</span><span class="l">Expired</span></li>
	</ul>

	<div class="tabs">
		<Tabs
			secondary
			bind:tab
			items={[
				{ name: `Invitations (${ov.invitations.length})`, value: 'invites' },
				{ name: `Users (${ov.users.length})`, value: 'users' }
			]}
		/>
	</div>

	{#if tab === 'invites'}
		<div class="bar">
			<div class="chips" role="toolbar" aria-label="Filter invitations">
				{#each FILTERS as f (f.value)}
					<Chip variant="general" selected={filter === f.value} onclick={() => (filter = f.value)}>{f.label} {inviteCounts[f.value]}</Chip>
				{/each}
			</div>
			{#if isAdmin}
				<Button variant="filled" onclick={() => (createOpen = true)}><Icon icon={iAdd} /> New invite</Button>
			{/if}
		</div>

		{#if isAdmin && !base}
			<p class="hint">To copy invite links, set a <strong>Public URL</strong> for this service in <a href="/settings">Settings</a>.</p>
		{/if}

		{#if invites.length === 0}
			<p class="empty">{ov.invitations.length ? 'No invitations match' : 'No invitations yet'}</p>
		{:else}
			<ul class="list">
				{#each invites as i (i.id)}
					<li class="invite s-{i.status}" class:active={i.active}>
						<div class="inv-head">
							{#if i.code}<code class="code">{i.code}</code>{:else}<span class="code anon">Invite #{i.id}</span>{/if}
							<span class="badge">{statusLabel(i)}</span>
						</div>
						<div class="meta">
							<span>{usesLeft(i)}</span>
							<span>{i.expires ? `${isPast(i.expires) ? 'Expired' : 'Expires'} ${fmtDate(i.expires)}` : 'Never expires'}</span>
							<span>{access(i)}</span>
							<span>Created {fmtDate(i.created)}</span>
							{#if i.usedBy}<span>Used by {i.usedBy}{i.usedAt ? ` · ${fmtDate(i.usedAt)}` : ''}</span>{/if}
						</div>
						{#if i.servers.length || i.libraries.length}
							<div class="tags">
								{#each i.servers as s, n (`s${n}`)}<span class="tag srv">{s}</span>{/each}
								{#each i.libraries as l, n (`l${n}`)}<span class="tag">{l}</span>{/each}
							</div>
						{/if}
						{#if isAdmin}
						<div class="actions">
							{#if i.active && base && i.path}
								<Button variant="tonal" onclick={() => copy(i)}><Icon icon={iCopy} /> Copy link</Button>
							{/if}
							{#if isAdmin}
								<Button
									variant="text"
									iconType="full"
									aria-label={`Delete invite ${label(i)}`}
									onclick={() => {
										delInvite = i;
										delInviteOpen = true;
									}}><Icon icon={iDelete} /></Button
								>
							{/if}
						</div>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	{:else}
		<div class="search">
			<TextField
				label="Search users"
				leadingIcon={iSearch}
				type="search"
				autocomplete="off"
				bind:value={q}
				trailing={q ? { icon: iClose, onclick: () => (q = ''), 'aria-label': 'Clear search' } : undefined}
			/>
		</div>
		{#if users.length === 0}
			<p class="empty">{ov.users.length ? 'No users match' : 'No users yet'}</p>
		{:else}
			<ul class="users">
				{#each users as u (u.id)}
					<li class="user">
						<span class="avatar" aria-hidden="true">{initials(u.username)}</span>
						<div class="u-main">
							<span class="u-name">{u.username}</span>
							{#if u.email}<span class="u-sub">{u.email}</span>{/if}
							<span class="u-sub">{u.server ?? 'Unknown server'}{u.serverType ? ` · ${u.serverType}` : ''}</span>
							<span class="u-dates">
								<span>Joined {fmtDate(u.created)}</span>
								<span class:warn={isPast(u.expires)}>{u.expires ? `${isPast(u.expires) ? 'Expired' : 'Expires'} ${fmtDate(u.expires)}` : 'No expiry'}</span>
							</span>
						</div>
						{#if isAdmin}
							<Button
								variant="text"
								iconType="full"
								aria-label={`Remove ${u.username}`}
								onclick={() => {
									delUser = u;
									confirmName = '';
									delUserOpen = true;
								}}><Icon icon={iDelete} /></Button
							>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	{/if}

	{#if ov.warnings.length && isAdmin}<p class="stale">{ov.warnings.join(' · ')}</p>{/if}
{/if}

{#if isAdmin && ov}
	<CreateInvite bind:open={createOpen} serviceId={service.id} servers={ov.servers} libraries={ov.libraries} created={onCreated} />
{/if}

{#if isAdmin}
<Dialog headline="Delete invite?" bind:open={delInviteOpen}>
	<div class="dlg">
		<p>Invite <code>{delInvite?.code ?? `#${delInvite?.id ?? ""}`}</code> stops working immediately. People who already joined keep their accounts.</p>
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (delInviteOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy} onclick={removeInvite}>Delete</Button>
	{/snippet}
</Dialog>

<Dialog headline="Remove user?" bind:open={delUserOpen}>
	<div class="dlg">
		<p>
			<strong>{delUser?.username}</strong> is deleted from {delUser?.server ?? 'the media server'} and loses access straight away. This
			can't be undone.
		</p>
		<TextField label="Type the username to confirm" autocomplete="off" bind:value={confirmName} enter={removeUser} />
	</div>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (delUserOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !delUser || confirmName.trim() !== delUser.username} onclick={removeUser}>Remove</Button>
	{/snippet}
</Dialog>
{/if}

<style>
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.card {
		padding: 1.25rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
	}
	.msg {
		display: grid;
		gap: 1rem;
		justify-items: start;
	}
	.msg p {
		margin: 0;
	}
	.err,
	.stale {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.stale {
		margin: 0 0 0.75rem;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.hint {
		margin: 0 0 0.75rem;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.hint a {
		color: var(--m3c-primary);
	}
	.anon {
		font-family: inherit;
		color: var(--m3c-on-surface-variant);
	}

	.stats {
		list-style: none;
		margin: 0 0 1rem;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 0.5rem;
	}
	.stats li {
		display: grid;
		justify-items: start;
		gap: 0.125rem;
		padding: 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		color: var(--m3c-primary);
		min-width: 0;
	}
	.stats .pending {
		color: var(--m3c-tertiary);
	}
	.stats .used {
		color: var(--m3c-secondary);
	}
	.stats .expired {
		color: var(--m3c-on-surface-variant);
	}
	.stats .n {
		font-size: 1.5rem;
		line-height: 1.2;
		font-weight: 600;
		color: var(--m3c-on-surface);
	}
	.stats .l {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		max-width: 100%;
	}
	@media (max-width: 22rem) {
		.stats {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}

	.tabs {
		margin-bottom: 0.75rem;
	}
	.bar {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	.chips {
		flex: 1;
		min-width: 0;
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		scrollbar-width: none;
	}
	.chips > :global(*) {
		flex: none;
	}
	.bar > :global(button) {
		flex: none;
	}
	@media (max-width: 30rem) {
		.bar {
			flex-wrap: wrap-reverse;
		}
		.chips {
			flex-basis: 100%;
		}
	}

	.list,
	.users {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.users {
		grid-template-columns: repeat(auto-fill, minmax(min(20rem, 100%), 1fr));
	}
	@media (min-width: 60rem) {
		.list {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}

	.invite {
		display: grid;
		gap: 0.5rem;
		padding: 0.875rem 1rem 0.5rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.inv-head {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
	}
	.code {
		font-family: ui-monospace, 'SF Mono', Menlo, monospace;
		font-size: 1rem;
		font-weight: 600;
		overflow-wrap: anywhere;
		min-width: 0;
	}
	.badge {
		flex: none;
		margin-left: auto;
		padding: 0.125rem 0.5rem;
		border-radius: 1rem;
		font-size: 0.75rem;
		font-weight: 600;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.s-pending .badge,
	.active .badge {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.s-used:not(.active) .badge {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.s-expired {
		opacity: 0.8;
	}
	.meta,
	.u-dates {
		display: flex;
		flex-wrap: wrap;
		gap: 0.125rem 0.75rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
	}
	.tag {
		padding: 0.125rem 0.5rem;
		border-radius: var(--radius-s, 0.5rem);
		border: 1px solid var(--m3c-outline-variant);
		font-size: 0.75rem;
		overflow-wrap: anywhere;
	}
	.tag.srv {
		border-color: transparent;
		background: var(--m3c-surface-container-high);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		align-items: center;
		gap: 0.25rem;
	}

	.search {
		margin-bottom: 0.75rem;
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.user {
		display: flex;
		align-items: flex-start;
		gap: 0.75rem;
		padding: 0.875rem 0.5rem 0.875rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	.avatar {
		flex: none;
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
		font-weight: 600;
		font-size: 0.875rem;
	}
	.u-main {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.125rem;
	}
	.u-name {
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.u-sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.u-dates {
		margin-top: 0.25rem;
	}
	.warn {
		color: var(--m3c-error);
	}

	.dlg {
		display: grid;
		gap: 1rem;
		margin: 0;
		max-width: 26rem;
	}
	.dlg p {
		margin: 0;
	}
	.dlg :global(.m3-container) {
		width: 100%;
	}
</style>
