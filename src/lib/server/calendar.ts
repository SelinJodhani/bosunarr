import { cached, call, ServiceError } from './services/client';
import { servicesOfType, type ServiceRecord } from './services/store';
import { arrImage, qualityProfiles, UHD } from './library';
import {
	MAX_RANGE_DAYS,
	type CalendarCopy,
	type CalendarEntry,
	type CalendarRelease,
	type CalendarResult,
	type CalendarStatus
} from '$lib/calendar/types';

type ArrImage = { coverType: string; url?: string; remoteUrl?: string };

type SonarrCalendarEpisode = {
	id: number;
	seriesId: number;
	seasonNumber: number;
	episodeNumber: number;
	title?: string;
	airDateUtc?: string;
	hasFile: boolean;
	monitored: boolean;
	runtime?: number;
	finaleType?: 'series' | 'season' | 'midseason';
	series?: {
		title: string;
		year?: number;
		tvdbId?: number;
		network?: string;
		images?: ArrImage[];
		monitored?: boolean;
		qualityProfileId?: number;
		runtime?: number;
	};
};

type RadarrCalendarMovie = {
	id: number;
	title: string;
	year?: number;
	tmdbId?: number;
	studio?: string;
	runtime?: number;
	monitored: boolean;
	hasFile?: boolean;
	isAvailable?: boolean;
	inCinemas?: string;
	digitalRelease?: string;
	physicalRelease?: string;
	images?: ArrImage[];
	qualityProfileId?: number;
	movieFile?: { quality?: { quality?: { resolution?: number } } };
};

const DAY = 864e5;
const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));
const pad = (n: number) => String(n).padStart(2, '0');

/** Parse and validate `start`/`end`; the range is capped so one request can't ask an arr for years of data. */
export function parseRange(startRaw: string | null, endRaw: string | null): { start: Date; end: Date } {
	const iso = /^\d{4}-\d{2}-\d{2}(T[\d:.]+(Z|[+-]\d{2}:?\d{2})?)?$/;
	if (!startRaw || !iso.test(startRaw)) throw new ServiceError('start must be an ISO date', 400);
	const start = new Date(startRaw);
	if (Number.isNaN(+start)) throw new ServiceError('start must be an ISO date', 400);
	let end: Date;
	if (endRaw) {
		if (!iso.test(endRaw)) throw new ServiceError('end must be an ISO date', 400);
		end = new Date(endRaw);
		if (Number.isNaN(+end)) throw new ServiceError('end must be an ISO date', 400);
		if (+end <= +start) throw new ServiceError('end must be after start', 400);
	} else end = new Date(+start + 7 * DAY);
	// A DST change inside the range makes a 6-week grid an hour longer or shorter than 42 × 24 h.
	const max = MAX_RANGE_DAYS * DAY + 2 * 3600_000;
	if (+end - +start > max) end = new Date(+start + MAX_RANGE_DAYS * DAY);
	return { start, end };
}

function copyStatus(hasFile: boolean | undefined, releasedAt: number, available = true): CalendarStatus {
	if (hasFile) return 'downloaded';
	return releasedAt > Date.now() || !available ? 'unaired' : 'missing';
}

/** Downloaded only when every monitored copy is; missing if any is; otherwise still upcoming. */
function combined(copies: CalendarCopy[]): CalendarStatus {
	const relevant = copies.some((c) => c.monitored) ? copies.filter((c) => c.monitored) : copies;
	if (relevant.every((c) => c.status === 'downloaded')) return 'downloaded';
	return relevant.some((c) => c.status === 'missing') ? 'missing' : 'unaired';
}

type Draft = Omit<CalendarEntry, 'status' | 'monitored' | 'is4k' | 'copies' | 'href'> & { copy: CalendarCopy };

async function sonarrEntries(s: ServiceRecord, start: Date, end: Date): Promise<Draft[]> {
	const [eps, profiles] = await Promise.all([
		cached(`cal:${s.id}:${start.toISOString()}:${end.toISOString()}`, 60_000, () =>
			call<SonarrCalendarEpisode[]>(
				s,
				`/api/v3/calendar?start=${encodeURIComponent(start.toISOString())}&end=${encodeURIComponent(end.toISOString())}&unmonitored=true&includeSeries=true`
			)
		),
		qualityProfiles(s)
	]);
	const out: Draft[] = [];
	for (const e of Array.isArray(eps) ? eps : []) {
		if (!e.airDateUtc || !e.series) continue;
		const at = +new Date(e.airDateUtc);
		if (Number.isNaN(at)) continue;
		const sr = e.series;
		const profile = sr.qualityProfileId != null ? profiles.get(sr.qualityProfileId) : undefined;
		const marker =
			e.finaleType === 'series'
				? 'series finale'
				: e.finaleType === 'season'
					? 'season finale'
					: e.finaleType === 'midseason'
						? 'midseason finale'
						: e.episodeNumber === 1
							? 'premiere'
							: null;
		out.push({
			id: sr.tvdbId ? `e:${sr.tvdbId}:${e.seasonNumber}:${e.episodeNumber}` : `e:${s.id}:${e.id}`,
			kind: 'episode',
			release: 'airing',
			title: sr.title,
			year: sr.year ?? null,
			episodeLabel: `S${pad(e.seasonNumber)}E${pad(e.episodeNumber)}${e.title ? ` · ${e.title}` : ''}`,
			marker,
			date: new Date(at).toISOString(),
			allDay: false,
			runtime: e.runtime || sr.runtime || null,
			network: sr.network || null,
			poster: arrImage(s, sr.images, 'poster', 250),
			copy: {
				serviceId: s.id,
				serviceName: s.name,
				is4k: UHD.test(s.name) || UHD.test(profile ?? ''),
				status: copyStatus(e.hasFile, at),
				monitored: e.monitored && sr.monitored !== false,
				href: `/library/${s.id}/series/${e.seriesId}`
			}
		});
	}
	return out;
}

