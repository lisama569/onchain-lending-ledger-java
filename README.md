# 本地链上借贷协议模拟服务（可恢复账本）

一个基于 Spring Boot 的**本地模拟**借贷协议：管理资金池（存款/借款/利息）、用户仓位（存款、借款、抵押品）与喂价，
支持清算与奖励分配。所有金额用 `BigDecimal`（禁止浮点），全部状态变更在**单账本锁**内完成
“惰性计息 → 业务校验 → 状态变更 → 守恒检查 → 原子快照落盘”，并通过 `requestId` 幂等保证
并发、异常输入与重启恢复后的结果**一致且可解释**。

> 模拟范围：单现金池资产 `CASH`（借贷币种）+ 单风险资产 `RISK`（抵押品）；
> 还款现金按“链下注入”建模，清算代还按“清算人存款支付”建模（与主流链上借贷协议一致）。

---

## 1. 目录与核心类

```
common/     ReasonCode（可区分失败原因码）、OperationResult、BusinessException
domain/     Asset、PoolState（池+惰性计息）、PositionState（仓位份额）、PriceTick、PriceSnapshot
model/      RateModel（利率模型）、HealthSnapshot（健康度判定）、LedgerSnapshot（快照）
ledger/     LedgerService（核心：锁/幂等/守恒/恢复）、SnapshotStore、FileSnapshotStore、SnapshotCodec
web/        LedgerController（REST）、GlobalExceptionHandler、Dtos
config/     LedgerProperties（ledger.* 全部参数）
```

---

## 2. 利率模型（`RateModel` + `PoolState.accrue`）

分段线性的**资金利用率利率曲线**（年化，按秒计息；多次计息按指数复利推进）：

```
利用率 u = totalBorrow / (cash + totalBorrow)              # 空池或无借款时 u=0

u <= 0.80(kink): borrowRate = 0.02(base) + u * 0.10        # 2% → 10%
u >  0.80(kink): borrowRate = 0.10 + (u - 0.80) * 0.60     # 10% → 22%(u=1)

supplyRate = borrowRate * u * (1 - 0.10 reserveFactor)
```

- 利用率越高利率越陡（kink 之后斜率 0.60），高利用率会抑制借款、吸引存款，利率随利用率合理变化。
- **惰性计息**：不逐用户记账。池维护 `borrowIndex / supplyIndex`（1e18 起步，单调上升），
  用户存借以“指数化份额”持有：`实际余额 = scaled 份额 × 当前指数`。
  每次操作前只推进一次全局指数，因此**并发操作不会重复计息**；停机期间利息在恢复时一次性补计，
  结果与协议持续在线等价。
- 利息的 `reserveFactor=10%` 部分计入协议储备 `totalReserves`（留在池内现金中）。
- 恒等式（每次提交前强制校验）：

```
cash + totalBorrow = Σ用户存款 + totalReserves
Σ用户借款份额     = 池借款份额
Σ用户存款份额     = 池存款份额
```

### 操作语义

| 操作 | 余额变动 | 关键校验（失败原因码） |
|---|---|---|
| supply 存入 | cash↑、存款份额↑ | `INVALID_AMOUNT` |
| withdraw 提取 | cash↓、存款份额↓ | `INSUFFICIENT_SUPPLY` / `INSUFFICIENT_CASH` / `PRICE_STALE` / `HEALTH_FACTOR_TOO_LOW` |
| borrow 借款 | cash↓、借款份额↑ | `INSUFFICIENT_CASH` / `PRICE_STALE` / `HEALTH_FACTOR_TOO_LOW` |
| repay 还款 | cash↑、借款份额↓（不超过当前债务） | `REPAY_EXCEEDS_DEBT` |
| collateral 存取 | RISK 数量变动 | `INSUFFICIENT_COLLATERAL` / `PRICE_STALE` / `HEALTH_FACTOR_TOO_LOW` |

