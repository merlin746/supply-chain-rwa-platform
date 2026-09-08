const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const outDir = path.resolve(process.argv[2] || "reports/security");
fs.mkdirSync(outDir, { recursive: true });
const bin = (name) => (process.platform === "win32" && name === "npx" ? "npx.cmd" : name);

function run(command, args, options = {}) {
  const result = spawnSync(bin(command), args, { encoding: "utf8", ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || "").trim();
    throw new Error(`${command} failed with exit code ${result.status}${detail ? `: ${detail}` : ""}`);
  }
  return result.stdout || "";
}

function requireCommand(command, installHint) {
  const result = spawnSync(bin(command), ["--version"], { stdio: "ignore" });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} is not installed; ${installHint}`);
  }
}

try {
  requireCommand("slither", "install with: pipx install slither-analyzer");
  requireCommand("myth", "install with: pipx install mythril");
  run("npx", ["hardhat", "compile"], { stdio: "inherit" });

  const slitherPath = path.join(outDir, "slither.json");
  run("slither", [".", "--config-file", "slither.config.json", "--json", slitherPath], { stdio: "inherit" });
  if (!fs.existsSync(slitherPath)) throw new Error("slither completed without producing slither.json");
  for (const [name, contract] of [["core", "RWA_Core_Asset.sol"], ["circulation", "RWA_Circulation.sol"], ["settlement", "RWA_Settlement.sol"]]) {
    const output = run("myth", ["analyze", `contracts/${contract}`, "--execution-timeout", "120", "--output", "json"]);
    fs.writeFileSync(path.join(outDir, `mythril-${name}.json`), output);
  }

  if (process.env.ZAP_TARGET) {
    requireCommand("zap-baseline.py", "install OWASP ZAP and add zap-baseline.py to PATH");
    run("zap-baseline.py", ["-t", process.env.ZAP_TARGET, "-J", path.join(outDir, "zap.json"), "-r", path.join(outDir, "zap.html")], { stdio: "inherit" });
  } else {
    fs.writeFileSync(path.join(outDir, "zap.SKIPPED"), "OWASP ZAP skipped: set ZAP_TARGET to an authorized test URL\n");
  }
  console.log(`Security scan artifacts written to ${outDir}`);
} catch (error) {
  console.error(error.message || error);
  process.exitCode = 1;
}
