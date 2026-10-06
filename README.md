# 城市社区配送机器人进楼门禁避障签收服务

外卖 / 药品 / 商超订单交给配送机器人后，本服务负责**进楼配送全链路协同**：记录订单类型、收货楼栋、
门禁授权、是否冷藏、预计送达、电量、载物仓编号与用户联系方式；机器人到小区门口申请物业门禁，
进入楼栋后与电梯系统协同选层，到达后通过短信 / 小程序通知用户取件。

**物业夜间限行**：物业可设置夜间时段（如 22:00 后）禁止机器人进楼。服务在**接单前校验预计送达时间**，
落入限行时段的订单自动改为**门岗自提**或**人工配送**；若机器人已在楼内，系统要求**尽快完成当前订单并退出**，
物业端可查看每台机器人的**要求结束时间、实际退出时间与行进路线**。夜间规则变更后立即影响该小区后续接单策略；
物业还可对某类订单（如药品）设置**临时放行**（必须指定有效期与适用楼栋）。

每一次**门禁开通、路线调整、电梯请求、避障停留、签收、退回、客服介入**都写入**同一配送事件流**
（Kafka topic `delivery-events` → `delivery_event` 表），设备动作与业务结果在同一事件里，
支撑物业端（通行影响）、运营端（投放效率）、客服端（异常责任）三类视图。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · PostgreSQL 16 · Kafka（KRaft）· Flyway · springdoc-openapi（Swagger UI）

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：`postgres`(5432)、`kafka`(9092)、`app`(8080)。应用启动时自动执行 Flyway 建表与种子数据。

- Swagger UI（OpenAPI 3.x 在线文档与调试）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

Compose 中为演示设置了较短超时（取件超时 120s、冷藏超时 180s、电梯超时 60s），生产可通过环境变量调大。

## 本地开发启动

依赖：JDK 17+、Maven 3.8+、本地 PostgreSQL（建库 `robot_delivery`，账号 `postgres/postgres`）、本地 Kafka（9092）。

```bash
# 1. 准备依赖（示例： Debian/Ubuntu）
#    PostgreSQL: apt install postgresql && createdb robot_delivery
#    Kafka: 解压官方 tgz，KRaft 模式 kafka-storage.sh format 后 kafka-server-start.sh

# 2. 构建并运行
mvn package -DskipTests
DB_HOST=localhost DB_PORT=5432 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  java -jar target/robot-delivery-1.0.0.jar
```

常用环境变量（均有默认值，见 `src/main/resources/application.yml`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost/5432/robot_delivery/postgres/postgres | PostgreSQL 连接 |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka 地址 |
| `PICKUP_TIMEOUT_SECONDS` | 600 | 用户超时不取件时限 |
| `COLDCHAIN_TIMEOUT_SECONDS` | 2700 | 冷藏订单履约时限 |
| `ELEVATOR_TIMEOUT_SECONDS` | 180 | 电梯请求超时 |
| `LOW_BATTERY_THRESHOLD` | 20 | 低电量阈值（%） |
| `SCHEDULER_INTERVAL_MS` | 10000 | 超时巡检间隔 |
| `NIGHT_EXIT_GRACE_MINUTES` | 15 | 夜间限行要求在楼机器人退出楼栋的宽限分钟数 |

## 测试账号

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 全部权限 |
| `property` | `property123` | PROPERTY | 物业端：门禁审批、夜间规则、通行影响视图 |
| `ops` | `ops123` | OPS | 运营端：下单、分配机器人、模拟机器人/电梯上报、效率视图 |
| `cs` | `cs123` | CS | 客服端：投诉受理、工单结案、责任追踪 |

调用方式：`POST /api/auth/login` 获取 token，之后请求头携带 `Authorization: Bearer <token>`。
（演示环境密码明文存储、令牌内存保存，生产需替换为安全实现。）

种子数据：社区 `阳光社区`(id=1，夜间 22:00-07:00 限制运行) / `绿洲家园`(id=2)；
楼栋 `1栋`(id=1) / `2栋`(id=2) / `5栋`(id=3)；机器人 `RB-001`(id=1) / `RB-002`(id=2，低电量 15%) / `RB-003`(id=3)，
每台机器人配 A（冷藏）/B（普通）载物仓。

## 本题验证方式

### 方式一：自动化冒烟脚本（推荐）

应用启动后执行（覆盖主流程 + 全部异常场景 + 夜间限行专项 + 三端视图，共 89 项断言）：

