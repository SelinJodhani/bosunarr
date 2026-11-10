import { untrack } from 'svelte';
import { pushState } from '$app/navigation';
import { navigating, page } from '$app/state';

// Local helper until the shared one lands (to be consolidated after merge).

type CloseWatcherLike = { onclose: (() => void) | null; destroy(): void };
type CloseWatcherCtor = new () => CloseWatcherLike;

/**
 * While `isOpen()` is true, the system back gesture / button (and Escape) calls `close()` instead of
 * leaving the page — for menus and popovers that aren't native dialogs.
 * Uses CloseWatcher where the browser has it (Chrome on Android), otherwise a shallow history entry
 * that is popped again when the menu closes some other way.
 * Call during component initialisation.
 */
export function closeOnBack(isOpen: () => boolean, close: () => void) {
	$effect(() => {
		if (!isOpen()) return;
		return untrack(() => {
			const Watcher = (globalThis as { CloseWatcher?: CloseWatcherCtor }).CloseWatcher;
			if (Watcher) {
				let watcher: CloseWatcherLike | null = null;
				try {
					watcher = new Watcher();
				} catch {
					/* not allowed right now: fall through to the history entry */
				}
				if (watcher) {
					watcher.onclose = () => close();
					return () => watcher?.destroy();
				}
			}

			const key = `menu-${Math.random().toString(36).slice(2)}`;
			pushState('', { ...page.state, overlay: key } as App.PageState);
			let popped = false;
			const onPop = () => {
				popped = true;
				close();
			};
			window.addEventListener('popstate', onPop, { once: true });
			return () => {
				window.removeEventListener('popstate', onPop);
				// Closed by a tap or a pick: drop the entry we added so back isn't "used up" — unless the
				// pick itself navigated somewhere (checked a tick later, once goto() has started).
				if (popped) return;
				setTimeout(() => {
					if (!navigating.to && (page.state as { overlay?: string }).overlay === key) history.back();
				}, 0);
			};
		});
	});
}
