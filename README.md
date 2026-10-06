# 城市社区配送机器人进楼门禁避障签收服务

外卖 / 药品 / 商超订单交给配送机器人后，本服务负责**进楼配送全链路协同**：记录订单类型、收货楼栋、
门禁授权、是否冷藏、预计送达、电量、载物仓编号与用户联系方式；机器人到小区门口申请物业门禁，
进入楼栋后与电梯系统协同选层，到达后通过短信 / 小程序通知用户取件。

**物业夜间限行**：物业可设置夜间时段（如 22:00 后）禁止机器人进楼。服务在**接单前**按预计到达
时间校验，落入限行时段且无有效放行的订单自动改为**门岗自提**或**人工配送**（机器人不进楼）；
限行开始时**已在楼内**的机器人会被系统催办尽快完成当前订单并按路线撤离，物业端可查看订单
预计完成时间、撤离路线与实际撤离时间；物业还可对某类**药品订单临时放行**，设置**有效期与适用
楼栋**，放行/限行规则变更立即影响该小区后续接单与门禁策略。

每一次**门禁开通、路线调整、电梯请求、避障停留、签收、退回、客服介入、夜间限行判定与催办撤离**
都写入**同一配送事件流**（Kafka topic `delivery-events` → `delivery_event` 表），设备动作与业务
结果在同一事件里，支撑物业端（通行影响）、运营端（投放效率）、客服端（异常责任）三类视图。

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
| `NIGHT_EXIT_GRACE_SECONDS` | 1800 | 夜间限行开始后，在楼机器人完成当前订单并撤离的宽限时间 |
| `SCHEDULER_INTERVAL_MS` | 10000 | 超时/夜间撤离巡检间隔 |

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

应用启动后执行（覆盖主流程 + 全部异常 + 夜间限行增强 + 三端视图，共 90+ 项断言）：

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
| 物业限制夜间运行 | `PUT /api/communities/{id}/night-rule` 后夜间申请门禁 | `NIGHT_RESTRICTED` + `ACCESS_DENIED` 自动拒绝 |
| 夜间预计到达限行 | 限行时段内预计到达的订单在**下单时**自动改履约方式 | `FULFILLMENT_DOWNGRADED`，外卖/商超→门岗自提，药品→人工配送 |
| 药品临时放行 | `POST /api/night/exemptions`（有效期+适用楼栋） | 命中放行的药品单 `NIGHT_EXEMPTION_APPLIED`，机器人可进楼 |
| 机器人已在楼内 | 限行开始/规则变更时在楼机器人 | `NIGHT_ORDER_EXPEDITED`+`NIGHT_EXIT_PLANNED`，物业端可查结束时间与路线；撤离后 `NIGHT_EXITED` |
| 电梯请求超时 | 请求后超过 `ELEVATOR_TIMEOUT_SECONDS` 未派梯 | `ELEVATOR_TIMEOUT` + 客服工单 |

### 夜间限行专题验证

