<script lang="ts">
	import { Button, Icon, LinearProgress, LoadingIndicator } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { bytes, dayLabel } from '$lib/format';
	import { peekItem, posterSeed, STATUS_LABEL } from '$lib/library/client';
	import type { LibraryDetail, LibraryItem, Season } from '$lib/library/types';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iBack, iExpand, iMonitored, iOk, iOpen, iPerson, iStar, iUnmonitored } from '$lib/icons';

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

	/* Poster-derived theme */
	let seed = $state<string | null>(null);
	const posterSrc = $derived(item?.poster ?? null);
	$effect(() => {
		const src = posterSrc;
		if (src) posterSeed(src).then((s) => src === posterSrc && (seed = s));
	});

	let showAllOverview = $state(false);
	let open = $state<Record<number, boolean>>({});

	const meta = $derived.by(() => {
		if (!item) return [];
		const m: string[] = [];
		if (item.year) m.push(String(item.year));
		if (d?.runtime) m.push(d.runtime >= 60 ? `${Math.floor(d.runtime / 60)}h ${d.runtime % 60}m` : `${d.runtime}m`);
		if (d?.certification) m.push(d.certification);
		if (item.network) m.push(item.network);
		if (item.kind === 'series' && d?.seasons.length) {
			const n = d.seasons.filter((s) => s.number > 0).length;
			m.push(`${n} season${n === 1 ? '' : 's'}`);
		}
		return m;
	});

	const seasonPct = (s: Season) => (s.total ? Math.round((s.have / s.total) * 100) : s.have ? 100 : 0);
	const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
	const serviceLabel = $derived(item?.kind === 'movie' ? 'Radarr' : 'Sonarr');
</script>

<svelte:head><title>{item?.title ?? 'Library'} · Bosunarr</title></svelte:head>

<div class="m3-theme themed" style:--m3v-source={seed ?? undefined}>
	{#if d?.fanart}
		<div class="backdrop" aria-hidden="true">
			<img src={d.fanart} alt="" decoding="async" />
		</div>
	{/if}

	<div class="top">
		<Button variant="text" iconType="full" aria-label="Back to library" onclick={() => (history.length > 1 ? history.back() : (location.href = '/library'))}>
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
				<div class="chips">
					<span class="chip status-{item.status}">{STATUS_LABEL[item.status]}</span>
					{#if item.rating}<span class="chip"><Icon icon={iStar} size={16} /> {item.rating}</span>{/if}
					{#if item.qualityProfile}<span class="chip">{item.qualityProfile}</span>{/if}
					{#if item.is4k}<span class="chip">4K</span>{/if}
					<span class="chip"><Icon icon={item.monitored ? iMonitored : iUnmonitored} size={16} /> {item.monitored ? 'Monitored' : 'Unmonitored'}</span>
					<span class="chip subtle">{item.serviceName}</span>
				</div>
				{#if d?.openUrl}
					<div class="actions">
						<Button variant="tonal" href={d.openUrl} target="_blank" rel="noopener noreferrer">
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
					<p class="overview" class:clamp={!showAllOverview}>{d.overview}</p>
					{#if d.overview.length > 280}
						<button class="more" onclick={() => (showAllOverview = !showAllOverview)}>{showAllOverview ? 'Less' : 'More'}</button>
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
						<p class="links">
							{#if d.imdbId}<a href={`https://www.imdb.com/title/${d.imdbId}/`} target="_blank" rel="noopener noreferrer">IMDb</a>{/if}
							{#if d.tmdbId}<a href={`https://www.themoviedb.org/${d.kind === 'movie' ? 'movie' : 'tv'}/${d.tmdbId}`} target="_blank" rel="noopener noreferrer">TMDb</a>{/if}
							{#if d.tvdbId}<a href={`https://thetvdb.com/?tab=series&id=${d.tvdbId}`} target="_blank" rel="noopener noreferrer">TVDB</a>{/if}
						</p>
					{/if}
				</section>

				{#if d.cast.length}
					<section class="card cast-card">
						<h2 class="title-medium">Cast</h2>
						<ul class="cast">
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

			{#if d.seasons.length}
				<section class="seasons">
					<h2 class="title-medium">Seasons</h2>
					{#each d.seasons as s (s.number)}
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
										<li class:missing={!e.hasFile && e.monitored && e.airDate && new Date(e.airDate) < new Date()}>
											<span class="enum">{e.number}</span>
											<span class="etext">
												<span class="etitle">{e.title}</span>
												<span class="esub">
													{e.airDate ? fmtDate(e.airDate) : 'TBA'}{e.quality ? ` · ${e.quality}` : ''}{e.size ? ` · ${bytes(e.size, 0)}` : ''}
												</span>
											</span>
											{#if e.hasFile}
												<span class="have" title="Downloaded"><Icon icon={iOk} /></span>
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
	.chip {
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
	}
	.chip.subtle {
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
		color: var(--m3c-on-surface-variant);
	}
	.chip.status-downloaded {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
	}
	.chip.status-missing,
	.chip.status-partial {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.chip.status-upcoming {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.actions {
		margin-top: 1rem;
	}
	@media (width < 34rem) {
		.hero {
			flex-direction: column;
			align-items: flex-start;
		}
		.poster :global(.poster) {
			width: 140px !important;
			height: 210px !important;
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
		gap: 1rem;
		margin: 1rem 0 0;
		font-weight: 500;
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
</style>
