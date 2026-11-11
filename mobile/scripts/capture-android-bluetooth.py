#!/usr/bin/env python3
"""Read-only, bounded capture of the Android Bluetooth process for Hendrix.

Does not clear logs, change settings, pair/unpair devices or restart services.
Run before reconnecting Hendrix. USB debugging must be authorized on the phone.
"""
import argparse
from datetime import datetime
from pathlib import Path
import os
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='ADB device selector; required if multiple devices are present')
    parser.add_argument('--seconds', type=int, default=90, choices=range(10, 181), metavar='10..180')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get('ANDROID_HOME', root / '.toolchain/android'))
    adb = str(sdk / 'platform-tools/adb')

    def read(command):
        return subprocess.check_output(command, text=True, timeout=15).strip()

    rows = [line.split() for line in read([adb, 'devices']).splitlines()[1:] if line.strip()]
    authorized = [row[0] for row in rows if len(row) >= 2 and row[1] == 'device']
    if args.serial:
        if args.serial not in authorized:
            raise SystemExit('Selected phone is not connected and authorized for USB debugging.')
        serial = args.serial
    elif len(authorized) == 1:
        serial = authorized[0]
    else:
        raise SystemExit('Connect and authorize one phone, or specify --serial. No capture started.')
    device = [adb, '-s', serial]
    pids = read(device + ['shell', 'pidof', 'com.android.bluetooth']).split()
    if len(pids) != 1 or not pids[0].isdigit():
        raise SystemExit('Bluetooth process not found. No broader device logs were collected.')
    out = root / 'build/bluetooth-diagnostics' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    out.mkdir(parents=True)
    metadata = []
    for key in ('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.fingerprint'):
        metadata.append(key + '=' + read(device + ['shell', 'getprop', key]))
    (out / 'device.txt').write_text('\n'.join(metadata) + '\n')
    path = out / 'bluetooth-system.log'
    print('Bluetooth capture started. Reconnect Hendrix on the Mac now; leave it connected until the failure.', flush=True)
    print(f'Capturing for at most {args.seconds} seconds. Output: {out}', flush=True)
    with path.open('wb') as stream, (out / 'capture-errors.txt').open('wb') as errors:
        process = subprocess.Popen(device + ['logcat', '-b', 'main', '-b', 'system', '-b', 'crash',
                                              '-v', 'threadtime', '-T', '1', '--pid=' + pids[0]],
                                   stdout=stream, stderr=errors)
        try:
            deadline = time.monotonic() + args.seconds
            while process.poll() is None and time.monotonic() < deadline and path.stat().st_size < 20_000_000:
                time.sleep(0.25)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    if process.returncode not in (0, -15) or path.stat().st_size == 0:
        raise SystemExit(f'Capture incomplete. Inspect capture-errors.txt locally: {out}')
    print('Capture finished. Bluetooth logs remain local; no upload or settings change was performed.')


if __name__ == '__main__':
    main()
