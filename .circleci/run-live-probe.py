#!/usr/bin/env python3
"""Run one isolated integrated-client probe per CircleCI node and publish JUnit evidence."""
import argparse
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

PROBES = (
    ('bankingDashboardProbe', 'economy-a10-02-01-', 'banking-dashboard'),
    ('bankingHistoryProbe', 'economy-a10-02-02-', 'banking-history'),
    ('traderHookProbe', 'economy-a8-04-01-', 'trader-hook'),
    ('traderCustodyProbe', 'economy-a8-04-02-', 'trader-custody'),
)


def stop_owned_process(process):
    """Only this freshly launched process group may be terminated."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)


def copy_evidence(client, output, marker):
    # Never copy saves/worlds, databases, or caches into CI artifacts.
    candidates = []
    for name in ('logs', 'crash-reports', 'screenshots'):
        directory = client / name
        if directory.is_dir() and not directory.is_symlink():
            candidates.extend(p for p in directory.rglob('*') if p.is_file() and not p.is_symlink())
    candidates.extend(client / (marker + ending) for ending in ('-passed.txt', '-failed.txt'))
    for path in candidates:
        if path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(client.resolve()):
            destination = output / path.relative_to(client)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)


def junit(path, name, elapsed, failure):
    suite = ET.Element('testsuite', name='minecraft.live', tests='1', failures=str(int(bool(failure))),
                       errors='0', skipped='0', time=f'{elapsed:.3f}')
    case = ET.SubElement(suite, 'testcase', classname='minecraft.live', name=name, time=f'{elapsed:.3f}')
    if failure:
        # Exception text may contain Minecraft's source-only control delimiters; keep XML valid.
        safe = ''.join(c for c in failure if c in '\t\n\r' or 0x20 <= ord(c) <= 0xD7FF
                       or 0xE000 <= ord(c) <= 0xFFFD or 0x10000 <= ord(c) <= 0x10FFFF)
        ET.SubElement(case, 'failure', message='Minecraft live probe failed').text = safe
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding='utf-8', xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', type=int, default=int(os.environ.get('CIRCLE_NODE_INDEX', '-1')))
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    if args.node not in range(4) or args.timeout < 1:
        parser.error('node must be 0..3 and timeout must be positive')
    if os.environ.get('CIRCLE_NODE_TOTAL', '4') != '4':
        parser.error('live probes require the four-node test job')
    prop, prefix, marker = PROBES[args.node]
    output = Path('build/circleci/live') / marker
    output.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix=prefix)).resolve()
    client = root / 'client'
    environment = os.environ.copy()
    environment.update(LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe')
    command = ['xvfb-run', '-a', '-s', '-screen 0 1280x1024x24 -nolisten tcp',
               './gradlew', 'runClient', '-P' + prop, '-PeconomyRunRoot=' + str(root),
               '-PcircleciLiveDir=' + str(output), '-I', '.circleci/gradle-tests.init.gradle',
               '--console=plain', '--no-daemon']
    started = time.monotonic()
    process = None
    failure = ''
    exit_code = 1

    def interrupted(signum, frame):
        raise InterruptedError('Runner received signal ' + str(signum))

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        print('Running ' + marker + ' in fresh disposable root ' + str(root), flush=True)
        with (output / 'gradle.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       env=environment, start_new_session=True)
            exit_code = process.wait(timeout=args.timeout)
        failed = client / (marker + '-failed.txt')
        passed = client / (marker + '-passed.txt')
        if exit_code != 0:
            failure = 'Gradle exited with status ' + str(exit_code)
        if failed.exists():
            failure += '\n' + failed.read_text(encoding='utf-8', errors='replace')
        if not passed.is_file():
            failure += '\nRequired pass marker was not produced: ' + passed.name
        if failure:
            exit_code = exit_code or 1
    except subprocess.TimeoutExpired:
        failure = 'Minecraft client exceeded the ' + str(args.timeout) + '-second deadline.'
        exit_code = 124
        stop_owned_process(process)
    except BaseException as error:
        failure = ''.join(traceback.format_exception(error))
        exit_code = 130 if isinstance(error, InterruptedError) else 1
        if process is not None:
            stop_owned_process(process)
    finally:
        try:
            copy_evidence(client, output, marker)
        except Exception:
            failure += '\nCould not collect probe evidence:\n' + traceback.format_exc()
            exit_code = exit_code or 1
        junit(Path('build/circleci/junit') / ('TEST-minecraft-live-' + marker + '.xml'),
              marker, time.monotonic() - started, failure)
        # This directory was just created above, never an existing player world.
        shutil.rmtree(root)
    if failure:
        print(failure, file=sys.stderr)
    else:
        print(marker + ' passed; evidence: ' + str(output))
    return exit_code if 0 <= exit_code <= 255 else 1


if __name__ == '__main__':
    sys.exit(main())