所有金额必须为正数、小数位 ≤ 18；`requestId` 为必填幂等键。

---

## 3. 健康度与清算规则

### 3.1 喂价有效性（只有“有效且顺序正确”的价格参与计算）

每个喂价带 `seq`（单调序号）、`price`、`sourceTimeMillis`（源时间）。接收规则：

1. 价格必须为正且在合理量级、源时间不能是未来 → 否则 `PRICE_INVALID`；
2. `seq` 必须 **严格大于** 最新已接受序号 → 否则 `PRICE_SEQ_OLD`（乱序/重复喂价被忽略，**现价不变**）；
3. 与上一口价比，涨跌幅必须 ≤ `ledger.max-price-deviation-ratio`（默认 30%）
   → 否则 `PRICE_DEVIATION`，**异常抖动被直接拒绝，不会据此重算健康度或触发误清算**；
4. 价格有“有效期”：`now - sourceTime ≤ ledger.price-max-age-millis`（默认 120 秒）。
   过期状态下借款、提取、抵押提取、清算一律返回 `PRICE_STALE`，杜绝用陈旧价格误清算。

价格必须**渐进**变化才会被接受（例如 100→75 接受、100→50 拒绝），从而既允许正常的连续下跌导致清算，
又挡住单点插针。

### 3.2 健康度

```
collateralValue = 抵押品数量 × 价格
healthFactor    = collateralValue × liquidationThreshold(0.80) / debtValue
liquidatable    = debtValue > 0 且 healthFactor < 1
```

- 无债务：HF = +∞（返回 `null` 加文字说明），不可清算；
- 借款/提取/提取抵押都用“试算后”的仓位做 HF 校验，HF 必须 ≥ 1；
- 每个判定结果 `HealthSnapshot` 都带全部输入与公式文字（`reason`），日志与接口均可解释。

### 3.3 部分清算

- 仓位 `liquidatable` 才允许清算，否则 `POSITION_HEALTHY`；
- 单次最多代还 `债务 × maxLiquidationRatio`（默认 50%）→ 超出 `LIQUIDATION_AMOUNT_TOO_LARGE`；
- 清算人必须有足够存款支付代还金额 → 否则 `INSUFFICIENT_SUPPLY`；不能清算自己；
- 抵押划转：`seizedCollateral = repayAmount × (1 + liquidationBonus=5%) / price`
  （折价 5% 是清算人的奖励）；借款人抵押不足支付时返回 `INSUFFICIENT_COLLATERAL`，
  模拟协议拒绝产生坏账；
- 清算后池现金不变：清算人的存款份额与借款人的借款份额**同步核销**，
  `cash + totalBorrow = Σsupply + reserves` 依旧成立；RISK 只在借款人和清算人之间转移，总量不变。

---

## 4. 并发与幂等保证

- **单把公平锁（`ReentrantLock(fair=true)`）串行化所有写操作**：并发提取/借款不会透支池现金、
  不会超额扣减个人余额；计息在锁内每操作最多推进一次，无重复计息。
  （并发测试：32 线程各取 100，对 1000 存款恰好 10 笔成功，其余得到可区分的不足原因。）
- **requestId 全局限流幂等**：
  - 首次成功：执行一次并记录 `OK`；
  - 首次业务失败：原因码也记录（成功与否的结果与状态在**同一个快照**里原子落盘）；
  - 之后任何重复提交（含并发同时提交）：直接返回 `DUPLICATE_REQUEST` 并携带 `firstOutcome`，
    **不重新计息、不二次扣款、不二次放行**。
- 失败原因全部是稳定的枚举码（`ReasonCode`），HTTP 层统一包装为 `OperationResult`，
  不向前端抛裸异常。

---

## 5. 状态恢复语义

