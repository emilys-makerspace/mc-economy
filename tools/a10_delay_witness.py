#!/usr/bin/env python3
"""Finite raw-TCP transport witness; does not establish Minecraft sessions or finance."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import socket
import struct
import time

from a10_delay_proxy import DelayProxy, Timing, fixture_root


async def receive_exact(stream, size):
    result = bytearray()
    loop = asyncio.get_running_loop()
    while len(result) < size:
        chunk = await asyncio.wait_for(loop.sock_recv(stream, size - len(result)), 5)
        if not chunk:
            raise AssertionError('Unexpected TCP EOF')
        result.extend(chunk)
    return bytes(result)


async def bounds_case(root, upstream):
    """Two individually sub-budget clients exceed only their aggregate direction budget."""
    proxy = DelayProxy(root, upstream)
    sources = [socket.socket(socket.AF_INET, socket.SOCK_STREAM) for _ in range(2)]
    loop = asyncio.get_running_loop()
    individual_bytes = 600 * 1024
    try:
        port = await proxy.start_proxy()
        for source in sources:
            source.setblocking(False)
        await asyncio.gather(*(loop.sock_connect(source, ('127.0.0.1', port)) for source in sources))
        deadline = time.monotonic() + 5
        while len(proxy.connections) != 2 and time.monotonic() < deadline:
            await asyncio.sleep(.001)
        assert len(proxy.connections) == 2

        async def burst(source):
            with contextlib.suppress(ConnectionError, OSError):
                await asyncio.wait_for(loop.sock_sendall(source, b'\x5a' * individual_bytes), 5)

        await asyncio.gather(*(burst(source) for source in sources))
        deadline = time.monotonic() + 5
        while proxy.stats['client_to_server'].overflows == 0 and time.monotonic() < deadline:
            await asyncio.sleep(.01)
        assert proxy.stats['client_to_server'].overflows > 0, 'Concurrent aggregate overflow did not refuse a connection'
        for source in sources:
            source.close()
        deadline = time.monotonic() + 5
        while proxy.connections and time.monotonic() < deadline:
            await asyncio.sleep(.01)
        assert not proxy.connections, 'Closed clients retained proxy connections'
        for stats in proxy.stats.values():
            assert stats.pending_chunks == 0 and stats.pending_bytes == 0, 'Closed clients leaked shared reservations'

        # The same proxy must admit ordinary traffic after old reservations have been released.
        reconnect = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        reconnect.setblocking(False)
        try:
            await loop.sock_connect(reconnect, ('127.0.0.1', port))
            data = b'bounded-reconnect'
            await loop.sock_sendall(reconnect, data)
            assert await receive_exact(reconnect, len(data)) == data
            reconnect.shutdown(socket.SHUT_WR)
            assert not await asyncio.wait_for(loop.sock_recv(reconnect, 1), 5)
        finally:
            reconnect.close()
        await proxy.close()
        result = proxy.report()
        for direction in result['directions'].values():
            assert direction['max_pending_chunks'] <= 64
            assert direction['max_pending_bytes'] <= 1024 * 1024
            assert direction['final_pending_chunks'] == 0
            assert direction['final_pending_bytes'] == 0
        assert result['connection_outcomes'].get('bounded_overflow', 0) > 0
        result.update(concurrent_clients=2, input_bytes_per_client=individual_bytes, reconnect_after_release=True)
        return result
    finally:
        for source in sources:
            source.close()
        if not proxy.stopping:
            await proxy.close()


async def witness(root, bounds_only=False):
    echo_tasks = set()

    async def echo(reader, writer):
        task = asyncio.current_task()
        echo_tasks.add(task)
        try:
            while data := await reader.read(16384):
                writer.write(data)
                await writer.drain()
        except (ConnectionError, OSError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError, OSError):
                await writer.wait_closed()
            echo_tasks.discard(task)

    server = await asyncio.start_server(echo, '127.0.0.1', 0, limit=16384)
    upstream = server.sockets[0].getsockname()[1]
    proxy = DelayProxy(root, upstream)
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.setblocking(False)
    loop = asyncio.get_running_loop()
    timing = Timing()
    rounds = 0
    overflow = None
    try:
        if bounds_only:
            return dict(result='PASS', scope='Concurrent raw TCP aggregate bounds/release only; no game/auth/finance or 60-second delay claim',
                        overflow_transport=await bounds_case(root, upstream))
        port = await proxy.start_proxy()
        await loop.sock_connect(client, ('127.0.0.1', port))
        started = time.monotonic()
        while time.monotonic() - started < 60:
            data = struct.pack('!Q', rounds) + bytes(range(256)) * 4
            sent = time.monotonic()
            await loop.sock_sendall(client, data)
            returned = await receive_exact(client, len(data))
            if returned != data:
                raise AssertionError('Opaque TCP byte order/content changed')
            timing.add((time.monotonic() - sent) * 1000)
            rounds += 1
            await asyncio.sleep(.2)
        elapsed = time.monotonic() - started
        client.shutdown(socket.SHUT_WR)
        assert not await asyncio.wait_for(loop.sock_recv(client, 1), 5), 'Unexpected extra echo bytes'
        client.close()
        await proxy.close()
        transport = proxy.report()
        assert transport['observed_delay_window_seconds'] == 60
        assert rounds > 60
        for direction in transport['directions'].values():
            assert direction['max_pending_chunks'] <= 64
            assert direction['max_pending_bytes'] <= 1024 * 1024
            assert direction['overflow_connections'] == 0

        overflow = await bounds_case(root, upstream)
        return dict(result='PASS', scope='Raw loopback TCP only; no game/client/auth/financial witness',
                    ordered_echo_rounds=rounds, measured_echo_seconds=elapsed,
                    echo_round_trip_ms=timing.report(), delay_transport=transport, overflow_transport=overflow)
    finally:
        client.close()
        if not proxy.stopping:
            await proxy.close()
        server.close()
        await server.wait_closed()
        tasks = tuple(echo_tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture-root', required=True)
    parser.add_argument('--bounds-only', action='store_true', help='Run only concurrent aggregate overflow and reconnect/release checks')
    args = parser.parse_args()
    root = fixture_root(args.fixture_root)
    output = root / ('transport-bounds.json' if args.bounds_only else 'transport-witness.json')
    try:
        result = asyncio.run(witness(root, args.bounds_only))
    except BaseException as error:
        output.write_text(json.dumps(dict(result='FAILED', error=type(error).__name__), indent=2) + '\n')
        raise
    output.write_text(json.dumps(result, indent=2) + '\n')
    print('DELAY_TRANSPORT_WITNESS_PASS raw_tcp_only=true', flush=True)


if __name__ == '__main__':
    main()
