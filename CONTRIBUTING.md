# 代码推送规则须知

本项目面向团队协作和公开仓库提交。为了保证仓库历史清晰、合约可复现、敏感信息不泄露，所有成员在提交和推送代码前必须遵守以下规则。

## 一、推送前必做

1. **安装依赖**：在本地执行 `npm install`，确保依赖与 `package-lock.json` 一致。
2. **编译通过**：执行 `npm run compile`，确保 Solidity 合约可以正常编译。
3. **测试通过**：执行 `npm run test`，确保现有测试不回归。
4. **清理产物**：不要提交 `node_modules/`、`artifacts/`、`cache/` 和 `coverage/` 等生成文件。
5. **检查敏感信息**：不要提交 `.env`、私钥、助记词、钱包私钥、数据库密码、API 密钥或内部服务器地址。仅可提交 `.env.example` 模板。

## 二、分支与提交规范

### 分支命名

| 类型 | 命名示例 |
| --- | --- |
| 功能开发 | `feature/rwa-mint` |
| 缺陷修复 | `fix/transfer-approval` |
| 文档更新 | `docs/readme` |
| 合约重构 | `refactor/access-control` |

### 提交信息格式

提交信息建议采用 `type(scope): subject` 格式：

```text
feat(contract): add ERC-3525 split and merge logic
fix(test): correct settle status assertion
docs(readme): add local deployment guide
chore(ci): add hardhat test workflow
```

常用类型：

- `feat`：新增功能
- `fix`：修复缺陷
- `docs`：文档变更
- `refactor`：重构代码
- `test`：测试相关
- `chore`：构建、配置或工具链调整

## 三、代码质量要求

1. 合约函数必须显式声明可见性，公开接口应补充 `@notice`、`@dev`、`@param`、`@return` 注释。
2. 涉及资金或权限的外部调用必须使用 `ReentrancyGuard`、`AccessControl` 或等价机制。
3. 新增合约功能必须同步补充或更新单元测试。
4. 不要在合约中写死真实企业私钥、生产 RPC 地址或真实合同敏感字段。
5. 事件日志应覆盖资产签发、流转、冻结、废止和结算等关键状态变化。

## 四、推送流程

```bash
git status
git add <仅添加本任务相关文件>
git commit -m "feat(contract): ..."
git pull --rebase origin main
git push origin <branch-name>
```

直接推送 `main` 前，应确认以下事项：

- 提交信息清晰，且不包含“临时提交”“test”等无意义说明。
- 合约编译和测试均已通过。
- 没有把本地临时文件、密钥文件或构建产物加入暂存区。

## 五、禁止事项

- 禁止提交任何形式的真实私钥、助记词或生产环境密钥。
- 禁止使用 `git add -A` 直接提交包含 `node_modules`、`artifacts`、`cache` 的变更。
- 禁止在提交信息中夹带企业交易金额、客户合同号等未脱敏商业数据。
- 禁止强制推送共享分支，除非已与团队明确确认。
- 禁止将竞赛文档中的真实企业名称替换或删除，但涉及具体敏感合同信息时需脱敏处理。

## 六、Pull Request 检查清单

- [ ] 合约可编译：`npm run compile`
- [ ] 测试全部通过：`npm run test`
- [ ] 未包含 `.env`、私钥或构建产物
- [ ] 新增功能有对应测试
- [ ] 提交信息符合规范
- [ ] 文档已同步更新