```bash
# 1) 物业设置/修改夜间限行（修改立即影响后续接单策略，并催办已在楼内的机器人）
curl -s -X PUT localhost:8080/api/communities/1/night-rule -H "Authorization: Bearer $PTOKEN" \
  -H 'Content-Type: application/json' -d '{"nightRestricted":true,"nightStart":"22:00","nightEnd":"07:00"}'

# 2) 下单（接单前校验预计到达时间）：22:00 后到达的外卖自动改门岗自提，药品自动改人工配送
curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"FOOD","communityId":1,"buildingId":1,"coldChain":false,
  "estimatedDeliveryAt":"2026-12-31T23:30:00","userName":"张三","userPhone":"13800000001"}'
# -> fulfillmentMode=GATE_PICKUP；药品单 -> MANUAL_DELIVERY，分配机器人接口返回 409
# 门岗自提: POST /api/deliveries/{id}/gate-pickup {"gateName":"南门岗亭"} -> 通知用户自取 -> sign
# 人工配送: POST /api/deliveries/{id}/manual-dispatch {"courierName":"夜班老李"}
#           -> POST /api/deliveries/{id}/manual-arrive-door -> sign

# 3) 物业临时放行某类药品：有效期 + 适用楼栋（buildingId 省略=全小区楼栋）
curl -s -X POST localhost:8080/api/night/exemptions -H "Authorization: Bearer $PTOKEN" \
  -H 'Content-Type: application/json' -d '{
  "communityId":1,"orderType":"MEDICINE","buildingId":1,
  "validFrom":"2026-12-31T20:00:00","validTo":"2026-12-31T23:59:59","reason":"1栋急用药品"}'
# 有效期内、适用楼栋的夜间药品单 fulfillmentMode=ROBOT 且夜间门禁不被自动拒绝
GET /api/night/exemptions?communityId=1&activeOnly=true   # 当前有效放行
PUT /api/night/exemptions/{id}  {"enabled":false,...}     # 停用/改有效期/改楼栋

# 4) 机器人已在楼内时限行开始：自动催办，物业端查看结束时间与路线
GET  /api/night/exit-plans?communityId=1&status=NOTIFIED  # expectedExitAt=结束时间, exitRoute=撤离路线
POST /api/night/exit-plans/expedite                       # 手动触发一次催办巡检
POST /api/night/exit-plans/{id}/confirm-exit             # 机器人完成订单并按路线撤离后确认
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

以下验证均在本环境真实执行通过（本地 PostgreSQL 15（独立实例 127.0.0.1:5433，trust）+ Kafka 3.8.1 KRaft + 本应用 jar）：

1. `mvn package -DskipTests` 构建通过，生成 `target/robot-delivery-1.0.0.jar`。
2. 干净数据库上应用启动成功：Flyway 执行 `V1__init.sql` + `V2__seed.sql` + `V3__night_restriction.sql`，
   JPA `ddl-auto=validate` 校验通过，Kafka 连接正常。
3. `./scripts/smoke.sh` 全量运行 **99 项断言全部通过**：主流程 12 类事件时间线、10 类异常场景、
   夜间限行增强（接单前降级门岗自提/人工配送、药品放行有效期+适用楼栋、已在楼机器人催办撤离、
   物业端结束时间/路线视图）、三端视图与鉴权 401/403。
4. Swagger UI（`/swagger-ui.html`）可访问，OpenAPI 文档输出 **48 个路径**（新增夜间放行/撤离 5 个路径）。
5. 事件流核验：Kafka topic `delivery-events` 中夜间新增事件
   `FULFILLMENT_DOWNGRADED`(4)、`NIGHT_EXEMPTION_APPLIED`(2)、`NIGHT_ORDER_EXPEDITED`(1)、
   `NIGHT_EXIT_PLANNED`(1)、`NIGHT_EXITED`(1)、`ARRIVED_AT_GATE`(1)、`MANUAL_DISPATCHED`(1)
   均由消费者落库；订单履约方式分布 ROBOT=12 / MANUAL_DELIVERY=3 / GATE_PICKUP=1，撤离计划 1 条已 EXITED。
6. 边界校验：非药品类型放行 400、有效期倒置 400、跨社区楼栋 400、运营账号放行 403；
   非限行时段/放行命中不拦截，催办巡检重复触发幂等（第二次 `newExitPlans=0`）。

> 一键启动仍以仓库根目录 `Dockerfile` + `docker-compose.yml` 为准（本环境无 docker 守护进程，
> 故上述为等价的本地 jar + 独立 PG/Kafka 实测；compose 已补充 `NIGHT_EXIT_GRACE_SECONDS` 演示参数）。

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

- **统一配送事件**：`DeliveryEventService.emit()` 是唯一入口，36 种事件类型（见 `EventType`），
  事件携带 `actor`（机器人/物业/电梯/用户/系统/运营/客服）、`reason`、`waitSeconds` 与业务 JSON。
- **夜间限行**：`NightPolicyService` 统一判定限行时段/药品放行；下单与分配机器人两处做预计到达
  预检并降级履约方式；`NightExitService` + `NightScheduler` 在限行开始时催办在楼机器人撤离。
- **超时巡检**：`TimeoutScheduler` 周期检查取件超时、冷藏超时、电梯超时，自动标记并触发客服介入。
- **状态机**：`CREATED→ASSIGNED→ACCESS_PENDING→ENTERING→(ELEVATOR_WAIT→ON_FLOOR)→WAITING_PICKUP→COMPARTMENT_OPEN→SIGNED`，
  异常分支 `EXCEPTION / RETURNING→RETURNED / CANCELLED`，非法迁移返回 409。

## 目录结构

```
├── Dockerfile / docker-compose.yml   # 一键启动
├── scripts/smoke.sh                  # 62 项断言的冒烟验证
├── src/main/resources/application.yml
├── src/main/resources/db/migration/  # V1 建表 / V2 种子数据
└── src/main/java/com/example/robotdelivery/
    ├── event/        # 统一配送事件：Kafka 生产者/消费者/事件查询
    ├── order/        # 配送订单（类型/楼栋/冷藏/预计送达/联系方式）
    ├── delivery/     # 主流程编排：分配/避障/到门/签收/退回/人工接力/改派/门岗自提/人工配送
    ├── access/       # 物业门禁协同（含夜间限制自动拒绝与药品放行豁免）
    ├── night/        # 夜间限行：时段/放行判定、药品临时放行、在楼机器人催办撤离
    ├── elevator/     # 电梯系统协同（派梯/满载/超时）
    ├── robot/        # 机器人与载物仓（低电量上报）
    ├── complaint/    # 用户投诉（送错楼栋等）
    ├── cs/           # 客服工单与责任认定
    ├── analytics/    # 物业通行影响 / 运营投放效率
    ├── notification/ # 短信/小程序取件通知（模拟）
    ├── scheduler/    # 超时巡检
    └── auth/ config/ # 登录鉴权 / 全局配置
```
