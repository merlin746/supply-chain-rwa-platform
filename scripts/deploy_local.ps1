 <#
 .SYNOPSIS
     面向供应链金融的 RWA 资产确权与流转平台 — 本地联盟链节点一键部署与合约部署脚本
 .DESCRIPTION
     该脚本执行以下操作：
       1. 检查 Node.js 及 npm 环境
       2. 安装 npm 依赖（Hardhat + OpenZeppelin）
       3. 启动本地 Hardhat 节点（模拟联盟链）
       4. 编译并部署 RWA_Core_Asset 合约
       5. 输出合约地址和演示凭证信息
 .NOTES
     运行前提: 已安装 Node.js 18+ 和 npm
     用法:      powershell -ExecutionPolicy Bypass -File scripts/deploy_local.ps1
#>
 
 $ErrorActionPreference = "Stop"
 $ROOT_DIR = Split-Path -Path $PSScriptRoot -Parent
 
 Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
 Write-Host "  RWA 平台 — 本地联盟链一键部署脚本" -ForegroundColor Cyan
 Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
 Write-Host ""
 
 # ─── 1. 环境检查 ───
 Write-Host "▶ [1/5] 检查 Node.js 环境..." -ForegroundColor Yellow
 try {
     $nodeVersion = node --version
     $npmVersion  = npm --version
     Write-Host "  ✅ Node.js: $nodeVersion" -ForegroundColor Green
     Write-Host "  ✅ npm:     $npmVersion" -ForegroundColor Green
 } catch {
     Write-Host "  ❌ 请先安装 Node.js (https://nodejs.org)" -ForegroundColor Red
     exit 1
 }
 
 # ─── 2. 安装依赖 ───
 Write-Host "▶ [2/5] 安装 npm 依赖..." -ForegroundColor Yellow
 Set-Location $ROOT_DIR
 npm install
 if ($LASTEXITCODE -ne 0) {
     Write-Host "  ❌ npm install 失败" -ForegroundColor Red
     exit 1
 }
 Write-Host "  ✅ 依赖安装完成" -ForegroundColor Green
 
 # ─── 3. 编译合约 ───
 Write-Host "▶ [3/5] 编译 Solidity 合约..." -ForegroundColor Yellow
 npx hardhat compile
 if ($LASTEXITCODE -ne 0) {
     Write-Host "  ❌ 合约编译失败" -ForegroundColor Red
     exit 1
 }
 Write-Host "  ✅ 合约编译成功" -ForegroundColor Green
 
 # ─── 4. 启动本地节点 ───
 Write-Host "▶ [4/5] 启动本地 Hardhat 节点（模拟联盟链）..." -ForegroundColor Yellow
 
 # 使用后台作业启动节点，保存 PID 以便后续清理
 $nodeJob = Start-Job -ScriptBlock {
     param($rootDir)
     Set-Location $rootDir
     npx hardhat node --hostname 0.0.0.0 --port 8545
 } -ArgumentList $ROOT_DIR
 
 Write-Host "  ⏳ 等待节点启动（5秒）..." -ForegroundColor Gray
 Start-Sleep -Seconds 5
 
 # 验证节点是否运行
 try {
     $response = Invoke-WebRequest -Uri "http://127.0.0.1:8545" -Method POST `
         -Body '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' `
         -ContentType "application/json" -TimeoutSec 5
     Write-Host "  ✅ 本地节点运行中 (http://127.0.0.1:8545)" -ForegroundColor Green
 } catch {
     Write-Host "  ⚠ 节点未在预期时间启动，尝试继续..." -ForegroundColor Yellow
 }
 
 # ─── 5. 部署合约 ───
 Write-Host "▶ [5/5] 部署 RWA_Core_Asset 合约..." -ForegroundColor Yellow
 npx hardhat run scripts/deploy.js --network localhost
 if ($LASTEXITCODE -ne 0) {
     Write-Host "  ❌ 合约部署失败" -ForegroundColor Red
     # 清理后台作业
     Stop-Job $nodeJob
     Remove-Job $nodeJob
     exit 1
 }
 Write-Host "  ✅ 合约部署成功！" -ForegroundColor Green
 
 Write-Host ""
 Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
 Write-Host "  部署完成！链信息:" -ForegroundColor Cyan
 Write-Host "  ·  RPC 端点:  http://127.0.0.1:8545" -ForegroundColor White
 Write-Host "  ·  链 ID:     31337 (Hardhat)" -ForegroundColor White
 Write-Host "  ·  合约:      RWA_Core_Asset" -ForegroundColor White
 Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
 Write-Host ""
 Write-Host "如需停止节点，请关闭此终端或运行: Stop-Job -Id $($nodeJob.Id)" -ForegroundColor Gray
 
 # 保持终端打开
 Read-Host "按 Enter 键停止节点并退出..."
 Stop-Job $nodeJob
 Remove-Job $nodeJob
