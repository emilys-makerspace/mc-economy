#!/usr/bin/env python3
"""Disposable A10-04 opaque TCP delay fixture; never a Minecraft protocol endpoint."""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter, deque
import contextlib
import json
from pathlib import Path
import signal
import socket
import tempfile
import time

CHUNK_BYTES = 16 * 1024
MAX_CHUNKS = 64
MAX_BYTES = 1024 * 1024
DELAY_SECONDS = 60.0


def fixture_root(value: str) -> Path:
    given = Path(value).absolute()
    root = given.resolve(strict=True)
    parents = {Path(tempfile.gettempdir()).resolve(), Path('/private/tmp').resolve()}
    if (given.is_symlink() or not root.is_dir() or root.parent not in parents
            or not root.name.startswith('economy-a10-04-')
            or root.name == 'economy-a10-04-'):
        raise ValueError('An existing guarded temporary economy-a10-04-* directory is required')
    return root


class Timing:
    """Fixed-size millisecond histogram: timing evidence cannot grow with traffic."""
    def __init__(self):
        self.buckets = [0] * 10002
        self.count = 0
        self.total = 0.0
        self.minimum = None
        self.maximum = 0.0

    def add(self, milliseconds):
        self.count += 1
        self.total += milliseconds
        self.minimum = milliseconds if self.minimum is None else min(self.minimum, milliseconds)
        self.maximum = max(self.maximum, milliseconds)
        self.buckets[min(10001, int(milliseconds))] += 1

    def report(self):
        def quantile(fraction):
            threshold = max(1, int(self.count * fraction + 0.999999))
            cumulative = 0
            for index, count in enumerate(self.buckets):
                cumulative += count
                if cumulative >= threshold:
                    return index if index < 10001 else 'over_10000'
            return None
        return dict(count=self.count, min_ms=self.minimum, mean_ms=self.total / self.count if self.count else None,
                    p50_ms=quantile(.5) if self.count else None, p95_ms=quantile(.95) if self.count else None,
                    p99_ms=quantile(.99) if self.count else None, max_ms=self.maximum,
                    quantile_resolution_ms=1)


class DirectionStats:
    def __init__(self):
        self.received_chunks = self.forwarded_chunks = 0
        self.received_bytes = self.forwarded_bytes = 0
        self.pending_chunks = self.pending_bytes = 0
        self.max_pending_chunks = self.max_pending_bytes = 0
        self.overflows = 0
        self.delay = Timing()
        self.delayed = Timing()

    def report(self):
        return dict(received_chunks=self.received_chunks, forwarded_chunks=self.forwarded_chunks,
                    received_bytes=self.received_bytes, forwarded_bytes=self.forwarded_bytes,
                    max_pending_chunks=self.max_pending_chunks, max_pending_bytes=self.max_pending_bytes,
                    final_pending_chunks=self.pending_chunks, final_pending_bytes=self.pending_bytes,
                    overflow_connections=self.overflows,
                    receive_to_send_complete_ms=self.delay.report(),
                    delayed_receive_to_send_complete_ms=self.delayed.report())


class TransportOverflow(Exception):
    pass


