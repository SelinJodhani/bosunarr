import { getContext, setContext } from 'svelte';

/** What a widget's frame shares with the State inside it. */
export type FrameContext = {
	/** Widget instance id (layout key). */
	readonly id: string;
	/** Remembered content height (px) for the loading skeleton, from the last visit. */
	readonly reserve: number | undefined;
	/** Raw error while cached data is shown, or null. */
	setStale(error: string | null): void;
};

const KEY = Symbol('widget-frame');
export const setFrameContext = (c: FrameContext) => setContext(KEY, c);
export const frameContext = () => getContext<FrameContext | undefined>(KEY);