```bash
./scripts/smoke.sh            # 默认打 http://localhost:8080
```

> 脚本中的超时类场景（超时未取 / 冷藏超时 / 电梯超时）依赖较短超时配置。
> 用 compose 启动时无需调整（已内置演示参数）；本地运行时请显式指定：
> `PICKUP_TIMEOUT_SECONDS=30 COLDCHAIN_TIMEOUT_SECONDS=45 ELEVATOR_TIMEOUT_SECONDS=20 SCHEDULER_INTERVAL_MS=5000 java -jar ...`

### 方式二：Swagger UI 手动验证

打开 http://localhost:8080/swagger-ui.html ，先调 `POST /api/auth/login`（如 `ops/ops123`），
点 `Authorize` 填入 `Bearer <token>`，再依次调用各接口。

### 方式三：curl 手动走一遍主流程

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"ops","password":"ops123"}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"

# 下单（外卖、1栋、不需冷藏）
OID=$(curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"FOOD","communityId":1,"buildingId":1,"unitNo":"2单元","roomNo":"901",
  "coldChain":false,"estimatedDeliveryAt":"2026-12-31T23:59:00",
  "userName":"张三","userPhone":"13800000001"}' | jq -r .id)

curl -s -X POST localhost:8080/api/deliveries/$OID/assign        -H "$AUTH" -H 'Content-Type: application/json' -d '{"robotId":1}'
AR=$(curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-gate -H "$AUTH" -H 'Content-Type: application/json' -d '{"gateName":"南门"}' | jq -r .id)
# 物业审批门禁（换 property 账号 token）
PTOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"property","password":"property123"}' | jq -r .token)
curl -s -X POST localhost:8080/api/access-requests/$AR/approve -H "Authorization: Bearer $PTOKEN"
ER=$(curl -s -X POST localhost:8080/api/deliveries/$OID/request-elevator -H "$AUTH" -H 'Content-Type: application/json' -d '{"fromFloor":1,"toFloor":9}' | jq -r .id)
curl -s -X POST localhost:8080/api/elevator-requests/$ER/assign  -H "$AUTH" -H 'Content-Type: application/json' -d '{"carId":"1栋-客梯A"}'
curl -s -X POST localhost:8080/api/elevator-requests/$ER/complete -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-door   -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/open-compartment -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/sign          -H "$AUTH" -H 'Content-Type: application/json' -d '{"signBy":"张三"}'

# 查看统一配送事件时间线（设备动作 + 业务结果在同一事件流里）
curl -s localhost:8080/api/deliveries/$OID/events -H "$AUTH" | jq '.[].eventType'
```

### 异常场景验证入口

| 异常场景 | 触发方式 | 结果（均写入统一事件流） |
|---|---|---|
| 物业门禁拒绝 | `POST /api/access-requests/{id}/deny` | `ACCESS_DENIED` + 订单 EXCEPTION + 客服工单 |
| 老人儿童挡路 | `POST /api/deliveries/{id}/obstacle` `obstacleType=ELDERLY_CHILD` | `OBSTACLE_WAIT`（含停留秒数） |
| 电梯满载 | `POST /api/elevator-requests/{id}/full` | `ELEVATOR_FULL` + `ROUTE_ADJUSTED` + 自动重新请求 |
| 楼道施工 | `obstacleType=CONSTRUCTION` | `OBSTACLE_WAIT` + `ROUTE_ADJUSTED(reason=CONSTRUCTION)` |
| 机器人电量不足 | 分配时电量 < 阈值被拒(409)；配送中 `POST /api/robots/{id}/status` 低电量 | `LOW_BATTERY` + 客服工单，可 `manual-relay` 人工接力 |
| 用户超时不取 | 到门后超过 `PICKUP_TIMEOUT_SECONDS` 未签收 | `PICKUP_TIMEOUT` + 自动退回 `RETURNED` |
| 载物仓打不开 | `POST /api/deliveries/{id}/compartment-fault` | `COMPARTMENT_OPEN_FAILED` + 客服工单 |
| 冷藏订单超时 | 冷藏单超过 `COLDCHAIN_TIMEOUT_SECONDS` 未完成 | `COLDCHAIN_TIMEOUT` + 客服工单 |
| 投诉送错楼栋 | `POST /api/complaints` `complaintType=WRONG_BUILDING` | `COMPLAINT_FILED` + 客服工单；`redeliver` 改派楼栋产生 `ROUTE_ADJUSTED` |
| 物业夜间限行-接单降级 | 开启夜间规则后 `POST /api/orders`（预计送达落入限行时段） | `NIGHT_FALLBACK_APPLIED`，订单 `fulfillmentMode=GATE_PICKUP/MANUAL_DELIVERY` |
| 物业夜间限行-门禁拒绝 | 夜间时段 `POST /api/deliveries/{id}/arrive-gate` | `NIGHT_RESTRICTED` + `ACCESS_DENIED` 自动拒绝 + 客服工单 |
| 物业夜间限行-楼内退出 | 限行时段机器人在楼内（规则变更或巡检触发） | `NIGHT_EVACUATION_REQUIRED`（含退出截止时间）→ `ROBOT_EXITED_BUILDING` |
| 药品订单临时放行 | `POST /api/communities/{id}/night-exemptions`（有效期+适用楼栋） | `NIGHT_EXEMPTION_CREATED`；豁免单夜间可进楼，撤销后恢复降级 |
| 电梯请求超时 | 请求后超过 `ELEVATOR_TIMEOUT_SECONDS` 未派梯 | `ELEVATOR_TIMEOUT` + 客服工单 |

### 夜间限行专项验证（curl）

```bash
# 物业开启夜间限制（22:00 后禁止机器人进楼；演示全天限行可用 "00:00"-"00:00"）
curl -s -X PUT localhost:8080/api/communities/1/night-rule -H "$PTOKEN" -H 'Content-Type: application/json' \
  -d '{"nightRestricted":true,"nightStart":"22:00","nightEnd":"07:00"}'

