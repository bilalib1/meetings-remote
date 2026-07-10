#!/usr/bin/env python3
"""Store-and-forward stdin->stdout with a constant delay (seconds).

Genuine transport latency for AV-sync testing: unlike setpts (which shifts
timestamps but not arrival), this holds every byte for N seconds, exactly like
a laggy network path. Used by rtsp_delayed_relay.sh.
"""
import sys, time, threading, collections

delay = float(sys.argv[1]) if len(sys.argv) > 1 else 0.5
q = collections.deque()
done = False

def reader():
    global done
    while True:
        chunk = sys.stdin.buffer.read1(65536)
        if not chunk:
            done = True
            return
        q.append((time.monotonic() + delay, chunk))

t = threading.Thread(target=reader, daemon=True)
t.start()
out = sys.stdout.buffer
while not (done and not q):
    if q and q[0][0] <= time.monotonic():
        out.write(q.popleft()[1])
        out.flush()
    else:
        time.sleep(0.005)