class Direction:
    """Queue includes the current send until sock_sendall completes (no StreamWriter buffer)."""
    def __init__(self, source, target, stats, delay, offset):
        self.source, self.target, self.stats = source, target, stats
        self.delay, self.offset = delay, offset
        self.queue = deque()
        self.ready = asyncio.Event()
        self.eof = False
        self.pending_chunks = self.pending_bytes = 0
        self.index = 0
        self.last_due = 0.0

    def enqueue(self, data, received):
        # This shared budget covers every connection in the proxy direction, including active sends.
        if self.stats.pending_chunks + 1 > MAX_CHUNKS or self.stats.pending_bytes + len(data) > MAX_BYTES:
            self.stats.overflows += 1
            raise TransportOverflow('pending transport bound exceeded')
        active = self.delay(received)
        jitter_ms = ((self.index * 19 + self.offset) % 51) - 25
        self.index += 1
        due = max(self.last_due, received + (.150 + jitter_ms / 1000 if active else 0))
        self.last_due = due
        self.pending_chunks += 1
        self.pending_bytes += len(data)
        self.stats.pending_chunks += 1
        self.stats.pending_bytes += len(data)
        self.stats.received_chunks += 1
        self.stats.received_bytes += len(data)
        self.stats.max_pending_chunks = max(self.stats.max_pending_chunks, self.stats.pending_chunks)
        self.stats.max_pending_bytes = max(self.stats.max_pending_bytes, self.stats.pending_bytes)
        self.queue.append((data, received, due, active))
        self.ready.set()

    async def receive(self):
        loop = asyncio.get_running_loop()
        while True:
            data = await loop.sock_recv(self.source, CHUNK_BYTES)
            if not data:
                self.eof = True
                self.ready.set()
                return
            # An overflowing read is rejected immediately; it never enters the pending queue.
            self.enqueue(data, time.monotonic())

    async def send(self):
        loop = asyncio.get_running_loop()
        while True:
            while self.queue:
                data, received, due, delayed = self.queue.popleft()
                await asyncio.sleep(max(0, due - time.monotonic()))
                await loop.sock_sendall(self.target, data)
                elapsed = (time.monotonic() - received) * 1000
                self.stats.delay.add(elapsed)
                if delayed:
                    self.stats.delayed.add(elapsed)
                self.stats.forwarded_chunks += 1
                self.stats.forwarded_bytes += len(data)
                self.pending_chunks -= 1
                self.pending_bytes -= len(data)
                self.stats.pending_chunks -= 1
                self.stats.pending_bytes -= len(data)
            if self.eof:
                with contextlib.suppress(OSError):
                    self.target.shutdown(socket.SHUT_WR)
                return
            self.ready.clear()
            await self.ready.wait()

    async def run(self):
        reader = asyncio.create_task(self.receive())
        sender = asyncio.create_task(self.send())
        try:
            await asyncio.gather(reader, sender)
        finally:
            reader.cancel()
            sender.cancel()
            await asyncio.gather(reader, sender, return_exceptions=True)
            # A canceled/overflowed connection must return its remaining shared reservation.
            self.stats.pending_chunks -= self.pending_chunks
            self.stats.pending_bytes -= self.pending_bytes
            self.pending_chunks = self.pending_bytes = 0
            self.queue.clear()


