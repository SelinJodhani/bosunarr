/**
 * Turn the server's raw service errors ("Sonarr TV returned HTTP 404", "Radarr is unreachable")
 * into short, human messages. The raw text stays available as detail (tap / hover).
 */
export type HumanError = { service: string | null; short: string; hint: string | null; detail: string };

const RAW = /^(.*?) (is unreachable|timed out|rejected the credentials|returned HTTP (\d{3}))$/;

export function humanError(raw: string): HumanError {
	const m = RAW.exec(raw.trim());
	const service = m ? m[1] : null;
	const code = m?.[3] ? Number(m[3]) : /\bHTTP (\d{3})\b/.exec(raw) ? Number(/\bHTTP (\d{3})\b/.exec(raw)![1]) : null;
	const r = raw.toLowerCase();
	let short = 'Something went wrong';
	let hint: string | null = null;
	if (r.includes('unreachable') || r.includes('failed to fetch') || r.includes('networkerror')) {
		short = "Can't reach it";
		hint = 'Is it running? Check the URL in Settings.';
	} else if (r.includes('timed out')) {
		short = 'Not responding';
		hint = 'It took too long to answer.';
	} else if (r.includes('banned')) {
		short = 'Temporarily blocked';
		hint = 'Too many failed logins — wait a minute.';
	} else if (r.includes('login failed') || r.includes('no session cookie')) {
		short = 'Wrong username or password';
	} else if (r.includes('rejected the credentials') || code === 401 || code === 403) {
		short = 'Wrong API key';
		hint = 'Update it in Settings.';
	} else if (code === 404) {
		short = 'Not found — check the URL';
	} else if (code && code >= 500) {
		short = 'It hit a server error';
	} else if (r.includes('unexpected response')) {
		short = 'Unexpected answer';
	}
	return { service, short, hint, detail: raw };
}
