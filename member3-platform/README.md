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

- JDK 21 或更高版本
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

## 零配置本地 Demo（推荐）

该模式使用内存数据库并关闭链节点连接，不需要安装 MySQL、Docker 或启动 Hardhat。
每次重启后端都会恢复演示种子数据。

终端 1：

```bash
cd backend
mvn clean package
java -jar target/rwa-supply-chain-1.0.0.jar --spring.profiles.active=demo
```

终端 2：

```bash
cd frontend
npm ci
npm run dev
```

打开 http://127.0.0.1:5178 。前端固定使用 5178，端口占用时不会自动跳到另一项目的地址。后端健康检查为
http://localhost:8080/api/health 。完整流程由后端集成测试覆盖，可用
`cd backend && mvn test` 随时回归。

## Docker + MySQL Demo

Docker 镜像会自行构建后端 JAR，无需预先运行 Maven：

```bash
docker compose up --build
```

旧版 Docker Compose 可使用 `docker-compose up --build`。启动后访问：

- 前端：http://127.0.0.1:5178
- 后端：http://localhost:8080/api/health

如果手工使用本机 MySQL，则先执行 `database/rwa_supply_chain.sql`，再通过
`DB_USER`、`DB_PASSWORD` 配置账号，并以默认 profile 启动后端。

## 演示账号

前端打开后会进入登录页，演示账号（密码均为 `123456`）：

| 账号 | 角色 |
| --- | --- |
| byd | 比亚迪 · 核心企业 |
| kdl | 科达利 · 一级供应商 |
| jnyt | 聚能永拓 · 二级供应商 |
| cytf | 长园特发 · 三级供应商 |
| ccb | 建设银行 · 资金方 |

登录后，总览和业务数据按当前企业展示；核心企业进入资产确权工作台，供应商进入资产流转工作台，银行进入融资管理工作台。切换演示角色需先退出当前账号。

演示数据包括 200 万元待流转凭证、80 万元待审核发票，以及一笔已放款且到期的 30 万元融资。银行可对 `LA-DEMO-MATURED` 执行“到期兑付”，立即验证清算与授信释放。

## 业务规则

- 发票金额必须大于零且精确到分，开票日与到期日有效，发票编号不可重复。
- 仅已审核的发票可以确权；重复点击不会重复开立或占用授信。
- 拆分保持金额守恒，继承原凭证到期日；已到期、已质押或有进行中融资的凭证不可拆分。
- 融资申请校验持有人、金融机构、金额、利率和期限。申请期间锁定流转，驳回后解除；审批后凭证处于质押状态，放款不直接清算。
- 到期兑付要求已放款且已到期，完成后凭证与融资同时进入终态，并一次性释放对应资产的核心企业授信。
- 链上核验同时检查持有人和余额；节点不可用或数据不一致时明确显示失败。

后端接口要求 Bearer 会话；历史演示密码首次登录后升级为带随机盐的 PBKDF2。会话存放在内存，后端重启后需重新登录。

## 运行模式

系统支持**链下/链上双模**（`web3.enabled` 开关），已对接成员1的
[RWA_Core_Asset.sol](https://github.com/merlin746/supply-chain-rwa-platform)（ERC-3525）：

- **链下存证模式**（`enabled: false`）：全流程落数据库（Demo 为 H2，常规部署为 MySQL），交易哈希以 `OFFCHAIN-` 占位，无需链节点即可演示。
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
#    web3.contract-address，并设 web3.enabled: true

# 5. 准备本地配置（演示私钥与本机数据库密码，不入库）
cp backend/application-local.yml.example backend/application-local.yml
# 按需修改 application-local.yml 中的 spring.datasource.password

# 6. 启动后端（激活 local 配置）
cd backend && java -jar target/rwa-supply-chain-1.0.0.jar --spring.profiles.active=local
```

密钥管理约定：数据库密码经环境变量 `DB_PASSWORD` 注入（application.yml 默认值
仅为占位符）；企业托管私钥只放在被 .gitignore 排除的 `application-local.yml`。
`application-local.yml.example` 中的 Hardhat 账户 #1~#5 私钥是 hardhat node
启动时打印的公开测试账户，仅用于本地演示；**真实私钥一律不入库不入仓**。

状态机说明：放款（disburse）只划转资金，凭证保持质押（PLEDGED）；
到期兑付走 `PUT /api/loan/{id}/settle`，要求凭证已到期，链上调用
`settleAsset` 后凭证才进入 SETTLED——与合约状态机一致。
演示兑付流程：使用 Demo 种子中的已到期融资 `LA-DEMO-MATURED`，在银行工作台点击“到期兑付”。

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
- AssetSplit / TransferValue
- AssetStatusChanged
- AssetRevoked
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
