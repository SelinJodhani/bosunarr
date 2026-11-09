import type { RequestHandler } from './$types';
import { subscribe } from '$lib/server/activity';

/** Server-Sent Events: pushes a full snapshot whenever queues or torrents change (polled every 2 s, shared). */
export const GET: RequestHandler = ({ request }) => {
	const enc = new TextEncoder();
	let cleanup = () => {};
	const stream = new ReadableStream<Uint8Array>({
		start(controller) {
			const send = (chunk: string) => {
				try {
					controller.enqueue(enc.encode(chunk));
				} catch {
					cleanup();
				}
			};
			send('retry: 3000\n\n');
			const unsubscribe = subscribe((json) => send(`data: ${json}\n\n`));
			const heartbeat = setInterval(() => send(': ping\n\n'), 20_000);
			cleanup = () => {
				clearInterval(heartbeat);
				unsubscribe();
			};
			request.signal.addEventListener('abort', () => {
				cleanup();
				try {
					controller.close();
				} catch {
					/* already closed */
				}
			});
		},
		cancel() {
			cleanup();
		}
	});
	return new Response(stream, {
		headers: {
			'Content-Type': 'text/event-stream; charset=utf-8',
			'Cache-Control': 'no-cache, no-transform',
			Connection: 'keep-alive',
			'X-Accel-Buffering': 'no'
		}
	});
};
