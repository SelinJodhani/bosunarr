<script lang="ts">
	import { Button, Chip, Dialog, Icon, TextField, snackbar } from 'm3-svelte';
	import iCopy from '@ktibow/iconset-material-symbols/content-copy-rounded';
	import iCopied from '@ktibow/iconset-material-symbols/check-rounded';
	import iDelete from '@ktibow/iconset-material-symbols/delete-rounded';
	import iAdd from '@ktibow/iconset-material-symbols/person-add-rounded';
	import iSearch from '@ktibow/iconset-material-symbols/search-rounded';
	import iClose from '@ktibow/iconset-material-symbols/close-rounded';
	import iMail from '@ktibow/iconset-material-symbols/mail-rounded';
	import iGroup from '@ktibow/iconset-material-symbols/group-rounded';
	import iLink from '@ktibow/iconset-material-symbols/link-rounded';
	import iSettings from '@ktibow/iconset-material-symbols/settings-rounded';
	import { live } from '$lib/live.svelte';
	import type { ServicePageProps } from '../types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import ConfirmDialog from '../shared/ConfirmDialog.svelte';
	import { niceName, sentence } from '../shared/format';
	import type { CreatedInvite, WizarrInvite, WizarrOverview, WizarrUser } from './types';
	import { copyText, fmtDate, inviteLink, isPast, send } from './client';
	import CreateInvite from './CreateInvite.svelte';

	let { service, isAdmin }: ServicePageProps = $props();

	const api = $derived(`/api/svc/wizarr/${service.id}`);
	// The page is keyed by service id, so the URL is fixed for this instance.
	// persist: false — emails and invite codes must never land in localStorage.
	// svelte-ignore state_referenced_locally
	const data = live<WizarrOverview>(`/api/svc/wizarr/${service.id}`, 30_000, { persist: false });
	const ov = $derived(data.state.data);
	// Only the public URL makes a usable link; the internal one (e.g. http://wizarr:5690) is useless to invitees.
	const base = $derived(service.externalUrl);

	type Label = 'Pending' | 'Active' | 'Used' | 'Expired';
	type Filter = 'all' | Label;

	let tab = $state('invites');
	let q = $state('');
	let filter = $state<Filter>('all');
	let createOpen = $state(false);

	let delInvite = $state<WizarrInvite | null>(null);
	let delInviteOpen = $state(false);
	let delUser = $state<WizarrUser | null>(null);
	let delUserOpen = $state(false);
	let confirmName = $state('');

	/** One label per invite; the filter chips use the same words. */
	function statusLabel(i: WizarrInvite): Label {
		if (i.status === 'expired') return 'Expired';
		if (i.status === 'pending') return 'Pending';
		return i.unlimited && i.active ? 'Active' : 'Used';
	}

	const users = $derived.by(() => {
		const all = ov?.users ?? [];
		const needle = q.trim().toLowerCase();
		if (!needle) return all;
		return all.filter((u) => [u.username, u.email, u.server].some((v) => v?.toLowerCase().includes(needle)));
	});
	const invites = $derived((ov?.invitations ?? []).filter((i) => filter === 'all' || statusLabel(i) === filter));
	const counts = $derived.by(() => {
		const c: Record<Filter, number> = { all: 0, Pending: 0, Active: 0, Used: 0, Expired: 0 };
		for (const i of ov?.invitations ?? []) {
			c.all++;
			c[statusLabel(i)]++;
		}
		return c;
	});
	const FILTERS: Array<{ value: Filter; label: string }> = [
		{ value: 'all', label: 'All' },
		{ value: 'Pending', label: 'Pending' },
		{ value: 'Active', label: 'Active' },
		{ value: 'Used', label: 'Used' },
		{ value: 'Expired', label: 'Expired' }
	];

	function usesLeft(i: WizarrInvite) {
		if (!i.active) return 'No uses left';
		return i.unlimited ? 'Unlimited uses' : '1 use left';
	}
	function access(i: WizarrInvite) {
		return i.durationDays ? `${i.durationDays}-day access` : 'Permanent access';
	}
	/** "Jellyfin" (type) is only worth showing when the server's name doesn't already say it. */
	function serverLine(u: WizarrUser) {
		const name = u.server ?? 'Unknown server';
		const type = niceName(u.serverType);
		return type && !name.toLowerCase().includes(type.toLowerCase()) ? `${name} · ${type}` : name;
	}

	/* ---------- copying ---------- */
	let copiedKey = $state<string | null>(null);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;
	$effect(() => () => clearTimeout(copiedTimer));
	async function copy(text: string, key: string, what: string) {
		const ok = await copyText(text);
		if (!ok) {
			snackbar(`Couldn't copy the ${what}. Select it and copy it by hand.`, {}, true);
			return;
		}
		copiedKey = key;
		clearTimeout(copiedTimer);
		copiedTimer = setTimeout(() => (copiedKey = null), 2000);
	}
	const label = (i: WizarrInvite) => i.code ?? `Invite #${i.id}`;

	/* ---------- created ---------- */
	let created = $state<CreatedInvite | null>(null);
	let createdOpen = $state(false);
	function onCreated(inv: CreatedInvite) {
		data.refresh();
		created = inv;
		createdOpen = true;
	}

	async function removeInvite(): Promise<string | null> {
		const i = delInvite;
		if (!i) return null;
		const res = await send('DELETE', `${api}/invitations/${i.id}`);
		if (res.error) return res.error;
		snackbar(`Deleted invite ${label(i)}`, {}, true);
		data.refresh();
		return null;
	}

	async function removeUser(): Promise<string | null> {
		const u = delUser;
		if (!u || confirmName.trim() !== u.username) return null;
		const res = await send('DELETE', `${api}/users/${u.id}`);
		if (res.error) return res.error;
		snackbar(`Removed ${u.username}`, {}, true);
		data.refresh();
		return null;
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
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(data.state.error)} onretry={() => data.refresh()} />
	{:else}
		<Loading label="Loading Wizarr" />
	{/if}
{:else}
	<Summary
		meta={['Wizarr', ov.servers.length ? ov.servers.map((s) => s.name).join(', ') : null]}
		stats={[
			{ value: ov.counts.users, label: 'Users', onclick: () => (tab = 'users') },
			{ value: counts.Pending, label: 'Pending', tone: counts.Pending ? 'info' : undefined, onclick: () => ((tab = 'invites'), (filter = 'Pending')) },
			{ value: counts.Used, label: 'Used', onclick: () => ((tab = 'invites'), (filter = 'Used')) },
			{ value: counts.Expired, label: 'Expired', onclick: () => ((tab = 'invites'), (filter = 'Expired')) }
		]}
		notices={isAdmin ? ov.warnings.map((w) => ({ tone: 'warn' as const, text: w })) : []}
		stale={data.state.error}
	/>

	<ServiceTabs
		bind:tab
		items={[
			{ name: 'Invitations', value: 'invites' },
			{ name: 'Users', value: 'users' }
		]}
	>
		{#if tab === 'invites'}
			<div class="bar">
				<div class="chips no-scrollbar" role="toolbar" aria-label="Filter invitations">
					{#each FILTERS as f (f.value)}
						{#if f.value === 'all' || counts[f.value] > 0 || filter === f.value}
							<Chip variant="general" selected={filter === f.value} onclick={() => (filter = f.value)}>{f.label} {counts[f.value]}</Chip>
						{/if}
					{/each}
				</div>
				{#if isAdmin}
					<Button variant="filled" onclick={() => (createOpen = true)}><Icon icon={iAdd} /> New invite</Button>
				{/if}
			</div>

			{#if isAdmin && !base}
				<div class="notice">
					<Icon icon={iLink} size={20} />
					<p>Invite links need this service's <strong>Public URL</strong>. Until it's set, share the invite code instead.</p>
					<Button variant="text" href="/settings"><Icon icon={iSettings} /> Settings</Button>
				</div>
			{/if}

			{#if invites.length === 0}
				<Empty icon={iMail} title={ov.invitations.length ? 'No invitations match this filter' : 'No invitations yet'} hint={ov.invitations.length || !isAdmin ? null : 'Create one to let someone join your media server.'} />
			{:else}
				<ul class="list">
					{#each invites as i (i.id)}
						{@const st = statusLabel(i)}
						<li class="invite s-{st.toLowerCase()}">
							<div class="inv-head">
								{#if i.code}<code class="code">{i.code}</code>{:else}<span class="code anon">Invite #{i.id}</span>{/if}
								<span class="badge">{st}</span>
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
							<div class="meta">
								<span>{usesLeft(i)}</span>
								<span>{i.expires ? `${isPast(i.expires) ? 'Expired' : 'Expires'} ${fmtDate(i.expires)}` : 'Never expires'}</span>
								<span>{access(i)}</span>
								<span>Created {fmtDate(i.created)}</span>
								{#if i.usedBy}<span>Used by {i.usedBy}{i.usedAt ? ` · ${fmtDate(i.usedAt)}` : ''}</span>{/if}
							</div>
							{#if i.servers.length || i.libraries.length}
								<div class="tags">
									{#each i.servers as s, n (`s${n}`)}<span class="tag">{s}</span>{/each}
									{#each i.libraries as l, n (`l${n}`)}<span class="tag">{l}</span>{/each}
								</div>
							{/if}
							{#if isAdmin && i.active && base && i.path}
								{@const key = `inv${i.id}`}
								<div class="actions">
									<Button variant="tonal" onclick={() => copy(inviteLink(base, i.path ?? ''), key, 'link')}>
										<Icon icon={copiedKey === key ? iCopied : iCopy} />
										{copiedKey === key ? 'Copied' : 'Copy link'}
									</Button>
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
				<Empty icon={iGroup} title={ov.users.length ? 'No users match' : 'No users yet'} hint={ov.users.length ? null : 'People who join with an invite show up here.'} />
			{:else}
				<ul class="users">
					{#each users as u (u.id)}
						<li class="user">
							<span class="avatar" aria-hidden="true">{initials(u.username)}</span>
							<div class="u-main">
								<span class="u-name">{u.username}</span>
								{#if u.email}<span class="u-sub">{u.email}</span>{/if}
								<span class="u-sub">{serverLine(u)}</span>
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
	</ServiceTabs>
{/if}

{#if isAdmin && ov}
	<CreateInvite bind:open={createOpen} serviceId={service.id} servers={ov.servers} libraries={ov.libraries} created={onCreated} />
{/if}

{#if isAdmin}
	<Dialog headline="Invite created" bind:open={createdOpen}>
		{#if created}
			<div class="dlg">
				<code class="big-code">{created.code}</code>
				{#if base}
					{@const link = inviteLink(base, created.path)}
					<p class="link">{link}</p>
				{:else}
					<p>Share this code. People join at your Wizarr address and enter it there.</p>
					<p class="hint">For a one-tap link, set this service's <strong>Public URL</strong> in Settings.</p>
					<div><Button variant="tonal" href="/settings"><Icon icon={iSettings} /> Open Settings</Button></div>
				{/if}
			</div>
		{/if}
		{#snippet buttons()}
			<Button variant="text" onclick={() => (createdOpen = false)}>Done</Button>
			{#if created}
				{@const key = `new${created.id}`}
				<Button
					variant="filled"
					onclick={() => created && (base ? copy(inviteLink(base, created.path), key, 'link') : copy(created.code, key, 'code'))}
				>
					<Icon icon={copiedKey === key ? iCopied : iCopy} />
					{copiedKey === key ? 'Copied' : base ? 'Copy link' : 'Copy code'}
				</Button>
			{/if}
		{/snippet}
	</Dialog>

	<ConfirmDialog bind:open={delInviteOpen} headline="Delete invite?" confirm="Delete" danger onconfirm={removeInvite}>
		<p>Invite <code>{delInvite ? label(delInvite) : ''}</code> stops working immediately. People who already joined keep their accounts.</p>
	</ConfirmDialog>

	<ConfirmDialog
		bind:open={delUserOpen}
		headline="Remove user?"
		confirm="Remove"
		danger
		disabled={!delUser || confirmName.trim() !== delUser.username}
		onconfirm={removeUser}
	>
		<p>
			<strong>{delUser?.username}</strong> is deleted from {delUser?.server ?? 'the media server'} and loses access straight away. This can't be undone.
		</p>
		<div class="field"><TextField label="Type the username to confirm" autocomplete="off" bind:value={confirmName} /></div>
	</ConfirmDialog>
{/if}

<style>
	.bar {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.notice {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.75rem;
		padding: 0.25rem 0.25rem 0.25rem 0.875rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		color: var(--m3c-on-surface-variant);
		font-size: 0.8125rem;
	}
	.notice p {
		margin: 0;
	}
	.anon {
		font-family: inherit;
		color: var(--m3c-on-surface-variant);
	}
	.invite {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
		padding: 0.5rem 0.5rem 0.875rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
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
	.inv-head .badge:last-child {
		margin-right: 0.5rem;
	}
	.s-pending .badge,
	.s-active .badge {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.s-used .badge {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.s-expired {
		opacity: 0.8;
	}
	.inv-head {
		min-height: 2.5rem;
	}
	.meta,
	.tags,
	.actions {
		padding-right: 0.5rem;
	}
	.actions {
		display: flex;
		gap: 0.5rem;
	}
	.search {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	.dlg {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		margin: 0;
	}
	.dlg p {
		margin: 0;
	}
	.big-code {
		justify-self: start;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-highest);
		font-family: ui-monospace, 'SF Mono', Menlo, monospace;
		font-size: 1.25rem;
		font-weight: 600;
		letter-spacing: 0.05em;
		user-select: all;
		overflow-wrap: anywhere;
	}
	.link {
		font-size: 0.875rem;
		color: var(--m3c-primary);
		overflow-wrap: anywhere;
		user-select: all;
	}
	.hint {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.field {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	.field :global(.m3-container) {
		width: 100%;
	}
	.anon {
		font-family: inherit;
		color: var(--m3c-on-surface-variant);
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
		grid-template-columns: minmax(0, 1fr);
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
		grid-template-columns: minmax(0, 1fr);
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

</style>
