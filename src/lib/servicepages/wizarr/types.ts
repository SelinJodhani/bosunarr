// Client-safe shapes for the Wizarr screen (mapped server-side from Wizarr's /api responses).

export type WizarrUser = {
	id: number;
	username: string;
	email: string | null;
	server: string | null;
	serverType: string | null;
	/** ISO date, null = never expires. */
	expires: string | null;
	created: string | null;
};

export type InviteStatus = 'pending' | 'used' | 'expired';

export type WizarrInvite = {
	id: number;
	code: string;
	/** Path of the public join page on the Wizarr host, e.g. `/j/ABC123`. */
	path: string;
	status: InviteStatus;
	/** Can still be redeemed (pending, or an unlimited invite that was used but hasn't expired). */
	active: boolean;
	/** Multi-use invite. Wizarr has no numeric max-uses: an invite is single-use or unlimited. */
	unlimited: boolean;
	created: string | null;
	expires: string | null;
	usedAt: string | null;
	usedBy: string | null;
	/** User access duration in days; null = unlimited access. */
	durationDays: number | null;
	libraries: string[];
	servers: string[];
};

export type WizarrServer = { id: number; name: string; type: string; verified: boolean };
export type WizarrLibrary = { id: number; name: string; serverId: number | null; serverName: string; enabled: boolean };

export type WizarrOverview = {
	counts: { users: number; invites: number; pending: number; used: number; expired: number };
	users: WizarrUser[];
	invitations: WizarrInvite[];
	servers: WizarrServer[];
	libraries: WizarrLibrary[];
	/** Non-fatal problems (e.g. libraries couldn't be listed). */
	warnings: string[];
};

export const EXPIRY_DAYS = [1, 7, 30] as const;
export type ExpiryDays = (typeof EXPIRY_DAYS)[number];

export type CreateInviteRequest = {
	serverIds: number[];
	/** null = never expires. */
	expiresInDays: ExpiryDays | null;
	unlimited: boolean;
	/** null = unlimited access. */
	durationDays: number | null;
	libraryIds: number[];
};

export type CreatedInvite = { id: number; code: string; path: string };
