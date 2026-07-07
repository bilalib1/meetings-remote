#!/bin/bash
# Start the Zoom Remote control server. Grant Terminal Accessibility
# permission first (System Settings > Privacy & Security > Accessibility).
cd "$(dirname "$0")"
exec python3 server/zoom_control_server.py --port "${1:-8765}"
