# 面向供应链金融的 RWA 资产确权与流转平台

面向供应链金融场景的联盟链 RWA 资产确权与流转平台，基于 Solidity + Hardhat + OpenZeppelin 构建核心智能合约，以 **ERC-3525（半同质化代币，SFT）** 对应收账款类现实资产进行链上确权、拆分流转与生命周期管理。

## 项目背景

传统供应链金融存在三大痛点：

- **信用无法穿透**：核心企业信用通常只能覆盖一级供应商，二、三级中小微企业难以获得低成本融资。
- **商业隐私泄露**：交易金额、采购数量等敏感信息一旦上链，可能暴露给竞争对手或无关节点。
- **重复质押与欺诈风险**：链下凭证易伪造、易重复融资，金融机构风控成本高。

本项目通过 RWA 通证化技术，将真实应收账款映射为链上可信数字资产，实现 **高可信确权 + 信用深度穿透 + 分级隐私保护**。

## 典型落地场景

以比亚迪新能源汽车供应链融资为例：

| 角色 | 企业 | 说明 |
| --- | --- | --- |
| 核心企业 | 比亚迪股份有限公司 | 签发 1000 万元 RWA 账款凭证，提供 AAA 级信用背书 |
| 一级供应商 | 深圳市科达利精密工业股份有限公司 | 接收凭证并向下游拆分 300 万元 |
| 二级供应商 | 深圳市聚能永拓电子有限公司 | 继续拆分 100 万元给三级供应商 |
| 三级供应商 | 深圳市长园特发科技有限公司 | 持拆分凭证向银行申请融资贴现 |
| 金融机构 | 中国建设银行深圳市分行 | 核验凭证后提供链上融资与到期清算 |

## 核心特性

- **ERC-3525 资产确权**：每一笔应收账款凭证拥有唯一 `tokenId`，同时支持按面值拆分、合并与流转。
- **角色权限控制**：基于 OpenZeppelin `AccessControl` 划分核心企业、供应商、金融机构、审计方四类角色。
- **资产状态机**：`Active`、`Frozen`、`Revoked`、`Settled` 四种状态覆盖资产完整生命周期。
- **隐私保护设计**：预留 Pedersen 承诺与分级可见性扩展点，支持“可验证、不可见”的金额守恒校验。
- **安全开发约束**：使用 `ReentrancyGuard`、权限校验和输入检查，降低重入、越权调用等合约风险。

## 系统架构

```text
┌────────────────────────────────────────────────────────────────┐
│ 呈现层  核心企业 Portal | 供应商 Portal | 金融机构 Portal | 资产拓扑大屏 │
├────────────────────────────────────────────────────────────────┤
│ 服务层  Spring Boot | Web3j/Ethers.js | 数据脱敏/KMS | MySQL + Redis │
├────────────────────────────────────────────────────────────────┤
│ 合约层  RWA 确权(ERC-3525) | 拆分流转与清算 | RBAC | Pedersen 承诺 │
├────────────────────────────────────────────────────────────────┤
│ 链底座  FISCO BCOS / Ethereum Private Chain                      │
└────────────────────────────────────────────────────────────────┘
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 智能合约 | Solidity ^0.8.26、ERC-3525、OpenZeppelin 5.x |
| 开发框架 | Hardhat 2.x |
| 区块链底座 | FISCO BCOS / Geth 私有链 |
| 后端（规划） | Java 17 + Spring Boot 3.x + Web3j |
| 前端（规划） | Vue 3 + Element Plus + AntV G6 |
| 隐私密码学（规划） | Pedersen 承诺 + AES-256 + RBAC |

## 目录结构

```text
.
├── contracts/                 # Solidity 智能合约
│   └── RWA_Core_Asset.sol      # RWA 资产确权主合约
├── scripts/
│   ├── deploy.js               # Hardhat 部署脚本
│   └── deploy_local.ps1        # Windows 本地一键部署脚本
├── test/
│   └── RWA_Core_Asset.test.js  # 合约测试
├── docs/
│   └── 平台总体技术架构与链上数据结构设计文档.md
├── hardhat.config.js
├── package.json
├── package-lock.json
├── .env.example
└── README.md
```

## 快速开始

### 环境要求

- Node.js 18 或更高版本
- npm 或 pnpm

### 安装依赖

```bash
npm install
```

### 编译合约

```bash
npm run compile
```

### 运行测试

```bash
npm run test
```

### 本地部署

方式一：使用 npm 脚本部署到本地节点。

```bash
npm run node
```

另开终端执行：

```bash
npm run deploy:local
```

方式二：在 Windows 上运行一键部署脚本。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy_local.ps1
```

### 配置环境变量

将 `.env.example` 复制为 `.env`，并按需填写私钥、RPC 地址和数据库信息。`.env` 已被 `.gitignore` 排除，请勿提交真实私钥。

```bash
cp .env.example .env
```

## 合约说明

`RWA_Core_Asset.sol` 是当前核心合约，主要能力包括：

- `mintRWAAsset`：核心企业签发 RWA 资产凭证。
- `transferFrom` / `safeTransferFrom`：资产流转与所有权转移。
- `approve` / `setApprovalForAll`：授权管理。
- `revokeAsset`：核心企业废止凭证。
- `settleAsset`：到期清算并标记为已结算。
- `setAssetFrozen`：金融机构或监管方冻结/解冻资产。
- `getAssetInfo`、`getIssuedAssets`、`getHeldAssets`、`getAssetsBySlot`：资产查询接口。

## 文档

- [《面向供应链金融的 RWA 资产确权与流转平台》开题报告](./《面向供应链金融的 RWA 资产确权与流转平台》开题报告.md)
- [平台总体技术架构与链上数据结构设计文档](./docs/平台总体技术架构与链上数据结构设计文档.md)
- [代码推送规则须知](./CONTRIBUTING.md)

## 提交与推送规范

本项目要求提交前执行编译和测试，统一分支命名与提交信息格式，禁止提交 `.env`、私钥、助记词及构建产物。详见 [CONTRIBUTING.md](./CONTRIBUTING.md)。