class DelayProxy:
    def __init__(self, root: Path, upstream_port: int, listen_port: int = 0, arm_file: str | None = None):
        self.root, self.upstream_port, self.listen_port = root, upstream_port, listen_port
        if arm_file and (Path(arm_file).name != arm_file or arm_file in {'.', '..'}):
            raise ValueError('Arm file must be a filename inside the fixture root')
        self.arm_file = root / arm_file if arm_file else None
        self.start = self.delay_start = self.stop_time = None
        self.listener = None
        self.accept_task = self.arm_task = None
        self.connections = set()
        self.sockets = set()
        self.accepted = self.refused = 0
        self.reasons = Counter()
        self.stats = {'client_to_server': DirectionStats(), 'server_to_client': DirectionStats()}
        self.stopping = False

    def delayed(self, now):
        return self.delay_start is not None and now < self.delay_start + DELAY_SECONDS

    async def start_proxy(self):
        self.start = time.monotonic()
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.setblocking(False)
        self.listener.bind(('127.0.0.1', self.listen_port))
        self.listener.listen(8)
        self.listen_port = self.listener.getsockname()[1]
        self.accept_task = asyncio.create_task(self.accept())
        if self.arm_file:
            self.arm_task = asyncio.create_task(self.arm())
        return self.listen_port

    async def arm(self):
        while not self.stopping and self.delay_start is None:
            if self.arm_file.exists():
                self.delay_start = time.monotonic()
                print('DELAY_PROXY_ARMED duration_seconds=60', flush=True)
                return
            await asyncio.sleep(.05)

    async def accept(self):
        loop = asyncio.get_running_loop()
        while not self.stopping:
            source, _ = await loop.sock_accept(self.listener)
            source.setblocking(False)
            if len(self.connections) >= 8:
                self.refused += 1
                source.close()
                continue
            if self.delay_start is None and not self.arm_file:
                self.delay_start = time.monotonic()
            self.accepted += 1
            task = asyncio.create_task(self.connect(source))
            self.connections.add(task)
            task.add_done_callback(self.connections.discard)

    async def connect(self, source):
        target = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        target.setblocking(False)
        self.sockets.update((source, target))
        directions = []
        reason = 'eof'
        try:
            await asyncio.wait_for(asyncio.get_running_loop().sock_connect(target, ('127.0.0.1', self.upstream_port)), 5)
            directions = [asyncio.create_task(Direction(source, target, self.stats['client_to_server'], self.delayed, 0).run()),
                          asyncio.create_task(Direction(target, source, self.stats['server_to_client'], self.delayed, 11).run())]
            done, pending = await asyncio.wait(directions, return_when=asyncio.FIRST_EXCEPTION)
            for task in done:
                task.result()
            await asyncio.gather(*pending)
        except TransportOverflow:
            reason = 'bounded_overflow'
        except asyncio.CancelledError:
            reason = 'shutdown'
            raise
        except (OSError, asyncio.TimeoutError):
            reason = 'socket_close_or_connect_failure'
        finally:
            self.reasons[reason] += 1
            for task in directions:
                task.cancel()
            await asyncio.gather(*directions, return_exceptions=True)
            for stream in (source, target):
                with contextlib.suppress(OSError):
                    stream.shutdown(socket.SHUT_RDWR)
                stream.close()
                self.sockets.discard(stream)

    async def close(self):
        self.stopping = True
        for task in (self.accept_task, self.arm_task):
            if task:
                task.cancel()
        if self.listener:
            self.listener.close()
        # Normal closure allows queued sends to drain, bounded to three seconds for a stalled peer.
        tasks = tuple(self.connections)
        if tasks:
            _, pending = await asyncio.wait(tasks, timeout=3)
            for task in pending:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        await asyncio.gather(*(t for t in (self.accept_task, self.arm_task) if t), return_exceptions=True)
        self.stop_time = time.monotonic()

    def report(self):
        now = self.stop_time or time.monotonic()
        active_elapsed = max(0, min(DELAY_SECONDS, now - self.delay_start)) if self.delay_start is not None else 0
        return dict(kind='opaque_loopback_tcp_proxy', topology='127.0.0.1 listener -> 127.0.0.1 upstream',
                    listen_port=self.listen_port, upstream_port=self.upstream_port,
                    configured_delay_ms=150, deterministic_jitter_ms=25, configured_delay_window_seconds=60,
                    observed_delay_window_seconds=active_elapsed,
                    delay_start_monotonic=self.delay_start, run_elapsed_seconds=now - self.start,
                    chunk_bytes=CHUNK_BYTES, max_pending_chunks_per_direction=MAX_CHUNKS,
                    max_pending_bytes_per_direction=MAX_BYTES, max_simultaneous_connections=8,
                    accepted_connections=self.accepted, refused_connections=self.refused,
                    connection_outcomes=dict(self.reasons),
                    directions={name: stats.report() for name, stats in self.stats.items()},
                    bounds_scope='Aggregate per proxy direction across all connections: application queue plus active sock_sendall; OS TCP buffers are separate. No StreamReader/StreamWriter buffers.',
                    timing_scope='receive completion to sock_sendall completion; not peer receive time',
                    protocol_scope='Opaque ordered TCP bytes; no Minecraft authentication or financial assertion')


async def cli(args):
    root = fixture_root(args.fixture_root)
    proxy = DelayProxy(root, args.upstream_port, args.listen_port, args.arm_file)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    try:
        port = await proxy.start_proxy()
        print(f'DELAY_PROXY_READY listen=127.0.0.1:{port} upstream=127.0.0.1:{args.upstream_port}', flush=True)
        await stop.wait()
    finally:
        await proxy.close()
        (root / 'delay-proxy.json').write_text(json.dumps(proxy.report(), indent=2) + '\n')
        print('DELAY_PROXY_STOPPED', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture-root', required=True)
    parser.add_argument('--listen-port', type=int, default=25566)
    parser.add_argument('--upstream-port', type=int, default=25565)
    parser.add_argument('--arm-file', help='Optional filename in fixture root; create it to begin the 60-second delay window')
    args = parser.parse_args()
    if not all(1 <= port <= 65535 for port in (args.listen_port, args.upstream_port)):
        parser.error('TCP ports must be between 1 and 65535')
    asyncio.run(cli(args))


if __name__ == '__main__':
    main()
