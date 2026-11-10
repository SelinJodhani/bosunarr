<script lang="ts">
	import { page } from '$app/state';
	import { Button, Icon, snackbar } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iBack, iCheck, iDecline, iDelete, iLibrary, iOpen, iPerson, iRequest, iStar } from '$lib/icons';
	import { AVAILABILITY_LABEL, AVAILABILITY_TONE, REQUEST_LABEL, REQUEST_TONE, cachedSeed, copyLabel, headline, peek, post, resolveSeed } from './client';
	import RequestDialog from './RequestDialog.svelte';
	import RequestConfirm from './RequestConfirm.svelte';
	import StatusBadge from './StatusBadge.svelte';
	import type { ConfirmTarget, DiscoverDetail, DiscoverItem, RequestAction, RequestBrief } from './types';

	let { service, type, id }: { service: string; type: string; id: string } = $props();

	// The route re-mounts this component per item ({#key}), so props are fixed for its lifetime.
	// svelte-ignore state_referenced_locally
	const q = live<DiscoverDetail>(`/api/discover/${service}/${type}/${id}`, 60_000);
	// svelte-ignore state_referenced_locally
	const peeked: DiscoverItem | undefined = peek(service, type, id);

	const d = $derived(q.state.data);
	const item = $derived<DiscoverItem | undefined>(d ?? peeked);
	const isAdmin = $derived(Boolean(page.data.user?.isAdmin));

	/* Poster-derived theme, without the purple-then-recolour flash: a seed computed earlier this
	   session (lists warm it on hover / touch) paints on the first frame; otherwise the page stays
	   invisible until the seed is ready (or 350 ms pass) and then fades in. */
	const posterSrc = $derived(item?.poster ?? null);
	// svelte-ignore state_referenced_locally
	const initial = cachedSeed(posterSrc);
	let seed = $state<string | null>(initial ?? null);
	// svelte-ignore state_referenced_locally
	let ready = $state(initial !== undefined && Boolean(item));
	$effect(() => {
		const src = posterSrc;
		if (!src) {
			if (item) ready = true;
			return;
		}
		const hit = cachedSeed(src);
		if (hit !== undefined) {
			seed = hit;
			ready = true;
			return;
		}
		const timer = setTimeout(() => (ready = true), 350);
		resolveSeed(src).then((s) => {
			if (src !== posterSrc) return;
			seed = s;
			ready = true;
		});
		return () => clearTimeout(timer);
	});
	// No poster info at all yet (cold deep link): show the skeleton rather than a blank page.
	$effect(() => {
		if (q.state.error) ready = true;
		const t = setTimeout(() => (ready = true), 600);
		return () => clearTimeout(t);
	});

	let showAllOverview = $state(false);
	/** Only offer "Show more" when the clamp actually hides text. */
	let overviewEl = $state<HTMLElement>();
	let overviewClamped = $state(false);
	$effect(() => {
		const el = overviewEl;
		if (!el) return;
		const check = () => (overviewClamped = showAllOverview || el.scrollHeight > el.clientHeight + 1);
		check();
		const ro = new ResizeObserver(check);
		ro.observe(el);
		return () => ro.disconnect();
	});
	let requestOpen = $state(false);
	let requestUhd = $state<boolean | undefined>(undefined);
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

	/** One chip per copy that has a status, the further-along copy first ("Available in 4K", "HD · Requested"). */
	// From the list item too, so the chips are there on the first frame (no shift when details arrive).
	const copies = $derived.by(() => {
		if (!item) return [];
		const both = item.status !== 'none' && item.status4k !== 'none';
		const out = [
			{ uhd: false, status: item.status },
			{ uhd: true, status: item.status4k }
		].filter((c) => c.status !== 'none' && AVAILABILITY_TONE[c.status]);
		if (headline(item.status, item.status4k).uhd) out.reverse();
		return out.map((c) => ({ uhd: c.uhd, tone: AVAILABILITY_TONE[c.status]!, label: copyLabel(c.status, c.uhd, both) }));
	});

	const skeletonButtons = $derived.by(() => {
		if (!item) return ['8rem', '6rem'];
		const w: string[] = [];
		if (isAdmin && (item.status === 'none' || item.mediaType === 'tv')) w.push('7.25rem'); // Request
		if (item.status === 'available' || item.status === 'partial' || item.status4k === 'available' || item.status4k === 'partial') w.push('7.75rem'); // In Library
		w.push('9.5rem'); // Open in Seerr
		return w;
	});

	/** The request buttons: explicit about what each one asks for. */
	const requestButtons = $derived.by(() => {
		if (!d || !isAdmin) return [] as Array<{ label: string; uhd?: boolean }>;
		if (d.mediaType === 'movie') {
			const reg = d.status === 'none';
			const uhd = d.can4k && d.status4k === 'none';
			if (reg) return [{ label: d.status4k !== 'none' ? 'Request HD' : 'Request', uhd: false }];
			if (uhd) return [{ label: 'Request 4K', uhd: true }];
			return [];
		}
		const open = d.seasons.filter((s) => s.status === 'none');
		const open4k = d.can4k ? d.seasons.filter((s) => s.status4k === 'none') : [];
		if (open.length) return [{ label: open.length < d.seasons.length ? 'Request more seasons' : 'Request' }];
		if (open4k.length) return [{ label: 'Request 4K', uhd: true }];
		return [];
	});
	const somethingOpen = $derived.by(() => {
		if (!d) return false;
		if (d.mediaType === 'movie') return d.status === 'none' || (d.can4k && d.status4k === 'none');
		return d.seasons.some((s) => s.status === 'none' || (d.can4k && s.status4k === 'none'));
	});

	const fmtDate = (iso: string | null) => (iso ? new Date(iso + (iso.length === 10 ? 'T00:00:00' : '')).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
	const serviceOf = (l: { serviceName: string }) => l.serviceName;

	async function act(r: RequestBrief, action: RequestAction['action']) {
		if (!d) return null;
		busy = r.id;
		const err = await post('/api/discover/requests/action', { serviceId: d.serviceId, requestId: r.id, action } satisfies RequestAction);
		busy = null;
		if (!err) {
			snackbar({ approve: 'Request approved', decline: 'Request declined', delete: 'Request deleted' }[action], {}, true);
			q.refresh();
		}
		return err;
	}

	/* Decline / Delete go through a confirmation, like on the Requests tab. */
	let confirmOpen = $state(false);
	let confirmFor = $state<{ r: RequestBrief; target: ConfirmTarget } | null>(null);
	let confirmError = $state<string | null>(null);
	function ask(r: RequestBrief, action: 'decline' | 'delete') {
		if (!d) return;
		confirmFor = { r, target: { action, title: d.title, is4k: r.is4k, requestedBy: r.requestedBy, serviceName: d.serviceName } };
		confirmError = null;
		confirmOpen = true;
	}
	async function confirmed() {
		if (!confirmFor) return;
		const err = await act(confirmFor.r, confirmFor.target.action);
		if (err) confirmError = err;
		else confirmOpen = false;
	}
	async function approve(r: RequestBrief) {
		const err = await act(r, 'approve');
		if (err) snackbar(err, {}, true);
	}

	function openRequest(uhd?: boolean) {
		requestUhd = uhd;
		requestOpen = true;
	}

	const back = () => (history.length > 1 ? history.back() : (location.href = '/discover'));
</script>

<svelte:head><title>{item?.title ?? 'Discover'} · Bosunarr</title></svelte:head>

{#snippet actions()}
	{#if !d}
		<div class="actions">
			{#each copies as c (c.uhd)}
				<StatusBadge size="m" tone={c.tone} label={c.label} />
			{/each}
			<!-- Placeholders the width of the buttons that will most likely appear, so the row wraps
			     where the real one will. -->
			{#each skeletonButtons as w, i (i)}<span class="sk sk-btn" style:width={w} aria-hidden="true"></span>{/each}
		</div>
	{:else}
		<div class="actions">
			{#each copies as c (c.uhd)}
				<StatusBadge size="m" tone={c.tone} label={c.label} />
			{/each}
			{#each requestButtons as b (b.label)}
				<Button variant="filled" onclick={() => openRequest(b.uhd)}><Icon icon={iRequest} /> {b.label}</Button>
			{/each}
			{#each d.inLibrary as l (l.href)}
				<Button variant="tonal" href={l.href}><Icon icon={iLibrary} /> In Library{d.inLibrary.length > 1 ? ` · ${serviceOf(l)}` : ''}</Button>
			{/each}
			{#if d.openUrl}
				<Button variant="tonal" href={d.openUrl} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} /> Open in {d.serviceName}</Button>
			{/if}
		</div>
		{#if !isAdmin && somethingOpen}<p class="hint">Requests from Bosunarr are admin-only for now.</p>{/if}
	{/if}
{/snippet}

<div class="m3-theme themed" class:ready style:--m3v-source={seed ?? undefined}>
	{#if d?.backdrop}
		<div class="backdrop" aria-hidden="true">
			<img src={d.backdrop} alt="" decoding="async" />
		</div>
	{/if}

	<div class="top">
		<Button variant="text" iconType="full" aria-label="Back" onclick={back}><Icon icon={iBack} /></Button>
	</div>

	{#if !item && q.state.error}
		<p class="error" role="alert">{q.state.error}</p>
	{:else}
		<section class="hero" aria-busy={!d}>
			<div class="poster">
				{#if item}
					<Poster src={item.poster} alt={`${item.title} poster`} width={180} />
				{:else}
					<span class="sk sk-poster"></span>
				{/if}
			</div>
			<div class="heading">
				{#if item}
					<h1>{item.title}</h1>
				{:else}
					<span class="sk sk-h1"></span>
				{/if}
				<!-- Meta: until details arrive, placeholders the size of what's still coming (runtime,
				     rating, seasons) keep the line breaks — and the chips below — where they'll end up. -->
				{#if meta.length}<p class="meta">
						{#each meta as m, i (i)}{#if i}{' · '}{/if}<span>{m}</span>{/each}{#if !d}{#each item?.mediaType === 'tv' ? ['2rem', '2.5rem', '4.5rem'] : ['3.25rem', '2.25rem'] as w, i (i)}{' · '}<span
									class="sk sk-inline"
									style:width={w}
									aria-hidden="true"
								></span>{/each}{/if}
					</p>{/if}
				<div class="chips">
					{#if d}
						{#each d.ratings as r (r.source)}
							<span class="chip" title={r.source === 'rt' || r.source === 'rtAudience' ? 'Rotten Tomatoes' : r.label}>
								{#if r.source === 'tmdb' || r.source === 'imdb'}<Icon icon={iStar} size={16} />{/if}
								{r.label} {r.value}
							</span>
						{/each}
					{:else}
						{#if item?.rating}<span class="chip"><Icon icon={iStar} size={16} /> TMDb {item.rating}</span>{/if}
						<span class="sk sk-chip"></span><span class="sk sk-chip"></span>
					{/if}
				</div>
				<div class="wide-only">{@render actions()}</div>
			</div>
		</section>
		<div class="phone-only">{@render actions()}</div>

		{#if !d}
			{#if q.state.error}
				<p class="error" role="alert">{q.state.error}</p>
			{:else}
				<div class="card" aria-hidden="true">
					<span class="sk sk-line wide"></span><span class="sk sk-line wide"></span><span class="sk sk-line wide"></span><span class="sk sk-line"></span>
				</div>
				<div class="card" aria-hidden="true">
					<span class="sk sk-line"></span><span class="sk sk-line wide"></span><span class="sk sk-line wide"></span>
				</div>
			{/if}
		{:else}
			{#if d.overview || d.tagline}
				<section class="card">
					{#if d.tagline}<p class="tagline">{d.tagline}</p>{/if}
					{#if d.overview}
						<p class="overview" class:clamp={!showAllOverview} bind:this={overviewEl}>{d.overview}</p>
						{#if overviewClamped}
							<Button variant="text" onclick={() => (showAllOverview = !showAllOverview)} aria-expanded={showAllOverview}>{showAllOverview ? 'Show less' : 'Show more'}</Button>
						{/if}
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
										{@const tone = AVAILABILITY_TONE[s.status]}
										{@const tone4k = AVAILABILITY_TONE[s.status4k]}
										<li>
											<span class="sname">{s.name}</span>
											{#if tone}<StatusBadge {tone} label={AVAILABILITY_LABEL[s.status]} />{/if}
											{#if tone4k}<StatusBadge tone={tone4k} label={copyLabel(s.status4k, true)} />{/if}
										</li>
									{/each}
								</ul>
							</dd>
						{/if}
					</dl>
					{#if d.imdbId || d.tmdbId || d.tvdbId}
						<div class="links">
							{#if d.imdbId}<Button variant="outlined" href={`https://www.imdb.com/title/${d.imdbId}/`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />IMDb</Button>{/if}
							<Button variant="outlined" href={`https://www.themoviedb.org/${d.mediaType}/${d.tmdbId}`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />TMDb</Button>
							{#if d.tvdbId}<Button variant="outlined" href={`https://thetvdb.com/?tab=series&id=${d.tvdbId}`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />TVDB</Button>{/if}
						</div>
					{/if}
				</section>

				<div>
					{#if d.requests.length}
						<section class="card">
							<h2 class="title-medium">Requests</h2>
							<ul class="reqs">
								{#each d.requests as r (r.id)}
									<li>
										<div class="rhead">
											<StatusBadge tone={REQUEST_TONE[r.status]} label={REQUEST_LABEL[r.status]} />
											{#if r.is4k}<StatusBadge tone="partial" label="4K" />{/if}
											{#if r.seasons.length}<span class="rseasons">{r.seasons.map((n) => `S${n}`).join(', ')}</span>{/if}
										</div>
										<span class="rsub">{r.requestedBy} · {ago(r.createdAt)}</span>
										{#if isAdmin}
											<div class="racts">
												{#if r.status === 'pending'}
													<Button variant="text" data-danger disabled={busy === r.id} onclick={() => ask(r, 'decline')}><Icon icon={iDecline} /> Decline</Button>
													<Button variant="tonal" disabled={busy === r.id} onclick={() => approve(r)}><Icon icon={iCheck} /> Approve</Button>
												{:else}
													<Button variant="text" data-danger disabled={busy === r.id} onclick={() => ask(r, 'delete')}><Icon icon={iDelete} /> Delete</Button>
												{/if}
											</div>
										{/if}
									</li>
								{/each}
							</ul>
						</section>
					{/if}

					{#if d.cast.length}
						<section class="card cast-card">
							<h2 class="title-medium">Cast</h2>
							<ul class="cast no-scrollbar">
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

			{#if isAdmin}
				<RequestDialog bind:open={requestOpen} detail={d} uhd={requestUhd} ondone={() => q.refresh()} />
				<RequestConfirm bind:open={confirmOpen} target={confirmFor?.target ?? null} busy={busy !== null} error={confirmError} onconfirm={confirmed} />
			{/if}
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
		opacity: 0;
		transition:
			background-color 300ms,
			opacity 200ms ease-out;
	}
	.themed.ready {
		opacity: 1;
	}
	@media (prefers-reduced-motion: reduce) {
		.themed {
			transition: none;
		}
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
		margin: 0 0 0.5rem;
		font-style: italic;
		color: var(--m3c-on-surface-variant);
	}
	.meta {
		margin: 0.375rem 0 0;
		color: var(--m3c-on-surface-variant);
	}
	/* Wrap between items, never inside one ("PG-13", "1h 33m"). */
	.meta span {
		white-space: nowrap;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.375rem;
		margin-top: 0.75rem;
	}
	.chip {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		height: 1.75rem;
		padding: 0 0.625rem;
		border-radius: 0.5rem;
		font-size: 0.8125rem;
		font-weight: 500;
		white-space: nowrap;
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
		color: var(--m3c-on-surface-variant);
	}
	/* Skeletons reserve the space real content will take, so nothing jumps when details arrive. */
	.sk {
		display: block;
		border-radius: 0.5rem;
		background: var(--m3c-surface-container-highest);
		animation: pulse 1.2s ease-in-out infinite alternate;
	}
	@keyframes pulse {
		to {
			opacity: 0.5;
		}
	}
	.sk-poster {
		width: 180px;
		height: 270px;
		border-radius: 1rem;
	}
	.sk-h1 {
		height: 1.75rem;
		width: 70%;
	}
	.sk-line {
		height: 0.875rem;
		width: 55%;
		margin: 0.5rem 0;
	}
	.sk-line.wide {
		width: 100%;
	}
	.sk-inline {
		display: inline-block;
		height: 0.875rem;
		vertical-align: -0.0625rem;
		border-radius: 0.375rem;
	}
	.sk-chip {
		display: inline-block;
		height: 1.75rem;
		width: 4.5rem;
	}
	.sk-btn {
		display: inline-block;
		height: 2.5rem;
		width: 8rem;
		border-radius: 1.25rem;
	}
	.phone-only {
		display: none;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem;
		margin-top: 1rem;
		min-height: 2.5rem;
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
		.poster :global(.poster),
		.sk-poster {
			width: 112px !important;
			height: 168px !important;
		}
		h1 {
			font-size: 1.375rem;
		}
		/* Actions get the full width below the poster instead of a cramped column beside it. */
		.wide-only {
			display: none;
		}
		.phone-only {
			display: block;
			margin: -0.25rem 0 1.25rem;
		}
		.phone-only .actions {
			margin-top: 0;
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
	/* "Show more": a text button whose label lines up with the overview's first letter. */
	.overview + :global(button) {
		margin: 0.25rem 0 0 -1rem;
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
	.sname {
		min-width: 4.5rem;
	}
	.links {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin: 1rem 0 0;
	}
	.reqs {
		list-style: none;
		margin: 0.5rem 0 0;
		padding: 0;
	}
	.reqs li {
		display: grid;
		gap: 0.25rem;
		padding: 0.625rem 0;
	}
	.reqs li + li {
		border-top: 1px solid var(--m3c-outline-variant);
	}
	.rhead {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.375rem;
	}
	.rseasons {
		font-size: 0.8125rem;
		font-weight: 500;
	}
	.rsub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.racts {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		margin-right: -0.5rem;
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
	/* app.css's [data-danger] ties with m3-svelte's own variant rules on specificity and loses on
	   load order; repeat it with the scope class until the shared rule is fixed. */
	.racts :global(button.m3-container.text[data-danger]:not(:disabled)) {
		color: var(--m3c-error);
	}
</style>