async function radarrEntries(s: ServiceRecord, start: Date, end: Date): Promise<Draft[]> {
	// Release dates are calendar dates (stored as UTC midnight), so look a day either side of the
	// requested instants: the client places them on their date whatever its time zone.
	const qStart = new Date(+start - DAY);
	const qEnd = new Date(+end + DAY);
	const [movies, profiles] = await Promise.all([
		cached(`cal:${s.id}:${qStart.toISOString()}:${qEnd.toISOString()}`, 60_000, () =>
			call<RadarrCalendarMovie[]>(
				s,
				`/api/v3/calendar?start=${encodeURIComponent(qStart.toISOString())}&end=${encodeURIComponent(qEnd.toISOString())}&unmonitored=true`
			)
		),
		qualityProfiles(s)
	]);
	const out: Draft[] = [];
	for (const m of Array.isArray(movies) ? movies : []) {
		const profile = m.qualityProfileId != null ? profiles.get(m.qualityProfileId) : undefined;
		const is4k = UHD.test(s.name) || UHD.test(profile ?? '') || (m.movieFile?.quality?.quality?.resolution ?? 0) >= 2160;
		const releases: Array<[CalendarRelease, string | undefined]> = [
			['cinema', m.inCinemas],
			['digital', m.digitalRelease],
			['physical', m.physicalRelease]
		];
		for (const [release, raw] of releases) {
			if (!raw) continue;
			const day = raw.slice(0, 10);
			const at = Date.parse(`${day}T00:00:00Z`);
			if (Number.isNaN(at) || at < +qStart || at >= +qEnd) continue;
			out.push({
				id: m.tmdbId ? `m:${m.tmdbId}:${release}` : `m:${s.id}:${m.id}:${release}`,
				kind: 'movie',
				release,
				title: m.title,
				year: m.year ?? null,
				episodeLabel: null,
				marker: null,
				date: new Date(at).toISOString(),
				allDay: true,
				runtime: m.runtime || null,
				network: m.studio || null,
				poster: arrImage(s, m.images, 'poster', 250),
				copy: {
					serviceId: s.id,
					serviceName: s.name,
					is4k,
					// A cinema date passing doesn't make a movie downloadable; Radarr's isAvailable knows.
					status: copyStatus(m.hasFile, at, m.isAvailable !== false),
					monitored: m.monitored,
					href: `/library/${s.id}/movie/${m.id}`
				}
			});
		}
	}
	return out;
}

/** Merge every Sonarr and Radarr instance; the same title in several instances (e.g. Radarr + Radarr 4K) becomes one entry. */
export async function calendar(start: Date, end: Date): Promise<CalendarResult> {
	const services = servicesOfType('sonarr', 'radarr');
	const results = await Promise.allSettled(
		services.map((s) => (s.type === 'sonarr' ? sonarrEntries(s, start, end) : radarrEntries(s, start, end)))
	);
	const errors: CalendarResult['errors'] = [];
	const merged = new Map<string, CalendarEntry>();
	results.forEach((r, i) => {
		if (r.status === 'rejected') {
			errors.push({ service: services[i].name, error: errMsg(r.reason) });
			return;
		}
		for (const { copy, ...d } of r.value) {
			const hit = merged.get(d.id);
			if (!hit) {
				merged.set(d.id, { ...d, status: copy.status, monitored: copy.monitored, is4k: copy.is4k, href: copy.href, copies: [copy] });
				continue;
			}
			if (hit.copies.some((c) => c.serviceId === copy.serviceId)) continue; // same instance listed it twice
			hit.copies.push(copy);
			// The non-4K copy is the primary one (its poster and detail link), as in the Library.
			hit.copies.sort((a, b) => Number(a.is4k) - Number(b.is4k) || a.serviceId - b.serviceId);
			if (hit.copies[0] === copy) Object.assign(hit, { href: copy.href, poster: d.poster ?? hit.poster });
			hit.poster ??= d.poster;
			hit.network ??= d.network;
			hit.runtime ??= d.runtime;
		}
	});
	const entries = [...merged.values()];
	for (const e of entries) {
		e.status = combined(e.copies);
		e.monitored = e.copies.some((c) => c.monitored);
		e.is4k = e.copies.some((c) => c.is4k);
	}
	entries.sort((a, b) => a.date.localeCompare(b.date) || Number(b.allDay) - Number(a.allDay) || a.title.localeCompare(b.title));
	return { start: start.toISOString(), end: end.toISOString(), entries, errors };
}

export { ServiceError };