- 每次成功提交（以及业务失败记录幂等结果）后，把
  `{version, pool, positions, priceSnapshot, requestLog}` 用 JSON **原子写盘**
  （先写 `*.tmp`，再 `move(ATOMIC_MOVE, REPLACE_EXISTING)`），崩溃时文件要么是旧版要么新版，不会半写。
- 启动时若快照不存在 → 空账本启动；若快照损坏/不可读 → **拒绝启动**（fail-fast，避免状态分叉）。
- 恢复后按当前时间补计停机期间利息（指数模型天然支持）。
- `requestLog` 随快照恢复：历史成功/失败请求重启后重复提交依旧只重放；
  “未完成的操作”（尚未落盘）在重启中根本不存在，新 requestId 会正常执行——
  **不丢失已提交结果、不重复执行、不二次扣款**。
- 生产部署时应把快照路径（`ledger.snapshot-path`）放到持久化卷。

---

## 6. REST 接口

| 方法/路径 | 说明 |
|---|---|
| POST `/api/ledger/supply` `/withdraw` `/borrow` `/repay` | body: `{requestId,userId,amount}` |
| POST `/api/ledger/collateral/deposit` `/withdraw` | 同上（amount 为 RISK 数量） |
| POST `/api/ledger/liquidate` | `{requestId,liquidatorId,borrowerId,repayAmount}` |
| POST `/api/ledger/price` | `{seq,price,sourceTimeMillis?}`（sourceTime 缺省取当前时间） |
| GET `/api/ledger/state` | 池（含总存借/利用率/指数）、最新价、全部仓位、幂等日志条数 |
| GET `/api/ledger/health/{userId}` `/health` | 单用户/全部仓位健康度（含公式说明） |

示例：

```bash
curl -s localhost:8080/api/ledger/supply -H 'Content-Type: application/json' \
  -d '{"requestId":"s1","userId":"alice","amount":1000}'
curl -s localhost:8080/api/ledger/price  -H 'Content-Type: application/json' \
  -d '{"seq":1,"price":100}'
curl -s localhost:8080/api/ledger/state
```

---

## 7. 本地验证方法

要求：JDK 21+、Maven 3.9+（依赖可离线解析）。

```bash
mvn test            # 全部 22 个单测（含并发/恢复/清算/价格守卫/Web 冒烟）
mvn package         # 构建
mvn spring-boot:run # 启动（默认快照 data/ledger-snapshot.json，可用 -Dledger.snapshot-path=... 覆盖）
```

关键单测日志会打印**输入、状态变化与判定依据**（利用率、指数、HF 公式、拒绝原因）：

| 测试类 | 覆盖点 |
|---|---|
| `RateModelTest` | 利率曲线锚点（2%/10%/22%）、单调上升、存贷利率传导 |
| `PoolFlowTest` | 存/押/借/一年计息/还/取全链路与守恒；非法输入/余额不足/超额还款/HF 不足等可区分原因 |
| `PriceGuardTest` | 乱序与重复序号、非法价格、30% 异常波动拒绝、价格过期阻断借款与清算、渐进下跌可清算 |
| `LiquidationTest` | 部分清算上限、5% 奖励划转数量、池/借款人/清算人三方守恒、健康仓位不可清算 |
| `ConcurrencyTest` | 32 线程并发提取不超支；同 requestId 并发/串行重复只执行一次；首次失败也幂等 |
| `RecoveryTest` | 重启恢复全部状态、停机一年补息（880/1072/8）、历史请求只重放、快照文件即事实来源 |
| `WebApiTest` | REST 成功/`DUPLICATE_REQUEST`/`INSUFFICIENT_SUPPLY` 与状态查询 |

可调整参数（`application.properties`）：

```properties
ledger.snapshot-path=data/ledger-snapshot.json
ledger.price-max-age-millis=120000
ledger.max-price-deviation-ratio=0.30
ledger.liquidation-threshold=0.80
ledger.liquidation-bonus=0.05
ledger.max-liquidation-ratio=0.50
```
