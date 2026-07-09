#!/usr/bin/env python3
"""Ground-truth AirPlay-2 pairing via pyatv. Triggers the TV's on-screen code,
polls pin.txt for it (same convention as airplay_hap.py), prints credentials.

Run with the pyatv venv python. TV=192.168.1.233 by default.
"""
import asyncio, os, sys

import pyatv
from pyatv.const import Protocol

HOST = os.environ.get("TV", "192.168.1.233")
PIN_FILE = os.path.join(os.path.dirname(__file__), "pin.txt")


async def wait_for_pin(timeout=180):
    if os.path.exists(PIN_FILE):
        os.remove(PIN_FILE)
    print(f"Waiting for code: echo NNNN > {PIN_FILE}", flush=True)
    for _ in range(timeout):
        if os.path.exists(PIN_FILE):
            pin = open(PIN_FILE).read().strip()
            if pin:
                return pin
        await asyncio.sleep(1)
    sys.exit("timed out waiting for pin.txt")


async def main():
    loop = asyncio.get_running_loop()
    confs = await pyatv.scan(loop, hosts=[HOST], timeout=8)
    if not confs:
        sys.exit(f"no AirPlay device found at {HOST}")
    conf = confs[0]
    print(f"Found: {conf.name} {conf.address}")
    pairing = await pyatv.pair(conf, Protocol.AirPlay, loop)
    await pairing.begin()  # TV shows its code now
    pin = await wait_for_pin()
    pairing.pin(pin)
    await pairing.finish()
    if pairing.has_paired:
        print("*** PAIRED (pyatv). credentials: ***")
        print(pairing.service.credentials)
        open(os.path.join(os.path.dirname(__file__), "creds_pyatv.txt"), "w").write(
            pairing.service.credentials)
    else:
        print("pairing FAILED")
    await pairing.close()


asyncio.run(main())
