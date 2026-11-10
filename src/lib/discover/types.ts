// Client-safe shapes for Discover + requests (Jellyseerr / Overseerr).
import type { CastMember } from "$lib/library/types";

export type MediaType = "movie" | "tv";

/** Seerr media status, collapsed: available (5), partial (4), processing (3 — approved, downloading), pending (2 — awaiting approval). */
export type Availability =
  "available" | "partial" | "processing" | "pending" | "none";

export type DiscoverItem = {
  /** `${mediaType}-${tmdbId}` — unique within one Seerr instance. */
  key: string;
  serviceId: number;
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  year: number | null;
  poster: string | null;
  /** 0–10 (TMDb vote average). */
  rating: number | null;
  status: Availability;
  status4k: Availability;
};

/** Built-in rows; genre lists use `genre-movie-<id>` / `genre-tv-<id>`. */
export type SectionId =
  | "trending"
  | "movies"
  | "tv"
  | "movies-upcoming"
  | "tv-upcoming"
  | `genre-${MediaType}-${number}`;

export type DiscoverRow = {
  id: SectionId;
  title: string;
  items: DiscoverItem[];
};

export type Genre = { id: number; name: string; mediaType: MediaType };

export type DiscoverHome = {
  /** null when no Jellyseerr / Overseerr is configured. */
  serviceId: number | null;
  serviceName: string | null;
  rows: DiscoverRow[];
  genres: Genre[];
  errors: string[];
};

export type DiscoverPage = {
  items: DiscoverItem[];
  page: number;
  totalPages: number;
  totalResults: number;
};

export type SeasonInfo = {
  number: number;
  name: string;
  episodes: number;
  airDate: string | null;
  status: Availability;
  status4k: Availability;
};

export type RequestStatus =
  "pending" | "approved" | "declined" | "failed" | "completed";

export type RequestBrief = {
  id: number;
  status: RequestStatus;
  is4k: boolean;
  seasons: number[];
  requestedBy: string;
  createdAt: string;
};

export type LibraryLink = { href: string; serviceName: string; is4k: boolean };

export type Rating = {
  source: "tmdb" | "imdb" | "rt" | "rtAudience";
  label: string;
  value: string;
};

export type DiscoverDetail = DiscoverItem & {
  serviceName: string;
  backdrop: string | null;
  overview: string | null;
  tagline: string | null;
  runtime: number | null;
  certification: string | null;
  genres: string[];
  /** Release (movie) or first air (tv) date, ISO yyyy-mm-dd. */
  date: string | null;
  /** "Released", "Returning Series", … */
  releaseStatus: string | null;
  /** Network (tv) or studio (movie). */
  network: string | null;
  director: string | null;
  cast: CastMember[];
  ratings: Rating[];
  seasons: SeasonInfo[];
  requests: RequestBrief[];
  inLibrary: LibraryLink[];
  /** Seerr has a 4K Radarr (movie) / Sonarr (tv) server configured. */
  can4k: boolean;
  imdbId: string | null;
  tvdbId: number | null;
  openUrl: string | null;
};

export type RequestFilter =
  "pending" | "approved" | "available" | "failed" | "all";

export type RequestItem = {
  /** `${serviceId}:${requestId}` — unique across instances. */
  key: string;
  serviceId: number;
  serviceName: string;
  id: number;
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  year: number | null;
  poster: string | null;
  status: RequestStatus;
  media: Availability;
  is4k: boolean;
  seasons: number[];
  requestedBy: string;
  modifiedBy: string | null;
  createdAt: string;
};

export type RequestsResult = {
  items: RequestItem[];
  hasMore: boolean;
  errors: Array<{ service: string; error: string }>;
};

export type CreateRequest = {
  serviceId: number;
  mediaType: MediaType;
  tmdbId: number;
  is4k: boolean;
  seasons?: number[];
};

export type RequestAction = {
  serviceId: number;
  requestId: number;
  action: "approve" | "decline" | "delete";
};

/** What a Decline / Delete confirmation is about. */
export type ConfirmTarget = {
  action: "decline" | "delete";
  title: string;
  is4k: boolean;
  requestedBy: string;
  serviceName: string;
};
