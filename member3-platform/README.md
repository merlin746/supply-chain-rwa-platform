# RWA Supply Chain Finance — Member 3

成员3负责：
1. Spring Boot + MyBatis-Plus + MySQL 后端
2. Web3j 连接联盟链节点
3. 链上事件轮询同步到 MySQL
4. Vue 3 + Element Plus 三端 Portal
5. ECharts 信用穿透拓扑
6. Docker 打包部署
7. Demo 演示流程

## 推荐版本

- JDK 24
- Spring Boot 3.5.16
- Maven 3.9.x
- MyBatis-Plus 3.5.17
- MySQL 8.0/8.4
- web3j 4.13.0
- Node.js 22 LTS
- Vue 3 + Vite
- Element Plus 2.x
- Axios 1.x
- Vue Router 4.x
- ECharts 6.x

## 启动顺序

### 1. 数据库

执行:
`database/rwa_supply_chain.sql`

默认数据库:
`rwa_supply_chain`

默认连接:
localhost:3306
root / 123456

### 2. 后端

修改:
`backend/src/main/resources/application.yml`

重点修改:
- MySQL 密码
- `web3.rpc-url`
- `web3.contract-address`

然后：
```bash
cd backend
mvn clean package -DskipTests
java -jar target/rwa-supply-chain-1.0.0.jar
```

后端：
http://localhost:8080

健康检查：
http://localhost:8080/api/health

### 3. 前端

```bash
cd frontend
npm install
npm run dev
```

前端：
http://localhost:5173

### 4. Docker

```bash
docker compose up --build
```

前端：
http://localhost:5173
后端：
http://localhost:8080

## 演示账号

前端打开后会进入登录页，演示账号（密码均为 `123456`）：

| 账号 | 角色 |
| --- | --- |
| byd | 比亚迪 · 核心企业 |
| kdl | 科达利 · 一级供应商 |
| jnyt | 聚能永拓 · 二级供应商 |
| cytf | 长园特发 · 三级供应商 |
| ccb | 建设银行 · 资金方 |

## 运行模式

系统支持**链下/链上双模**（`web3.enabled` 开关），已对接成员1的
[RWA_Core_Asset.sol](https://github.com/merlin746/supply-chain-rwa-platform)（ERC-3525）：

- **链下存证模式**（`enabled: false`）：全流程落 MySQL，交易哈希以 `OFFCHAIN-` 占位，无需链节点即可演示。
- **链上模式**（`enabled: true`）：凭证开立走 `mintRWAAsset`、拆分走 `transferFrom(fromTokenId, to, value)`，
  tokenId 与 txHash 取自真实交易回执；银行核验接口追加链上余额/持有方交叉校验；
  事件同步服务每 10 秒解码 `AssetCreated/AssetSplit/AssetStatusChanged/AssetSettled/AssetRevoked` 回写业务库。

金额单位约定：链上 uint256 以「分」为单位，库中以「元」为单位（×100 换算，见 `ChainTxService`）。

### 本地链上模式启动步骤

```bash
# 1. 启动 Hardhat 本地节点（team-repo 目录，即成员1仓库）
npx hardhat node

# 2. 另开终端部署合约（自动授予角色并签发一笔 1000 万演示凭证）
npx hardhat run scripts/deploy.js --network localhost

# 3. 导入数据库后，切换演示钱包为 Hardhat 账户
mysql --default-character-set=utf8mb4 -uroot -p < database/rwa_supply_chain.sql
mysql --default-character-set=utf8mb4 -uroot -p < database/seed_onchain.sql

# 4. 把部署输出的合约地址填入 backend/src/main/resources/application.yml
#    web3.contract-address，并设 web3.enabled: true，然后启动后端
```

`application.yml` 中预置了 Hardhat 账户 #1~#5 的公开演示私钥（`web3.keys`），
与 deploy.js 的角色分配一一对应，本地开箱即用；**接入真实节点时必须替换且不得提交真实私钥**。

注意：`settleAsset` 合约要求凭证已到期（`block.timestamp >= maturityDate`），
演示凭证未到期时一键放款只落库清算（到期自动兑付属于成员2清算合约的职责）。

接口明细见 `docs/成员3接口与联调说明.md`，演示流程见 `docs/Demo演示脚本.md`。

## 和成员1/2的对接

成员1完成 RWA_Core_Asset.sol 后，把：
- 合约地址
- ABI JSON

填入：
`backend/src/main/resources/contracts/RWA_Core_Asset.json`

然后在 application.yml 设置：
`web3.contract-address`

成员2如果提供 Circulation / Settlement ABI，可以继续放入 contracts 目录。

本项目不硬编码成员1/2的真实私钥。前端也不保存私钥。

## 当前同步策略

EventSyncService 每 10 秒轮询 eth_getLogs：
- AssetCreated
- AssetBurned
- TransferValue
- FinancingApplied
- AssetSettled

为了兼容成员1/2最终 ABI，事件同步采用：
- 主题 topic0 识别
- raw topics/data 保存
- 可配置 event signatures
- 同步结果写入 blockchain_event 和 rwa_token

如果最终事件名称/参数发生变化，只需修改 EventSyncService 的 signature 常量和解析方法。

## Demo 建议

1. 核心企业登录/进入核心企业 Portal
2. 创建一笔应收账款凭证
3. 展示凭证 Token ID、金额、到期日
4. 切换供应商 Portal
5. 展示供应商持有资产
6. 执行拆分
7. 展示信用穿透拓扑
8. 切换银行 Portal
9. 展示融资申请
10. 点击链上真实性核验
11. 展示交易 Hash / 区块号 / 链上状态
12. 展示三端联动

> 注意：这是比赛 Demo 工程模板。真正接入 FISCO BCOS 时，RPC/Web3j 的兼容层要按你们最终选择的节点协议调整；若成员1使用标准 Ethereum JSON-RPC 私链/Geth，本项目可以直接使用 Web3j。
