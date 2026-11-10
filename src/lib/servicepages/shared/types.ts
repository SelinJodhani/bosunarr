import type { IconifyIcon } from '@iconify/types';

export type Tone = 'ok' | 'info' | 'warn' | 'bad' | 'off';

/** One number tile in a service's summary card. Give it `onclick` or `href` to make it a shortcut. */
export type SummaryStat = {
	value: string | number | null;
	label: string;
	/** Colours the number: warn = tertiary, bad = error, info = primary. */
	tone?: Tone;
	onclick?: () => void;
	href?: string;
};

/** A health / update / sync notice, shown at the top of a tab (see ServiceTabs). */
export type SummaryNotice = {
	tone: 'bad' | 'warn' | 'info' | 'update';
	text: string;
	/** Short bold prefix, e.g. "Indexer status". */
	source?: string;
	href?: string;
	/** Text of the link button; defaults to "How to fix". */
	linkLabel?: string;
	icon?: IconifyIcon;
};
