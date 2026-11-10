// System back closes a transient surface (menu) instead of leaving the page.
// Local stop-gap: to be consolidated with the shared helper after merge.
import { pushState } from "$app/navigation";
import { page } from "$app/state";

/**
 * Call during component init. `opened()` when the surface opens pushes a shallow history entry;
 * system back pops it and `close` runs. `closed()` when it closes any other way (item tapped,
 * light dismiss, Esc) removes that entry again, so the next back leaves the page as usual.
 */
export function backClose(key: string, close: () => void) {
  let pushed = false;
  $effect(() => {
    const state = page.state as Record<string, unknown>;
    if (pushed && state[key] !== true) {
      pushed = false;
      close();
    }
  });
  // Unmounting (navigating away) with the surface open: never call back() then, it would undo
  // the navigation; the leftover entry is the same URL, so a later back just lands here again.
  $effect(() => () => {
    pushed = false;
  });
  return {
    opened() {
      if (pushed) return;
      pushed = true;
      pushState("", {
        ...(page.state as Record<string, unknown>),
        [key]: true,
      } as App.PageState);
    },
    closed() {
      if (!pushed) return;
      pushed = false;
      history.back();
    },
  };
}
