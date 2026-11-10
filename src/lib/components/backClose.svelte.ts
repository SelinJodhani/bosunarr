/**
 * useBackClose — the system Back gesture/button closes a menu, sheet or popover instead of leaving the page.
 *
 * Usage (call once during component init, like any rune-based helper):
 *
 *   import { useBackClose } from '$lib/components/backClose.svelte';
 *   let open = $state(false);
 *   useBackClose(() => open, () => (open = false));
 *
 * - `isOpen` is read reactively: when it turns true the helper arms, when it turns false it disarms.
 * - `close` is called when the user presses Back (or Esc on desktop, where CloseWatcher exists) while open.
 *   It must set whatever `isOpen` reads to false.
 *
 * How: where the browser has `CloseWatcher` (Chrome/Android 120+, incl. installed PWAs) that is used; it needs no
 * history entry. Elsewhere a shallow history entry is pushed with SvelteKit's `pushState` while open; Back pops it
 * (closing the menu), and closing the menu any other way pops it again so history doesn't fill with stale entries.
 *
 * Caveat for the fallback: if choosing an item *navigates* (goto / link), navigate first and let the component
 * unmount, rather than closing the menu and then navigating — closing pops the shallow entry with history.back().
 * m3-svelte <Dialog>s don't need this: native <dialog> already closes on Back in Chrome.
 */
import { untrack } from 'svelte';
import { pushState } from '$app/navigation';
import { page } from '$app/state';

type Watcher = { onclose: (() => void) | null; destroy(): void };
type WatcherCtor = new () => Watcher;

let seq = 0;

export function useBackClose(isOpen: () => boolean, close: () => void): void {
	/** Key of our shallow history entry while armed (fallback path only). */
	let armed: string | null = null;

	// Back popped our entry: page.state no longer carries the key.
	$effect(() => {
		const state = page.state as Record<string, unknown>;
		if (armed && !state[armed]) {
			armed = null;
			untrack(close);
		}
	});

	$effect(() => {
		if (!isOpen()) return;
		return untrack(() => {
			const CW = (globalThis as { CloseWatcher?: WatcherCtor }).CloseWatcher;
			if (CW) {
				let w: Watcher | null = null;
				try {
					w = new CW();
				} catch {
					w = null;
				}
				if (w) {
					let fired = false;
					w.onclose = () => {
						fired = true;
						close();
					};
					return () => {
						if (!fired) w.destroy();
					};
				}
			}
			const key = `backClose${++seq}`;
			try {
				pushState('', { ...page.state, [key]: true } as App.PageState);
			} catch {
				return; // router not ready: Back keeps its default behaviour
			}
			armed = key;
			return () => {
				// Closed from the UI (item picked, outside tap, Esc): drop our entry if it is still the current one.
				if (armed === key && (page.state as Record<string, unknown>)[key]) {
					armed = null;
					history.back();
				}
			};
		});
	});
}
