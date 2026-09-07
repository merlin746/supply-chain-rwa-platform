#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-reports/security}"
mkdir -p "$OUT_DIR"

if ! command -v slither >/dev/null 2>&1; then
  echo "slither is not installed; install with: pipx install slither-analyzer" >&2
  exit 127
fi
if ! command -v myth >/dev/null 2>&1; then
  echo "mythril is not installed; install with: pipx install mythril" >&2
  exit 127
fi

npx hardhat compile
slither . --config-file slither.config.json --json "$OUT_DIR/slither.json" || true
myth analyze contracts/RWA_Core_Asset.sol --execution-timeout 120 --output json > "$OUT_DIR/mythril-core.json" || true
myth analyze contracts/RWA_Circulation.sol --execution-timeout 120 --output json > "$OUT_DIR/mythril-circulation.json" || true
myth analyze contracts/RWA_Settlement.sol --execution-timeout 120 --output json > "$OUT_DIR/mythril-settlement.json" || true

if [[ -n "${ZAP_TARGET:-}" ]]; then
  if ! command -v zap-baseline.py >/dev/null 2>&1; then
    echo "ZAP_TARGET is set but zap-baseline.py is not installed" >&2
    exit 127
  fi
  zap-baseline.py -t "$ZAP_TARGET" -J "$OUT_DIR/zap.json" -r "$OUT_DIR/zap.html" || true
else
  echo "OWASP ZAP skipped: set ZAP_TARGET to an authorized test URL" | tee "$OUT_DIR/zap.SKIPPED"
fi

echo "Security scan artifacts written to $OUT_DIR"
