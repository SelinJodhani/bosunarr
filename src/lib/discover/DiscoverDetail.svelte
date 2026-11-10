<script lang="ts">
	import { page } from '$app/state';
	import { Button, Icon, LoadingIndicator, snackbar } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import { posterSeed } from '$lib/library/client';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iBack, iCheck, iDecline, iLibrary, iOpen, iPerson, iRequest, iStar } from '$lib/icons';
	import { AVAILABILITY_LABEL, REQUEST_LABEL, peek, post } from './client';
	import RequestDialog from './RequestDialog.svelte';
	import type { Availability, DiscoverDetail, DiscoverItem, RequestAction, RequestBrief } from './types';

	let { service, type, id }: { service: string; type: string; id: string } = $props();

	// The route re-mounts this component per item ({#key}), so props are fixed for its lifetime.
	// svelte-ignore state_referenced_locally
	const q = live<DiscoverDetail>(`/api/discover/${service}/${type}/${id}`, 60_000);
	let peeked = $state<DiscoverItem | undefined>();
	$effect(() => {
		peeked = peek(service, type, id);
	});

	const d = $derived(q.state.data);
	const item = $derived<DiscoverItem | undefined>(d ?? peeked);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));

	/* Poster-derived theme */
	let seed = $state<string | null>(null);
	const posterSrc = $derived(item?.poster ?? null);
	$effect(() => {
		const src = posterSrc;
		if (src) posterSeed(src).then((s) => src === posterSrc && (seed = s));
	});

	let showAllOverview = $state(false);
	let requestOpen = $state(false);
	let busy = $state<number | null>(null);

	const meta = $derived.by(() => {
		if (!item) return [];
		const m: string[] = [item.mediaType === 'movie' ? 'Movie' : 'Series'];
		if (item.year) m.push(String(item.year));
		if (d?.runtime) m.push(d.runtime >= 60 ? `${Math.floor(d.runtime / 60)}h ${d.runtime % 60}m` : `${d.runtime}m`);
		if (d?.certification) m.push(d.certification);
		if (d?.mediaType === 'tv' && d.seasons.length) m.push(`${d.seasons.length} season${d.seasons.length === 1 ? '' : 's'}`);
		return m;
	});

	/** Can anything still be requested (regular or, when configured, 4K)? */
	const requestable = $derived.by(() => {
		if (!d) return false;
		if (d.mediaType === 'movie') return d.status === 'none' || (d.can4k && d.status4k === 'none');
		return d.seasons.some((s) => s.status === 'none' || (d.can4k && s.status4k === 'none'));
	});
	const anyRequested = $derived(Boolean(d && (d.status !== 'none' || d.status4k !== 'none' || d.seasons.some((s) => s.status !== 'none'))));

	const fmtDate = (iso: string | null) => (iso ? new Date(iso + (iso.length === 10 ? 'T00:00:00' : '')).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—');

	async function act(r: RequestBrief, action: RequestAction['action']) {
		if (!d) return;
		busy = r.id;
		const err = await post('/api/discover/requests/action', { serviceId: d.serviceId, requestId: r.id, action } satisfies RequestAction);
		busy = null;
		snackbar(err ?? (action === 'approve' ? 'Request approved' : 'Request declined'), {}, true);
		q.refresh();
	}

	const back = () => (history.length > 1 ? history.back() : (location.href = '/discover'));
	const statusClass = (s: Availability) => `status-${s}`;
</script>

<svelte:head><title>{item?.title ?? 'Discover'} · Bosunarr</title></svelte:head>

<div class="m3-theme themed" style:--m3v-source={seed ?? undefined}>
	{#if d?.backdrop}
		<div class="backdrop" aria-hidden="true">
			<img src={d.backdrop} alt="" decoding="async" />
		</div>
	{/if}

	<div class="top">
		<Button variant="text" iconType="full" aria-label="Back" onclick={back}><Icon icon={iBack} /></Button>
	</div>

	{#if !item}
		{#if q.state.error}
			<p class="error" role="alert">{q.state.error}</p>
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading" /></div>
		{/if}
	{:else}
		<section class="hero">
			<div class="poster"><Poster src={item.poster} alt={`${item.title} poster`} width={180} /></div>
			<div class="heading">
				<h1>{item.title}</h1>
				{#if d?.tagline}<p class="tagline">{d.tagline}</p>{/if}
				{#if meta.length}<p class="meta">{meta.join(' · ')}</p>{/if}
				<div class="chips">
					<span class="chip {statusClass(item.status)}">{AVAILABILITY_LABEL[item.status]}</span>
					{#if item.status4k !== 'none' || d?.can4k}
						<span class="chip {statusClass(item.status4k)}">4K · {AVAILABILITY_LABEL[item.status4k]}</span>
					{/if}
					{#if d}
						{#each d.ratings as r (r.source)}
							<span class="chip subtle" title={r.source === 'rt' || r.source === 'rtAudience' ? 'Rotten Tomatoes' : r.label}>
								{#if r.source === 'tmdb' || r.source === 'imdb'}<Icon icon={iStar} size={16} />{/if}
								{r.label} {r.value}
							</span>
						{/each}
					{:else if item.rating}
						<span class="chip subtle"><Icon icon={iStar} size={16} /> TMDb {item.rating}</span>
					{/if}
				</div>
				{#if d}
					<div class="actions">
						{#if isAdmin && requestable}
							<Button variant="filled" onclick={() => (requestOpen = true)}><Icon icon={iRequest} /> {anyRequested ? 'Request more' : 'Request'}</Button>
						{/if}
						{#each d.inLibrary as l (l.href)}
							<Button variant="tonal" href={l.href}><Icon icon={iLibrary} /> In Library{d.inLibrary.length > 1 ? ` · ${l.serviceName}` : ''}</Button>
						{/each}
						{#if d.openUrl}
							<Button variant="text" href={d.openUrl} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} /> {d.serviceName}</Button>
						{/if}
					</div>
					{#if !isAdmin && requestable}<p class="hint">Requests from Bosunarr are admin-only for now.</p>{/if}
				{/if}
			</div>
		</section>

		{#if !d}
			{#if q.state.error}
				<p class="error" role="alert">{q.state.error}</p>
			{:else}
				<div class="center"><LoadingIndicator aria-label="Loading details" /></div>
			{/if}
		{:else}
			{#if d.overview}
				<section class="card">
					<p class="overview" class:clamp={!showAllOverview}>{d.overview}</p>
					{#if d.overview.length > 280}
						<button class="more" onclick={() => (showAllOverview = !showAllOverview)}>{showAllOverview ? 'Less' : 'More'}</button>
					{/if}
					{#if d.genres.length}<p class="genres">{d.genres.join(' · ')}</p>{/if}
				</section>
			{/if}

			<div class="cols">
				<section class="card info">
					<h2 class="title-medium">Details</h2>
					<dl>
						{#if d.releaseStatus}<dt>Status</dt><dd>{d.releaseStatus}</dd>{/if}
						<dt>{d.mediaType === 'movie' ? 'Release' : 'First aired'}</dt><dd>{fmtDate(d.date)}</dd>
						{#if d.network}<dt>{d.mediaType === 'movie' ? 'Studio' : 'Network'}</dt><dd>{d.network}</dd>{/if}
						{#if d.director}<dt>Director</dt><dd>{d.director}</dd>{/if}
						{#if d.seasons.length}
							<dt>Seasons</dt>
							<dd>
								<ul class="seasons">
									{#each d.seasons as s (s.number)}
										<li>
											<span>{s.name}</span>
											<span class="schip {statusClass(s.status)}">{AVAILABILITY_LABEL[s.status]}</span>
											{#if s.status4k !== 'none'}<span class="schip {statusClass(s.status4k)}">4K · {AVAILABILITY_LABEL[s.status4k]}</span>{/if}
										</li>
									{/each}
								</ul>
							</dd>
						{/if}
					</dl>
					{#if d.imdbId || d.tmdbId || d.tvdbId}
						<p class="links">
							{#if d.imdbId}<a href={`https://www.imdb.com/title/${d.imdbId}/`} target="_blank" rel="noopener noreferrer">IMDb</a>{/if}
							<a href={`https://www.themoviedb.org/${d.mediaType}/${d.tmdbId}`} target="_blank" rel="noopener noreferrer">TMDb</a>
							{#if d.tvdbId}<a href={`https://thetvdb.com/?tab=series&id=${d.tvdbId}`} target="_blank" rel="noopener noreferrer">TVDB</a>{/if}
						</p>
					{/if}
				</section>

				<div>
					{#if d.requests.length}
						<section class="card">
							<h2 class="title-medium">Requests</h2>
							<ul class="reqs">
								{#each d.requests as r (r.id)}
									<li>
										<div class="rtext">
											<span class="rtitle">
												{REQUEST_LABEL[r.status]}{r.is4k ? ' · 4K' : ''}{r.seasons.length ? ` · ${r.seasons.map((n) => `S${n}`).join(', ')}` : ''}
											</span>
											<span class="rsub">{r.requestedBy} · {ago(r.createdAt)}</span>
										</div>
										{#if isAdmin && r.status === 'pending'}
											<Button variant="tonal" iconType="full" aria-label="Approve request" disabled={busy === r.id} onclick={() => act(r, 'approve')}><Icon icon={iCheck} /></Button>
											<Button variant="text" iconType="full" aria-label="Decline request" disabled={busy === r.id} onclick={() => act(r, 'decline')}><Icon icon={iDecline} /></Button>
										{/if}
									</li>
								{/each}
							</ul>
						</section>
					{/if}

					{#if d.cast.length}
						<section class="card cast-card">
							<h2 class="title-medium">Cast</h2>
							<ul class="cast">
								{#each d.cast as c, i (i)}
									<li>
										<div class="face">
											{#if c.photo}<img src={c.photo} alt="" loading="lazy" decoding="async" />{:else}<Icon icon={iPerson} size={32} />{/if}
										</div>
										<span class="name">{c.name}</span>
										{#if c.character}<span class="role">{c.character}</span>{/if}
									</li>
								{/each}
							</ul>
						</section>
					{/if}
				</div>
			</div>

			{#if isAdmin}<RequestDialog bind:open={requestOpen} detail={d} ondone={() => q.refresh()} />{/if}
		{/if}
	{/if}
</div>

<style>
	.themed {
		position: relative;
		isolation: isolate;
		background: var(--m3c-surface);
		color: var(--m3c-on-surface);
		margin: -1rem calc(-1 * max(1rem, env(safe-area-inset-left))) -1.5rem;
		padding: 0.5rem max(1rem, env(safe-area-inset-left)) 2rem;
		min-height: 100dvh;
		transition: background-color 300ms;
	}
	@media (width >= 52.5rem) {
		.themed {
			margin: -1.5rem -1.5rem -2rem -0.5rem;
			padding: 1rem 1.5rem 2rem;
			border-radius: var(--radius-l) 0 0 0;
		}
	}
	.backdrop {
		position: absolute;
		inset: 0 0 auto;
		height: min(60vh, 32rem);
		z-index: -1;
		overflow: hidden;
		border-radius: inherit;
	}
	.backdrop img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		opacity: 0.35;
		animation: fade 400ms ease-out;
	}
	.backdrop::after {
		content: '';
		position: absolute;
		inset: 0;
		background: linear-gradient(to bottom, transparent 20%, var(--m3c-surface));
	}
	@keyframes fade {
		from {
			opacity: 0;
		}
	}
	.top {
		margin-left: -0.5rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error {
		color: var(--m3c-error);
	}
	.hero {
		display: flex;
		gap: 1.25rem;
		align-items: flex-end;
		margin: 0.5rem 0 1.25rem;
	}
	.poster :global(.poster) {
		box-shadow: 0 6px 24px rgb(0 0 0 / 0.3);
		border-radius: 1rem;
	}
	.heading {
		min-width: 0;
		flex: 1;
	}
	h1 {
		font-size: clamp(1.5rem, 4vw, 2.5rem);
		line-height: 1.15;
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.tagline {
		margin: 0.25rem 0 0;
		font-style: italic;
		color: var(--m3c-on-surface-variant);
	}
	.meta {
		margin: 0.375rem 0 0;
		color: var(--m3c-on-surface-variant);
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.375rem;
		margin-top: 0.75rem;
	}
	.chip,
	.schip {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		height: 1.75rem;
		padding: 0 0.625rem;
		border-radius: 0.5rem;
		font-size: 0.8125rem;
		font-weight: 500;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
		white-space: nowrap;
	}
	.schip {
		height: 1.375rem;
		padding: 0 0.5rem;
		font-size: 0.75rem;
	}
	.subtle,
	.status-none {
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
		color: var(--m3c-on-surface-variant);
	}
	.status-available {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
	}
	.status-processing {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.status-pending {
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface);
		box-shadow: inset 0 0 0 1px var(--m3c-outline);
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 1rem;
	}
	.hint {
		margin: 0.5rem 0 0;
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	/* Phones: small poster beside the title, so the overview is visible without scrolling. */
	@media (width < 34rem) {
		.hero {
			align-items: flex-start;
			gap: 1rem;
			margin-top: 0.25rem;
		}
		.poster :global(.poster) {
			width: 112px !important;
			height: 168px !important;
		}
		h1 {
			font-size: 1.375rem;
		}
	}
	.card {
		padding: 1.25rem;
		border-radius: var(--radius-l);
		background: color-mix(in oklab, var(--m3c-surface-container-low) 92%, transparent);
		margin-bottom: 1rem;
		min-width: 0;
	}
	.overview {
		margin: 0;
		line-height: 1.5;
	}
	.overview.clamp {
		display: -webkit-box;
		-webkit-line-clamp: 4;
		line-clamp: 4;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.more {
		all: unset;
		cursor: pointer;
		color: var(--m3c-primary);
		font-weight: 500;
		margin-top: 0.25rem;
	}
	.genres {
		margin: 0.75rem 0 0;
		font-size: 0.875rem;
		color: var(--m3c-on-surface-variant);
	}
	.cols {
		display: grid;
		/* minmax(0, …): without it the cast carousel's full width becomes the column width on phones. */
		grid-template-columns: minmax(0, 1fr);
		gap: 0 1rem;
	}
	@media (width >= 60rem) {
		.cols {
			grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
			align-items: start;
		}
	}
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 0.5rem 1rem;
		margin: 0.75rem 0 0;
		font-size: 0.875rem;
	}
	dt {
		color: var(--m3c-on-surface-variant);
	}
	dd {
		margin: 0;
		overflow-wrap: anywhere;
	}
	.seasons {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.375rem;
	}
	.seasons li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.375rem;
	}
	.seasons li > span:first-child {
		min-width: 4.5rem;
	}
	.links {
		display: flex;
		gap: 1rem;
		margin: 1rem 0 0;
		font-weight: 500;
	}
	.reqs {
		list-style: none;
		margin: 0.5rem 0 0;
		padding: 0;
	}
	.reqs li {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		padding: 0.375rem 0;
	}
	.rtext {
		flex: 1;
		min-width: 0;
		display: grid;
	}
	.rtitle {
		font-size: 0.875rem;
		font-weight: 500;
	}
	.rsub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.cast {
		list-style: none;
		margin: 0.75rem 0 0;
		padding: 0 0 0.25rem;
		display: flex;
		gap: 0.75rem;
		overflow-x: auto;
		scroll-snap-type: x mandatory;
		scrollbar-width: thin;
	}
	.cast li {
		flex: none;
		width: 5.5rem;
		display: grid;
		justify-items: center;
		text-align: center;
		scroll-snap-align: start;
	}
	.face {
		width: 4.5rem;
		height: 4.5rem;
		border-radius: 50%;
		overflow: hidden;
		display: grid;
		place-items: center;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.face img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.name,
	.role {
		width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.75rem;
	}
	.name {
		margin-top: 0.375rem;
		font-weight: 500;
	}
	.role {
		color: var(--m3c-on-surface-variant);
	}
</style>
