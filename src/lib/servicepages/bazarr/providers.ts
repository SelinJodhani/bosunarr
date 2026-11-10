// Bazarr reports subtitle providers by their internal id and throttles by exception class name.
// These turn them into words people recognise.

const NAMES: Record<string, string> = {
	addic7ed: 'Addic7ed',
	animekalesi: 'AnimeKalesi',
	animetosho: 'Anime Tosho',
	argenteam: 'Argenteam',
	assrt: 'Assrt',
	avistaz: 'AvistaZ',
	betaseries: 'BetaSeries',
	bsplayer: 'BSPlayer',
	cinemaz: 'CinemaZ',
	embeddedsubtitles: 'Embedded subtitles',
	gestdown: 'Gestdown',
	greeksubs: 'GreekSubs',
	greeksubtitles: 'Greek Subtitles',
	hdbits: 'HDBits',
	hosszupuska: 'Hosszupuska',
	jimaku: 'Jimaku',
	karagarga: 'Karagarga',
	ktuvit: 'Ktuvit',
	legendasdivx: 'LegendasDivx',
	legendasnet: 'Legendas.net',
	napiprojekt: 'NapiProjekt',
	napisy24: 'Napisy24',
	nekur: 'Nekur',
	opensubtitles: 'OpenSubtitles.org',
	opensubtitlescom: 'OpenSubtitles.com',
	podnapisi: 'Podnapisi',
	regielive: 'RegieLive',
	soustitreseu: 'Sous-Titres.eu',
	subdivx: 'Subdivx',
	subdl: 'SubDL',
	subf2m: 'Subf2m',
	subs4free: 'Subs4Free',
	subs4series: 'Subs4Series',
	subscene: 'Subscene',
	subscenter: 'Subscenter',
	subssabbz: 'Subs.sab.bz',
	subsunacs: 'Subsunacs',
	subsynchro: 'Subsynchro',
	subtis: 'Subtis',
	subtitrarinoi: 'Subtitrari Noi',
	subtitriid: 'Subtitri.id',
	subtitulamostv: 'Subtitulamos.tv',
	supersubtitles: 'Super Subtitles',
	titlovi: 'Titlovi',
	titrari: 'Titrari',
	titulky: 'Titulky.com',
	turkcealtyaziorg: 'Turkcealtyazi.org',
	tusubtitulo: 'TuSubtitulo',
	tvsubtitles: 'TVsubtitles',
	whisperai: 'Whisper (AI)',
	wizdom: 'Wizdom',
	xsubs: 'XSubs',
	yavkanet: 'Yavka.net',
	yifysubtitles: 'YIFY Subtitles',
	zimuku: 'Zimuku'
};

export function providerName(id: string): string {
	return NAMES[id.toLowerCase()] ?? id.replace(/[_-]+/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

const ISSUES: Array<[RegExp, string]> = [
	[/TooManyRequests|RateLimit|Throttl/i, 'Too many requests — paused by the provider'],
	[/DownloadLimit/i, 'Daily download limit reached'],
	[/Authentication|Unauthori[sz]ed|Login|Credential/i, 'Login failed — check its username and password in Bazarr'],
	[/ConfigurationError|APIKey|ApiKey/i, 'Not set up correctly — check its settings in Bazarr'],
	[/ServiceUnavailable|Unavailable|5\d\d/i, 'The provider is down right now'],
	[/Timeout|ReadTimeout|ConnectTimeout|timed out/i, 'The provider took too long to answer'],
	[/ConnectionError|SSLError|NameResolution/i, "Bazarr couldn't connect to the provider"],
	[/ParseResponseError|JSONDecode/i, 'The provider sent a response Bazarr could not read'],
	[/IPAddressBlocked|Forbidden|403/i, 'The provider is blocking this server'],
	[/Cloudflare|Captcha/i, 'Blocked by a Cloudflare or CAPTCHA check']
];

export function providerIssue(raw: string): string {
	for (const [re, text] of ISSUES) if (re.test(raw)) return text;
	// Unknown class name → "SomethingWentWrong" → "Something went wrong".
	const words = raw.replace(/Error$|Exception$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
	return words ? words[0].toUpperCase() + words.slice(1).toLowerCase() : 'Not working right now';
}
