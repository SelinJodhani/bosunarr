<script lang="ts">
	import { Button, Icon, LinearProgress, LoadingIndicator } from 'm3-svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { afterNavigate, goto } from '$app/navigation';
	import { live } from '$lib/live.svelte';
	import { bytes, dayLabel } from '$lib/format';
	import { backLabel, cachedSeed, fadeIn, peekItem, posterSeed, STATUS_LABEL } from '$lib/library/client';
	import type { LibraryDetail, LibraryItem, LibraryStatus, Season } from '$lib/library/types';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iBack, iError, iExpand, iMonitored, iOk, iOpen, iPerson, iSchedule, iStar, iUnmonitored, iWarning } from '$lib/icons';

	let { service, kind, id }: { service: string; kind: string; id: string } = $props();

	// The page re-mounts this component per item ({#key}), so props are fixed for its lifetime.
	// svelte-ignore state_referenced_locally
	const q = live<LibraryDetail>(`/api/library/${service}/${kind}/${id}`, 60_000);
	let peek = $state<LibraryItem | undefined>();
	$effect(() => {
		peek = peekItem(`${service}-${kind}-${id}`);
	});

	const d = $derived(q.state.data);
	const item = $derived<LibraryItem | undefined>(d ?? peek);

	/* Poster-derived theme. A cached seed (primed when the card was touched) applies on the first frame;
	   only a seed that has to be computed now arrives late, and then cross-fades in. */
	const posterSrc = $derived(item?.poster ?? null);
	let late = $state<{ src: string; seed: string | null } | null>(null);
	const seed = $derived(posterSrc ? (late?.src === posterSrc ? late.seed : cachedSeed(posterSrc)) : null);
	$effect(() => {
		const src = posterSrc;
		if (!src || cachedSeed(src) !== undefined) return;
		posterSeed(src).then((s) => src === posterSrc && fadeIn(() => (late = { src, seed: s })));
	});

	/* Back button: say where it goes (Calendar, Discover …) — history.back() returns there. */
	let from = $state<string | null>(null);
	afterNavigate((n) => {
		if (n.from && n.from.url.pathname !== n.to?.url.pathname) from = n.from.url.pathname;
	});
	const goBack = () => (from && history.length > 1 ? history.back() : goto('/library'));

	/* Backdrop fades in once decoded (no progressive-JPEG band over the chips). */
	let fanartReady = $state(false);

	/* Overview "More" only when the clamp actually hides text. */
	let overviewEl = $state<HTMLElement>();
	let overviewClipped = $state(false);
	$effect(() => {
		const el = overviewEl;
		if (!el) return;
		const check = () => !showAllOverview && (overviewClipped = el.scrollHeight > el.clientHeight + 1);
		check();
		const ro = new ResizeObserver(check);
		ro.observe(el);
		return () => ro.disconnect();
	});

	const STATUS_ICON: Record<LibraryStatus, { icon: IconifyIcon; tone: string }> = {
		downloaded: { icon: iOk, tone: 'primary' },
		partial: { icon: iWarning, tone: 'error' },
		missing: { icon: iError, tone: 'error' },
		upcoming: { icon: iSchedule, tone: 'tertiary' },
		unmonitored: { icon: iUnmonitored, tone: 'muted' }
	};
	const UHD = /2160|4k|uhd|ultra[\s-]?hd/i;
	const isPast = (iso: string | null) => !!iso && new Date(iso) < new Date();
	const nb = (t: string) => t.replace(/ /g, '\u00a0');
	const visibleSeasons = $derived((d?.seasons ?? []).filter((s) => !(s.number === 0 && s.total === 0 && s.have === 0)));

	let showAllOverview = $state(false);
	let open = $state<Record<number, boolean>>({});

	const meta = $derived.by(() => {
		if (!item) return [];
		const m: string[] = [];
		if (item.year) m.push(String(item.year));
		if (d?.runtime) {
			const rt = d.runtime >= 60 ? `${Math.floor(d.runtime / 60)}h ${d.runtime % 60}m` : `${d.runtime}m`;
			m.push(item.kind === 'series' ? `~${rt} / episode` : rt);
		}
		if (d?.certification) m.push(d.certification);
		if (item.network) m.push(item.network);
		if (item.kind === 'series' && d?.seasons.length) {
			const n = d.seasons.filter((s) => s.number > 0).length;
			m.push(`${n} season${n === 1 ? '' : 's'}`);
		}
		return m.map(nb);
	});

	const seasonPct = (s: Season) => (s.total ? Math.round((s.have / s.total) * 100) : s.have ? 100 : 0);
	const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
	const serviceLabel = $derived(item?.kind === 'movie' ? 'Radarr' : 'Sonarr');