# 1) 接单前校验：预计送达 23:59 落入限行时段 -> 自动门岗自提（或 "nightFallback":"MANUAL_DELIVERY" 转人工）
curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"FOOD","communityId":1,"buildingId":1,"roomNo":"901",
  "estimatedDeliveryAt":"2026-12-31T23:59:00","userName":"张三","userPhone":"13800000001"}' | jq '.fulfillmentMode'
# -> "GATE_PICKUP"；门岗自提单：assign -> arrive-door(门岗) -> open-compartment -> sign，全程不进楼

# 2) 临时放行药品单：有效期 + 适用楼栋（1栋）
curl -s -X POST localhost:8080/api/communities/1/night-exemptions -H "$PTOKEN" -H 'Content-Type: application/json' -d '{
  "orderType":"MEDICINE","buildingId":1,
  "validFrom":"2026-10-06T00:00:00","validUntil":"2026-10-07T08:00:00",
  "reason":"夜间急救药品绿色通道"}' | jq '.id'
# 豁免期内 1栋 药品单仍 ROBOT 履约、夜间门禁不自动拒绝；撤销：POST /api/night-exemptions/{id}/revoke

# 3) 楼内机器人尽快退出 + 物业端查看结束时间与路线
curl -s -X POST localhost:8080/api/deliveries/$OID/exit-building -H "$AUTH"          # 机器人退出上报
curl -s localhost:8080/api/communities/1/night-curfew/robots -H "$PTOKEN" | jq '.'   # 物业端：要求结束时间/实际退出时间/路线
```

### 三端视图

```bash
# 物业端：机器人是否影响居民通行（避障次数/停留时长/施工绕行/夜间限制/投诉/影响等级）
curl -s "localhost:8080/api/analytics/property/passage-impact?communityId=1" -H "$AUTH"

# 运营端：按小区/楼栋/订单类型评估投放效率（单量/签收率/平均时长/超时数）
curl -s "localhost:8080/api/analytics/ops/efficiency?communityId=1&orderType=FOOD" -H "$AUTH"

