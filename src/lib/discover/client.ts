// Client helpers for Discover: instant detail paint, status labels, write actions.
import type { Availability, DiscoverItem, RequestStatus } from "./types";

export const HOME_URL = "/api/discover";

/** Items seen in lists this session, so a detail page can paint before its fetch. */
const seen = new Map<string, DiscoverItem>();

export function remember(items: DiscoverItem[]) {
  for (const it of items) seen.set(`${it.serviceId}-${it.key}`, it);
  // Keep it bounded.
  if (seen.size > 2000)
    for (const k of [...seen.keys()].slice(0, 500)) seen.delete(k);
}

export const peek = (
  serviceId: string | number,
  type: string,
  id: string | number,
) => seen.get(`${serviceId}-${type}-${id}`);

export const detailHref = (
  i: Pick<DiscoverItem, "serviceId" | "mediaType" | "tmdbId">,
) => `/discover/${i.serviceId}/${i.mediaType}/${i.tmdbId}`;

export const AVAILABILITY_LABEL: Record<Availability, string> = {
  available: "Available",
  partial: "Partially available",
  processing: "Requested",
  pending: "Pending approval",
  none: "Not requested",
};

/** Short badge text for posters; null = no badge. */
export const BADGE: Record<Availability, string | null> = {
  available: "Available",
  partial: "Partial",
  processing: "Requested",
  pending: "Pending",
  none: null,
};

export const REQUEST_LABEL: Record<RequestStatus, string> = {
  pending: "Pending approval",
  approved: "Approved",
  declined: "Declined",
  failed: "Failed",
  completed: "Completed",
};

/** De-duplicate by key (stale saved data or page overlaps must never produce a duplicate keyed-each entry). */
export function uniq<T extends { key: string }>(list: T[]): T[] {
  const keys = new Set<string>();
  return list.filter((x) => !keys.has(x.key) && (keys.add(x.key), true));
}

/** POST JSON; resolves to an error message or null on success. */
export async function post(url: string, body: unknown): Promise<string | null> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }).catch(() => null);
  if (!res) return "Network error";
  if (res.ok) return null;
  const b = await res.json().catch(() => ({}));
  return b?.error ?? b?.message ?? `HTTP ${res.status}`;
}

/** One colour scheme for every status badge (cards, detail, dialog, requests). */
export type Tone = "ok" | "partial" | "progress" | "wait" | "bad";
export const AVAILABILITY_TONE: Record<Availability, Tone | null> = {
  available: "ok",
  partial: "partial",
  processing: "progress",
  pending: "wait",
  none: null,
};
export const REQUEST_TONE: Record<RequestStatus, Tone> = {
  pending: "wait",
  approved: "progress",
  completed: "ok",
  declined: "bad",
  failed: "bad",
};

/** Theme seeds resolved this session, keyed by poster URL (sync lookups for a flash-free first paint). */
const seeds = new Map<string, string | null>();

/**
 * Poster-derived theme seed already computed, read synchronously so a detail page can paint in
 * its final colours on the first frame. Checks this module's own cache, then whatever
 * `posterSeed` in $lib/library/client persisted under `seed:<src>`. undefined = not computed yet.
 */
export function cachedSeed(
  src: string | null | undefined,
): string | null | undefined {
  if (!src) return null;
  if (seeds.has(src)) return seeds.get(src);
  for (const store of ["sessionStorage", "localStorage"] as const) {
    try {
      const hit = globalThis[store]?.getItem("seed:" + src);
      if (hit) return hit === "none" ? null : hit;
    } catch {
      /* storage disabled */
    }
  }
  return undefined;
}

/** Resolve (and remember) a poster's theme seed. */
export async function resolveSeed(src: string): Promise<string | null> {
  const { posterSeed } = await import("$lib/library/client");
  const seed = await posterSeed(src).catch(() => null);
  seeds.set(src, seed);
  return seed;
}

/** Start computing a poster's theme seed before navigating (on hover / touch), so the detail page opens already themed. */
export function warmSeed(src: string | null | undefined) {
  if (!src || cachedSeed(src) !== undefined) return;
  resolveSeed(src).catch(() => {});
}
