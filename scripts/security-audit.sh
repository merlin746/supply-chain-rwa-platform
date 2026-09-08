#!/usr/bin/env bash
set -euo pipefail

# Kept as a Unix convenience wrapper. The Node.js implementation is the
# canonical cross-platform entry point and is also exposed via npm.
exec node "$(dirname "$0")/security-audit.js" "$@"
