#!/usr/bin/env python3
"""Summarize a Mac<->TV AirPlay capture: which channels the real sender opens.
Answers: is there PTP (UDP 319/320) timing? legacy NTP (7010)? how many TCP
data streams and their volumes? -> decides timing-vs-FairPlay for our sender.

Usage: analyze_pcap.py cap.pcap [TV_IP]
"""
import collections, subprocess, sys

pcap = sys.argv[1]
TV = sys.argv[2] if len(sys.argv) > 2 else "192.168.1.233"

# one line per packet: proto srcip:sport dstip:dport len
out = subprocess.run(
    ["tcpdump", "-nn", "-r", pcap, "-q"], capture_output=True, text=True).stdout

udp_ports = collections.Counter()
tcp_bytes = collections.Counter()
tcp_pkts = collections.Counter()
proto_count = collections.Counter()

for line in out.splitlines():
    if " IP " not in line:
        continue
    try:
        body = line.split(" IP ", 1)[1]
        left, right = body.split(" > ", 1)
        proto = "UDP" if "UDP" in right else ("TCP" if "tcp" in right or "Flags" in right else "?")
        # endpoints look like a.b.c.d.PORT
        def hostport(s):
            s = s.strip().rstrip(":").split(",")[0]
            ip, port = s.rsplit(".", 1)
            return ip, int(port)
        sip, sport = hostport(left)
        dip, dport = hostport(right.split(":")[0].split(" ")[0])
        proto_count[proto] += 1
        length = 0
        if "length" in right:
            length = int(right.rsplit("length", 1)[1].split()[0].rstrip(":"))
        if proto == "UDP":
            for p in (sport, dport):
                if p != 0:
                    udp_ports[p] += 1
        else:
            key = tuple(sorted([(sip, sport), (dip, dport)]))
            tcp_bytes[key] += length
            tcp_pkts[key] += 1
    except Exception:
        continue

print("=== protocol packet counts ===")
for p, c in proto_count.most_common():
    print(f"  {p}: {c}")

print("\n=== UDP ports seen (count) ===")
for port, c in udp_ports.most_common(20):
    tag = ""
    if port in (319, 320): tag = "  <-- PTP (AirPlay-2 timing!)"
    if port == 7010: tag = "  <-- legacy NTP timing"
    if port == 5353: tag = "  (mDNS)"
    print(f"  {port}: {c}{tag}")

print("\n=== TCP streams (endpoints -> pkts, bytes) ===")
for key, b in tcp_bytes.most_common(15):
    a, c = key
    print(f"  {a[0]}:{a[1]} <-> {c[0]}:{c[1]}  pkts={tcp_pkts[key]} bytes={b}")