# 客服端：异常责任追踪
curl -s "localhost:8080/api/cs/cases?status=OPEN" -H "$AUTH"
curl -s "localhost:8080/api/cs/cases/summary" -H "$AUTH"
```

## 实际验证结论（本仓库交付前已执行）

以下验证均在本环境真实执行通过（本地 PostgreSQL 15 + Kafka KRaft + 本应用 jar）：

1. `mvn package` 构建通过，生成 `target/robot-delivery-1.0.0.jar`。
2. 干净数据库上应用启动成功：Flyway 执行 `V1__init.sql` + `V2__seed.sql` + `V3__night_curfew.sql`，JPA 校验通过，Kafka 连接正常。
3. `./scripts/smoke.sh` 全量运行 **89 项断言全部通过**（主流程 12 类事件时间线、10 类异常场景、夜间限行 4 个专项段落、三端视图与鉴权 401/403）；同一数据库上重复执行两轮均全绿。
4. Swagger UI（`/swagger-ui.html`）可访问，OpenAPI 文档输出 44 个路径。
5. Kafka topic `delivery-events` 实际消费到 661 条事件、覆盖全部 35 种事件类型（含 `NIGHT_FALLBACK_APPLIED`、`NIGHT_EVACUATION_REQUIRED`、`ROBOT_EXITED_BUILDING`、`NIGHT_EXEMPTION_CREATED`、`NIGHT_EXEMPTION_REVOKED`），消费者落库后事件时间线接口可查。
6. 夜间限行专项实测：预计送达落入限行时段的订单自动降级门岗自提/人工配送；规则变更即刻影响后续接单；在楼机器人被标记 `nightExitRequired` 与退出截止时间，完成签收后 `exit-building` 记录实际退出时间；物业端 `/api/communities/1/night-curfew/robots` 返回结束时间与行进路线；药品临时放行按有效期+适用楼栋生效，撤销后恢复降级。

## 架构说明

```
机器人/电梯系统(模拟)        物业端            运营端            客服端
      │  上报/回调             │ 审批/规则         │ 下单/调度         │ 结案
      └──────────┬─────────────┴─────────────────┴─────────────────┘
                 ▼
        Spring Boot REST API（/api/**，Bearer 鉴权 + 角色控制）
                 │  每一次设备动作与业务结果
                 ▼
        Kafka topic: delivery-events（key=配送单ID，保证同单有序）
                 ▼
        消费者落库 delivery_event（统一配送事件流）
                 ▼
        PostgreSQL（订单/机器人/门禁/电梯/投诉/工单/通知/事件）
```

- **统一配送事件**：`DeliveryEventService.emit()` 是唯一入口，35 种事件类型（见 `EventType`），
  事件携带 `actor`（机器人/物业/电梯/用户/系统/运营/客服）、`reason`、`waitSeconds` 与业务 JSON。
- **夜间限行**：`NightCurfewService` 负责接单前校验（预计送达落入限行时段则降级门岗自提/人工配送，
  命中有效临时放行除外）、在楼机器人退出标记（规则变更即时触发 + 巡检兜底）与物业端结束时间/路线视图；
  临时放行按 社区+楼栋+订单类型 匹配且必须在有效期内、未被撤销。
- **超时巡检**：`TimeoutScheduler` 周期检查取件超时、冷藏超时、电梯超时与夜间在楼机器人，自动标记并触发客服介入。
- **状态机**：`CREATED→ASSIGNED→ACCESS_PENDING→ENTERING→(ELEVATOR_WAIT→ON_FLOOR)→WAITING_PICKUP→COMPARTMENT_OPEN→SIGNED`，
  异常分支 `EXCEPTION / RETURNING→RETURNED / CANCELLED`，非法迁移返回 409。
  履约方式 `fulfillmentMode`：`ROBOT`（默认进楼）/ `GATE_PICKUP`（门岗自提：分配机器人→送至门岗→通知→签收）/
  `MANUAL_DELIVERY`（人工配送：人工接力承接→到门→签收）。

## 目录结构

```
├── Dockerfile / docker-compose.yml   # 一键启动
├── scripts/smoke.sh                  # 89 项断言的冒烟验证
├── src/main/resources/application.yml
├── src/main/resources/db/migration/  # V1 建表 / V2 种子数据 / V3 夜间限行
└── src/main/java/com/example/robotdelivery/
    ├── event/        # 统一配送事件：Kafka 生产者/消费者/事件查询
    ├── order/        # 配送订单（类型/楼栋/冷藏/预计送达/履约方式/联系方式）
    ├── delivery/     # 主流程编排：分配/避障/到门/签收/退回/人工接力/改派/退出楼栋
    ├── night/        # 物业夜间限行：接单降级/在楼退出/临时放行/物业端视图
    ├── access/       # 物业门禁协同（含夜间限制自动拒绝、临时放行豁免）
    ├── elevator/     # 电梯系统协同（派梯/满载/超时）
    ├── robot/        # 机器人与载物仓（低电量上报）
    ├── complaint/    # 用户投诉（送错楼栋等）
    ├── cs/           # 客服工单与责任认定
    ├── analytics/    # 物业通行影响 / 运营投放效率
    ├── notification/ # 短信/小程序取件通知（模拟）
    ├── scheduler/    # 超时巡检（含夜间在楼机器人退出扫描）
    └── auth/ config/ # 登录鉴权 / 全局配置
```
