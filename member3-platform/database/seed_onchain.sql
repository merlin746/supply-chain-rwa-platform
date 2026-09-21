-- 链上演示附加脚本：将企业/用户钱包地址切换为 Hardhat 本地节点账户（#1~#5）
-- 使用时机：启动 hardhat node 并部署成员1合约、准备以链上模式演示时，
-- 在执行完 rwa_supply_chain.sql 之后执行本脚本。
-- 与 team-repo scripts/deploy.js 的角色分配一一对应。

USE rwa_supply_chain;

UPDATE enterprise SET wallet_address = '0x70997970C51812dc3A010C7d01b50e0d17dc79C8' WHERE id = 1; -- 比亚迪（账户#1）
UPDATE enterprise SET wallet_address = '0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC' WHERE id = 2; -- 科达利（账户#2）
UPDATE enterprise SET wallet_address = '0x90F79bf6EB2c4f870365E785982E1f101E93b906' WHERE id = 3; -- 聚能永拓（账户#3）
UPDATE enterprise SET wallet_address = '0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65' WHERE id = 4; -- 长园特发（账户#4）
UPDATE enterprise SET wallet_address = '0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc' WHERE id = 5; -- 建设银行（账户#5）

UPDATE sys_user SET wallet_address = '0x70997970C51812dc3A010C7d01b50e0d17dc79C8' WHERE enterprise_id = 1;
UPDATE sys_user SET wallet_address = '0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC' WHERE enterprise_id = 2;
UPDATE sys_user SET wallet_address = '0x90F79bf6EB2c4f870365E785982E1f101E93b906' WHERE enterprise_id = 3;
UPDATE sys_user SET wallet_address = '0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65' WHERE enterprise_id = 4;
UPDATE sys_user SET wallet_address = '0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc' WHERE enterprise_id = 5;

-- 链上模式建议清掉链下种子凭证（链上 tokenId 从 1 开始自增，避免展示混淆）；
-- 按需取消注释：
-- DELETE FROM rwa_token;
-- UPDATE invoice SET status = 'PENDING' WHERE invoice_code = 'INV-2026-0001';
