// Client-safe description of every supported service type. No secrets here.

export type CredentialKind = 'apiKey' | 'userPass' | 'none';

export type ServiceTypeId =
	| 'sonarr'
	| 'radarr'
	| 'lidarr'
	| 'prowlarr'
	| 'bazarr'
	| 'jellyseerr'
	| 'overseerr'
	| 'qbittorrent'
	| 'jellyfin'
	| 'jellystat'
	| 'wizarr';

export type ServiceTypeInfo = {
	id: ServiceTypeId;
	label: string;
	category: 'media' | 'indexer' | 'subtitles' | 'requests' | 'downloads' | 'server' | 'stats' | 'users';
	credentials: CredentialKind;
	defaultPort: number;
	/** Where users find the key, shown in the add-service form. */
	keyHint: string;
	/** Seed colour for the service's tonal accent. */
	color: string;
};

export const SERVICE_TYPES: Record<ServiceTypeId, ServiceTypeInfo> = {
	sonarr: {
		id: 'sonarr',
		label: 'Sonarr',
		category: 'media',
		credentials: 'apiKey',
		defaultPort: 8989,
		keyHint: 'Sonarr → Settings → General → API Key',
		color: '#35c5f4'
	},
	radarr: {
		id: 'radarr',
		label: 'Radarr',
		category: 'media',
		credentials: 'apiKey',
		defaultPort: 7878,
		keyHint: 'Radarr → Settings → General → API Key',
		color: '#ffc230'
	},
	lidarr: {
		id: 'lidarr',
		label: 'Lidarr',
		category: 'media',
		credentials: 'apiKey',
		defaultPort: 8686,
		keyHint: 'Lidarr → Settings → General → API Key',
		color: '#00a65b'
	},
	prowlarr: {
		id: 'prowlarr',
		label: 'Prowlarr',
		category: 'indexer',
		credentials: 'apiKey',
		defaultPort: 9696,
		keyHint: 'Prowlarr → Settings → General → API Key',
		color: '#e66001'
	},
	bazarr: {
		id: 'bazarr',
		label: 'Bazarr',
		category: 'subtitles',
		credentials: 'apiKey',
		defaultPort: 6767,
		keyHint: 'Bazarr → Settings → General → Security → API Key',
		color: '#14b8a6'
	},
	jellyseerr: {
		id: 'jellyseerr',
		label: 'Jellyseerr / Seerr',
		category: 'requests',
		credentials: 'apiKey',
		defaultPort: 5055,
		keyHint: 'Jellyseerr → Settings → General → API Key',
		color: '#7c3aed'
	},
	overseerr: {
		id: 'overseerr',
		label: 'Overseerr',
		category: 'requests',
		credentials: 'apiKey',
		defaultPort: 5055,
		keyHint: 'Overseerr → Settings → General → API Key',
		color: '#6366f1'
	},
	qbittorrent: {
		id: 'qbittorrent',
		label: 'qBittorrent',
		category: 'downloads',
		credentials: 'userPass',
		defaultPort: 8080,
		keyHint: 'Your qBittorrent Web UI username and password',
		color: '#2f67ba'
	},
	jellyfin: {
		id: 'jellyfin',
		label: 'Jellyfin',
		category: 'server',
		credentials: 'apiKey',
		defaultPort: 8096,
		keyHint: 'Jellyfin → Dashboard → API Keys → +',
		color: '#aa5cc3'
	},
	jellystat: {
		id: 'jellystat',
		label: 'Jellystat',
		category: 'stats',
		credentials: 'apiKey',
		defaultPort: 3000,
		keyHint: 'Jellystat → Settings → API Keys',
		color: '#f472b6'
	},
	wizarr: {
		id: 'wizarr',
		label: 'Wizarr',
		category: 'users',
		credentials: 'apiKey',
		defaultPort: 5690,
		keyHint: 'Wizarr → Settings → API Keys',
		color: '#f59e0b'
	}
};

export const SERVICE_TYPE_LIST = Object.values(SERVICE_TYPES);

/** A configured service as the browser sees it — never includes credentials. */
export type PublicService = {
	id: number;
	type: ServiceTypeId;
	name: string;
	url: string;
	externalUrl: string | null;
	hasCredentials: boolean;
};