</script>

<svelte:head><title>{item?.title ?? 'Library'} · Bosunarr</title></svelte:head>

<div class="m3-theme themed" style:--m3v-source={seed ?? undefined}>
	{#if d?.fanart}
		<div class="backdrop" aria-hidden="true">
			<img src={d.fanart} alt="" decoding="async" class:ready={fanartReady} onload={() => (fanartReady = true)} />
		</div>
	{/if}

	<div class="top">
		<Button variant="text" iconType="full" aria-label={backLabel(from)} title={backLabel(from)} onclick={goBack}>
			<Icon icon={iBack} />
		</Button>
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
				{#if meta.length}<p class="meta">{meta.join(' · ')}</p>{/if}
				<ul class="chips" aria-label="Status">
					<li class="chip tone-{STATUS_ICON[item.status].tone}"><Icon icon={STATUS_ICON[item.status].icon} size={18} />{STATUS_LABEL[item.status]}</li>
					{#if item.rating}
						<li class="chip tone-star" title={`Rating from ${item.ratingSource ?? item.serviceName}`}>
							<Icon icon={iStar} size={18} />{item.rating}{#if item.ratingSource}<span class="src">{item.ratingSource}</span>{/if}
						</li>
					{/if}
					{#if item.qualityProfile}<li class="chip">{item.qualityProfile}</li>{/if}
					{#if item.is4k && !UHD.test(item.qualityProfile ?? '')}<li class="chip">4K</li>{/if}
					<li class="chip"><Icon icon={item.monitored ? iMonitored : iUnmonitored} size={18} />{item.monitored ? 'Monitored' : 'Unmonitored'}</li>
					<li class="chip">{item.serviceName}</li>
				</ul>
				<!-- Space is held while details load so nothing below jumps when the button appears. -->
				{#if !d || d.openUrl}
					<div class="actions" class:pending={!d}>
						<Button variant="tonal" href={d?.openUrl ?? '#'} target="_blank" rel="noopener noreferrer" tabindex={d ? undefined : -1} aria-hidden={d ? undefined : 'true'}>
							<Icon icon={iOpen} /> Open in {serviceLabel}
						</Button>
					</div>
				{/if}
			</div>
		</section>

		{#if !d}
			<div class="center"><LoadingIndicator aria-label="Loading details" /></div>
		{:else}
			{#if d.overview}
				<section class="card">
					<p class="overview" class:clamp={!showAllOverview} bind:this={overviewEl}>{d.overview}</p>
					{#if overviewClipped || showAllOverview}
						<Button variant="text" onclick={() => (showAllOverview = !showAllOverview)} aria-expanded={showAllOverview}>{showAllOverview ? 'Less' : 'More'}</Button>
					{/if}
					{#if d.genres.length}<p class="genres">{d.genres.join(' · ')}</p>{/if}
				</section>
			{/if}

			<div class="cols">
				<section class="card info">
					<h2 class="title-medium">{d.kind === 'movie' ? 'File' : 'Details'}</h2>
					<dl>
						{#if d.kind === 'movie'}
							{#if d.file}
								<dt>Quality</dt><dd>{d.file.quality ?? '—'}</dd>
								{#if d.file.resolution}<dt>Resolution</dt><dd>{d.file.resolution}</dd>{/if}
								{#if d.file.videoCodec}<dt>Video</dt><dd>{d.file.videoCodec}</dd>{/if}
								{#if d.file.audio}<dt>Audio</dt><dd>{d.file.audio}</dd>{/if}
								{#if d.file.languages.length}<dt>Languages</dt><dd>{d.file.languages.join(', ')}</dd>{/if}
								<dt>Size</dt><dd>{bytes(d.file.size)}</dd>
								{#if d.file.releaseGroup}<dt>Group</dt><dd>{d.file.releaseGroup}</dd>{/if}
								<dt>Added</dt><dd>{fmtDate(d.file.dateAdded)}</dd>
								<dt>Path</dt><dd class="path">{d.file.path}</dd>
							{:else}
								<dt>File</dt><dd>None on disk</dd>
								{#if d.inCinemas}<dt>In cinemas</dt><dd>{fmtDate(d.inCinemas)}</dd>{/if}
								{#if d.digitalRelease}<dt>Digital</dt><dd>{fmtDate(d.digitalRelease)}</dd>{/if}
								{#if d.path}<dt>Path</dt><dd class="path">{d.path}</dd>{/if}
							{/if}
						{:else}
							<dt>Episodes</dt><dd>{d.episodes?.have ?? 0} of {d.episodes?.total ?? 0}</dd>
							<dt>Size</dt><dd>{bytes(d.sizeOnDisk)}</dd>
							<dt>Status</dt><dd>{d.continuing ? 'Continuing' : 'Ended'}</dd>
							{#if d.nextAiring}<dt>Next airing</dt><dd>{dayLabel(d.nextAiring)}</dd>{/if}
							{#if d.path}<dt>Path</dt><dd class="path">{d.path}</dd>{/if}
						{/if}
						{#if d.tags.length}<dt>Tags</dt><dd>{d.tags.join(', ')}</dd>{/if}
					</dl>
					{#if d.imdbId || d.tmdbId || d.tvdbId}
						<div class="links">
							{#if d.imdbId}<Button variant="outlined" href={`https://www.imdb.com/title/${d.imdbId}/`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />IMDb</Button>{/if}
							{#if d.tmdbId}<Button variant="outlined" href={`https://www.themoviedb.org/${d.kind === 'movie' ? 'movie' : 'tv'}/${d.tmdbId}`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />TMDb</Button>{/if}
							{#if d.tvdbId}<Button variant="outlined" href={`https://thetvdb.com/?tab=series&id=${d.tvdbId}`} target="_blank" rel="noopener noreferrer"><Icon icon={iOpen} />TVDB</Button>{/if}
						</div>
					{/if}
				</section>

				{#if d.cast.length}
					<section class="card cast-card">
						<h2 class="title-medium">Cast</h2>
						<ul class="cast no-scrollbar">
							{#each d.cast as c (c.name + c.character)}
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

			{#if visibleSeasons.length}
				<section class="seasons">
					<h2 class="title-medium">Seasons</h2>
					{#each visibleSeasons as s (s.number)}
						<div class="season card" class:open={open[s.number]}>
							<button class="season-head" aria-expanded={!!open[s.number]} onclick={() => (open[s.number] = !open[s.number])}>
								<span class="sname">
									{s.number === 0 ? 'Specials' : `Season ${s.number}`}
									{#if !s.monitored}<Icon icon={iUnmonitored} size={16} />{/if}
								</span>
								<span class="scount">{s.have}/{s.total} · {bytes(s.size, 0)}</span>
								<span class="chev"><Icon icon={iExpand} /></span>
								<span class="bar"><LinearProgress percent={seasonPct(s)} aria-label={`Season ${s.number} downloaded`} /></span>
							</button>
							{#if open[s.number]}
								<ol class="episodes">
									{#each s.episodes as e (e.id)}
										{@const missing = !e.hasFile && e.monitored && isPast(e.airDate)}
										<li class:missing>
											<span class="enum">{e.number}</span>
											<span class="etext">
												<span class="etitle">{e.title}</span>
												<span class="esub">
													{e.airDate ? fmtDate(e.airDate) : 'TBA'}{e.quality ? ` · ${e.quality}` : ''}{e.size ? ` · ${bytes(e.size, 0)}` : ''}
												</span>
											</span>
											{#if e.hasFile}
												<span class="have" title="Downloaded"><Icon icon={iOk} /><span class="sr-only">Downloaded</span></span>
											{:else if missing}
												<span class="miss" title="Missing"><Icon icon={iError} /><span class="sr-only">Missing</span></span>
											{:else if !e.monitored}
												<span class="unmon" title="Unmonitored"><Icon icon={iUnmonitored} size={20} /></span>
											{/if}
										</li>
									{/each}
								</ol>
							{/if}
						</div>
					{/each}
				</section>
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
		opacity: 0;
		transition: opacity 400ms ease-out;
	}
	.backdrop img.ready {
		opacity: 0.35;
	}
	.backdrop::after {
		content: '';
		position: absolute;
		inset: 0;
		background: linear-gradient(to bottom, transparent 20%, var(--m3c-surface));
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
	.meta {
		margin: 0.375rem 0 0;
		color: var(--m3c-on-surface-variant);
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin: 0.75rem 0 0;
		padding: 0;
		list-style: none;
	}
	/* One chip style for everything; status is told apart by its coloured icon, not a different chip. */
	.chip {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		height: 2rem;
		padding: 0 0.75rem;
		border-radius: 0.5rem;
		font-size: 0.8125rem;
		font-weight: 500;
		line-height: 1;
		white-space: nowrap;
		background: color-mix(in oklab, var(--m3c-surface-container-low) 80%, transparent);
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
		color: var(--m3c-on-surface);
	}
	.chip :global(svg) {
		margin-left: -0.25rem;
		color: var(--m3c-on-surface-variant);
	}
	.chip.tone-primary :global(svg) {
		color: var(--m3c-primary);
	}
	.chip.tone-error :global(svg) {
		color: var(--m3c-error);
	}
	.chip.tone-tertiary :global(svg),
	.chip.tone-star :global(svg) {
		color: var(--m3c-tertiary);
	}
	.chip .src {
		font-weight: 400;
		color: var(--m3c-on-surface-variant);
	}
	.actions {
		margin-top: 1rem;
	}
	.actions.pending {
		visibility: hidden;
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
	.overview + :global(button) {
		margin: 0.25rem 0 0 -0.75rem;
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
	dd.path {
		font-family: ui-monospace, monospace;
		font-size: 0.75rem;
	}
	.links {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin: 1rem 0 0;
	}
	.cast {
		list-style: none;
		/* Bleeds to the card edge, aligned with the heading at rest. */
		margin: 0.75rem -1.25rem 0;
		padding: 0 1.25rem 0.25rem;
		scroll-padding-inline: 1.25rem;
		display: flex;
		gap: 0.75rem;
		overflow-x: auto;
		scroll-snap-type: x mandatory;
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
	.seasons > h2 {
		margin: 0.5rem 0 0.75rem;
	}
	.season {
		padding: 0;
		margin-bottom: 0.5rem;
		overflow: hidden;
	}
	.season-head {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		width: 100%;
		display: grid;
		grid-template-columns: 1fr auto auto;
		align-items: center;
		gap: 0.25rem 0.75rem;
		padding: 1rem 1.25rem;
	}
	.season-head:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -2px;
		border-radius: var(--radius-l);
	}
	.sname {
		font-weight: 500;
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
	}
	.scount {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
	}
	.chev {
		display: flex;
		transition: transform 200ms;
	}
	.open .chev {
		transform: rotate(180deg);
	}
	.bar {
		grid-column: 1 / -1;
	}
	.episodes {
		list-style: none;
		margin: 0;
		padding: 0 0.75rem 0.75rem;
	}
	.episodes li {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem;
		border-radius: 0.75rem;
	}
	.episodes li.missing .etitle {
		color: var(--m3c-error);
	}
	.enum {
		width: 2rem;
		flex: none;
		text-align: center;
		font-variant-numeric: tabular-nums;
		color: var(--m3c-on-surface-variant);
	}
	.etext {
		flex: 1;
		min-width: 0;
		display: grid;
	}
	.etitle,
	.esub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.etitle {
		font-size: 0.875rem;
		font-weight: 500;
	}
	.esub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.have {
		color: var(--m3c-primary);
		display: flex;
	}
	.unmon {
		color: var(--m3c-outline);
		display: flex;
	}
	.miss {
		color: var(--m3c-error);
		display: flex;
	}
</style>
