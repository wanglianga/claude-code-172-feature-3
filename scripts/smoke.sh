#!/usr/bin/env bash
# 冒烟验证：主流程 + 全部异常场景 + 三端视图
# 用法: BASE=http://localhost:8080 ./scripts/smoke.sh
# 依赖: curl, jq
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
FAILS=0

say()  { echo ""; echo "== $* =="; }
ok()   { echo "  [PASS] $1"; }
bad()  { echo "  [FAIL] $1"; FAILS=$((FAILS+1)); }
check(){ # desc actual expected
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: 期望[$3] 实际[$2]"; fi
}

req() { # method path token [data]
  local method=$1 path=$2 token=${3:-} data=${4:-}
  local args=(-sS -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  curl "${args[@]}"
}

wait_event() { # deliveryId eventType tries
  local did=$1 type=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    if req GET "/api/deliveries/$did/events" "$OPS" | jq -e ".[] | select(.eventType==\"$type\")" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_status() { # deliveryId status tries
  local did=$1 st=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    local cur
    cur=$(req GET "/api/orders/$did" "$OPS" | jq -r '.status // empty')
    [ "$cur" = "$st" ] && return 0
    sleep 1
  done
  return 1
}

new_order() { # buildingId coldChain type -> order id
  req POST /api/orders "$OPS" "{
    \"orderType\":\"${3:-FOOD}\",\"communityId\":1,\"buildingId\":$1,
    \"unitNo\":\"2单元\",\"roomNo\":\"901\",\"coldChain\":$2,
    \"estimatedDeliveryAt\":\"2026-12-31T23:59:00\",
    \"userName\":\"张三\",\"userPhone\":\"13800000001\"}" | jq -r '.id'
}

say "0. 认证与权限"
UNAUTH=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/orders")
check "未登录访问受保护接口返回401" "$UNAUTH" "401"
ADMIN=$(req POST /api/auth/login "" '{"username":"admin","password":"admin123"}'   | jq -r .token)
OPS=$(req POST /api/auth/login "" '{"username":"ops","password":"ops123"}'         | jq -r .token)
PROPERTY=$(req POST /api/auth/login "" '{"username":"property","password":"property123"}' | jq -r .token)
CS=$(req POST /api/auth/login "" '{"username":"cs","password":"cs123"}'            | jq -r .token)
[ -n "$OPS" ] && [ "$OPS" != "null" ] && ok "四个测试账号登录成功" || { bad "登录失败"; exit 1; }
FORBID=$(req POST /api/orders "$PROPERTY" '{"orderType":"FOOD","communityId":1,"buildingId":1,"userName":"x","userPhone":"1"}' | jq -r .status)
check "物业账号创建订单被拒绝(403)" "$FORBID" "403"

say "准备：关闭社区1夜间限制（避免测试时段落入夜间窗口）"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":false,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null

say "1. 主流程：创建->分配->门禁->电梯->避障->到门通知->开仓->签收"
O1=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O1/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE=$(req POST "/api/deliveries/$O1/arrive-gate" "$OPS" '{"gateName":"南门"}')
AR_ID=$(echo "$GATE" | jq -r .id)
check "门禁申请生成" "$(echo "$GATE" | jq -r .status)" "PENDING"
req POST "/api/access-requests/$AR_ID/approve" "$PROPERTY" >/dev/null
EV_REQ=$(req POST "/api/deliveries/$O1/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":9}')
ER_ID=$(echo "$EV_REQ" | jq -r .id)
req POST "/api/elevator-requests/$ER_ID/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$ER_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O1/obstacle" "$OPS" '{"obstacleType":"ELDERLY_CHILD","waitSeconds":30,"location":"1栋大堂"}' >/dev/null
req POST "/api/deliveries/$O1/arrive-door" "$OPS" >/dev/null
NOTIFY=$(req GET "/api/notifications?deliveryId=$O1" "$OPS" | jq 'length')
check "短信+小程序双通道通知" "$NOTIFY" "2"
req POST "/api/deliveries/$O1/open-compartment" "$OPS" >/dev/null
SIGN=$(req POST "/api/deliveries/$O1/sign" "$OPS" '{"signBy":"张三"}')
check "签收后状态" "$(echo "$SIGN" | jq -r .status)" "SIGNED"
ROBOT1=$(req GET "/api/robots?communityId=1" "$OPS" | jq -r '.[] | select(.id==1) | .status')
check "签收后机器人释放为空闲" "$ROBOT1" "IDLE"
if wait_event "$O1" SIGNED; then ok "统一事件流含 SIGNED"; else bad "统一事件流缺少 SIGNED"; fi
EV_COUNT=$(req GET "/api/deliveries/$O1/events" "$OPS" | jq 'length')
[ "$EV_COUNT" -ge 12 ] && ok "订单1事件时间线完整($EV_COUNT条)" || bad "订单1事件过少: $EV_COUNT"
for T in ORDER_CREATED ROBOT_ASSIGNED ACCESS_REQUESTED ACCESS_GRANTED ELEVATOR_REQUESTED ELEVATOR_ASSIGNED ELEVATOR_COMPLETED OBSTACLE_WAIT ARRIVED_AT_DOOR USER_NOTIFIED COMPARTMENT_OPENED SIGNED; do
  if wait_event "$O1" "$T" 3; then ok "事件 $T 已写入统一事件流"; else bad "缺少事件 $T"; fi
done

say "2. 异常：物业门禁拒绝 -> 客服介入 -> 取消"
O2=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O2/assign" "$OPS" '{"robotId":1}' >/dev/null
AR2=$(req POST "/api/deliveries/$O2/arrive-gate" "$OPS" '{"gateName":"北门"}' | jq -r .id)
req POST "/api/access-requests/$AR2/deny" "$PROPERTY" '{"reason":"外来设备未备案"}' >/dev/null
check "门禁拒绝后订单状态" "$(req GET "/api/orders/$O2" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O2" ACCESS_DENIED && ok "ACCESS_DENIED 事件" || bad "缺少 ACCESS_DENIED"
wait_event "$O2" CS_INTERVENTION && ok "客服介入事件 CS_INTERVENTION" || bad "缺少 CS_INTERVENTION"
req POST "/api/deliveries/$O2/cancel" "$OPS" '{"reason":"门禁被拒，取消配送"}' >/dev/null

say "3. 异常：电梯满载 -> 自动等待下一趟(路线调整)"
O3=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O3/assign" "$OPS" '{"robotId":1}' >/dev/null
AR3=$(req POST "/api/deliveries/$O3/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR3/approve" "$PROPERTY" >/dev/null
ER3=$(req POST "/api/deliveries/$O3/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":6}' | jq -r .id)
RETRY=$(req POST "/api/elevator-requests/$ER3/full" "$OPS")
RETRY_ID=$(echo "$RETRY" | jq -r .id)
[ "$RETRY_ID" != "$ER3" ] && [ "$RETRY_ID" != "null" ] && ok "满载后自动重新请求电梯(新请求#$RETRY_ID)" || bad "满载后未自动重新请求"
wait_event "$O3" ELEVATOR_FULL && ok "ELEVATOR_FULL 事件" || bad "缺少 ELEVATOR_FULL"
wait_event "$O3" ROUTE_ADJUSTED && ok "ROUTE_ADJUSTED(电梯满载) 事件" || bad "缺少 ROUTE_ADJUSTED"
req POST "/api/elevator-requests/$RETRY_ID/assign" "$OPS" '{"carId":"1栋-货梯B"}' >/dev/null
req POST "/api/elevator-requests/$RETRY_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/sign" "$OPS" '{"signBy":"李女士"}' >/dev/null
check "满载重试后签收" "$(req GET "/api/orders/$O3" "$OPS" | jq -r .status)" "SIGNED"

say "4. 异常：楼道施工 -> 避障停留+路线调整 -> 投诉送错楼栋 -> 客服结案"
O4=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O4/assign" "$OPS" '{"robotId":1}' >/dev/null
AR4=$(req POST "/api/deliveries/$O4/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR4/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O4/obstacle" "$OPS" '{"obstacleType":"CONSTRUCTION","waitSeconds":120,"location":"1栋3层楼道"}' >/dev/null
wait_event "$O4" OBSTACLE_WAIT && ok "OBSTACLE_WAIT(施工) 事件" || bad "缺少 OBSTACLE_WAIT"
req GET "/api/deliveries/$O4/events" "$OPS" | jq -e '.[] | select(.eventType=="ROUTE_ADJUSTED" and .reason=="CONSTRUCTION")' >/dev/null \
  && ok "ROUTE_ADJUSTED(reason=CONSTRUCTION) 事件" || bad "缺少施工路线调整事件"
req POST "/api/deliveries/$O4/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/sign" "$OPS" '{"signBy":"王先生"}' >/dev/null
CMP=$(req POST /api/complaints "$CS" "{\"deliveryId\":$O4,\"complaintType\":\"WRONG_BUILDING\",\"content\":\"用户称应送2栋而非1栋\"}")
CMP_CASE=$(req GET "/api/cs/cases?status=OPEN" "$CS" | jq -r ".[] | select(.deliveryId==$O4) | .id")
[ -n "$CMP_CASE" ] && [ "$CMP_CASE" != "null" ] && ok "投诉自动生成客服工单#$CMP_CASE" || bad "投诉未生成工单"
req POST "/api/cs/cases/$CMP_CASE/resolve" "$CS" '{"responsibility":"PLATFORM","note":"分拣贴错楼栋标签，已致歉"}' >/dev/null
check "结案后投诉状态" "$(req GET /api/complaints "$CS" | jq -r ".[] | select(.delivery.id==$O4) | .status")" "RESOLVED"
wait_event "$O4" CS_CASE_CLOSED && ok "CS_CASE_CLOSED 事件" || bad "缺少 CS_CASE_CLOSED"

say "5. 异常：低电量 -> 分配被拒 / 配送途中低电量 -> 人工接力"
O5=$(new_order 1 false MEDICINE)
LOW=$(req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}')
check "低电量机器人(RB-002,15%)分配被拒(409)" "$(echo "$LOW" | jq -r .status)" "409"
req POST /api/robots/2/status "$OPS" '{"battery":90}' >/dev/null
req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}' >/dev/null
check "充电后分配成功" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "ASSIGNED"
AR5=$(req POST "/api/deliveries/$O5/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR5/approve" "$PROPERTY" >/dev/null
req POST /api/robots/2/status "$OPS" '{"battery":8}' >/dev/null
wait_event "$O5" LOW_BATTERY && ok "配送途中 LOW_BATTERY 事件" || bad "缺少 LOW_BATTERY"
req POST "/api/deliveries/$O5/manual-relay" "$OPS" '{"courierName":"骑手小王"}' >/dev/null
req POST "/api/deliveries/$O5/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/sign" "$OPS" '{"signBy":"赵女士"}' >/dev/null
check "人工接力后签收" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O5" MANUAL_RELAY && ok "MANUAL_RELAY 事件" || bad "缺少 MANUAL_RELAY"

say "6. 异常：载物仓打不开 -> 客服介入 -> 改派楼栋(送错纠正) -> 签收"
O6=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O6/assign" "$OPS" '{"robotId":1}' >/dev/null
AR6=$(req POST "/api/deliveries/$O6/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR6/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/compartment-fault" "$OPS" >/dev/null
check "仓门故障后状态" "$(req GET "/api/orders/$O6" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O6" COMPARTMENT_OPEN_FAILED && ok "COMPARTMENT_OPEN_FAILED 事件" || bad "缺少 COMPARTMENT_OPEN_FAILED"
req POST "/api/deliveries/$O6/redeliver" "$CS" '{"buildingId":2}' >/dev/null
wait_event "$O6" ROUTE_ADJUSTED && ok "改派楼栋 ROUTE_ADJUSTED(WRONG_BUILDING) 事件" || bad "缺少改派事件"
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/sign" "$OPS" '{"signBy":"周先生"}' >/dev/null
check "改派2栋后签收" "$(req GET "/api/orders/$O6" "$OPS" | jq -r '".building=" + (.building.name|tostring) + " " + .status')" ".building=2栋 SIGNED"

say "7. 异常：用户超时不取 -> 自动退回（等待巡检，PICKUP_TIMEOUT_SECONDS 需较短）"
O7=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O7/assign" "$OPS" '{"robotId":1}' >/dev/null
AR7=$(req POST "/api/deliveries/$O7/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR7/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O7/arrive-door" "$OPS" >/dev/null
if wait_status "$O7" RETURNING 60; then ok "超时未取自动进入退回"; else bad "未进入退回流程"; fi
wait_event "$O7" PICKUP_TIMEOUT && ok "PICKUP_TIMEOUT 事件" || bad "缺少 PICKUP_TIMEOUT"
req POST "/api/deliveries/$O7/confirm-return" "$OPS" >/dev/null
check "退回完成" "$(req GET "/api/orders/$O7" "$OPS" | jq -r .status)" "RETURNED"
wait_event "$O7" RETURNED && ok "RETURNED 事件" || bad "缺少 RETURNED"

say "8. 异常：冷藏订单超时（等待巡检，COLDCHAIN_TIMEOUT_SECONDS 需较短）"
O8=$(new_order 1 true MEDICINE)
if wait_event "$O8" COLDCHAIN_TIMEOUT 90; then ok "COLDCHAIN_TIMEOUT 事件"; else bad "缺少 COLDCHAIN_TIMEOUT"; fi
check "冷藏超时标记" "$(req GET "/api/orders/$O8" "$OPS" | jq -r .coldChainTimedOut)" "true"
req POST "/api/deliveries/$O8/cancel" "$OPS" '{"reason":"冷藏超时，订单取消"}' >/dev/null

say "9. 异常：电梯请求超时（等待巡检，ELEVATOR_TIMEOUT_SECONDS 需较短）"
O9=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O9/assign" "$OPS" '{"robotId":1}' >/dev/null
AR9=$(req POST "/api/deliveries/$O9/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR9/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O9/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":11}' >/dev/null
if wait_event "$O9" ELEVATOR_TIMEOUT 60; then ok "ELEVATOR_TIMEOUT 事件"; else bad "缺少 ELEVATOR_TIMEOUT"; fi
req POST "/api/deliveries/$O9/cancel" "$OPS" '{"reason":"电梯长时间不可用，取消"}' >/dev/null

say "10. 夜间限行：规则变更影响接单策略 + 在途单门禁自动拒绝"
# 规则当前关闭（开头已设置）。先创建 ROBOT 单并分配（规则变更前的在途单）
O10=$(new_order 1 false FOOD)
check "规则关闭时下单为机器人履约" "$(req GET "/api/orders/$O10" "$OPS" | jq -r .fulfillmentMode)" "ROBOT"
req POST "/api/deliveries/$O10/assign" "$OPS" '{"robotId":1}' >/dev/null
# 物业开启全天夜间限制（00:00-00:00 起止相等=全天），后续接单策略立即受影响
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"00:00","nightEnd":"00:00"}' >/dev/null
# 规则变更后新接单：预计送达时间落入限行时段 -> 自动降级门岗自提
O11=$(new_order 1 false FOOD)
check "夜间时段新单自动降级门岗自提" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .fulfillmentMode)" "GATE_PICKUP"
wait_event "$O11" NIGHT_FALLBACK_APPLIED && ok "NIGHT_FALLBACK_APPLIED 事件" || bad "缺少 NIGHT_FALLBACK_APPLIED"
# 变更前接的机器人单到门口 -> 夜间自动拒绝
GATE10=$(req POST "/api/deliveries/$O10/arrive-gate" "$OPS" '{"gateName":"南门"}')
check "夜间门禁申请自动拒绝" "$(echo "$GATE10" | jq -r .status)" "DENIED"
wait_event "$O10" NIGHT_RESTRICTED && ok "NIGHT_RESTRICTED 事件" || bad "缺少 NIGHT_RESTRICTED"
wait_event "$O10" ACCESS_DENIED && ok "夜间 ACCESS_DENIED 事件" || bad "缺少夜间 ACCESS_DENIED"
req POST "/api/deliveries/$O10/cancel" "$OPS" '{"reason":"夜间限制，改期配送"}' >/dev/null

say "11. 夜间限行：门岗自提与人工配送履约"
# 门岗自提单：机器人送至门岗 -> 通知 -> 开仓 -> 签收（不进楼、无门禁）
req POST "/api/deliveries/$O11/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE_ACCESS=$(req POST "/api/deliveries/$O11/arrive-gate" "$OPS" '{"gateName":"南门"}')
check "门岗自提单不能申请进楼门禁(409)" "$(echo "$GATE_ACCESS" | jq -r .status)" "409"
req POST "/api/deliveries/$O11/arrive-door" "$OPS" >/dev/null
check "门岗自提到岗通知后状态" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .status)" "WAITING_PICKUP"
req POST "/api/deliveries/$O11/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O11/sign" "$OPS" '{"signBy":"张三"}' >/dev/null
check "门岗自提签收" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .status)" "SIGNED"
# 人工配送单：nightFallback=MANUAL_DELIVERY
O12=$(req POST /api/orders "$OPS" '{"orderType":"FOOD","communityId":1,"buildingId":1,"unitNo":"1单元","roomNo":"802","coldChain":false,"estimatedDeliveryAt":"2026-12-31T23:59:00","userName":"李四","userPhone":"13800000002","nightFallback":"MANUAL_DELIVERY"}' | jq -r .id)
check "指定人工配送降级" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .fulfillmentMode)" "MANUAL_DELIVERY"
MANUAL_ASSIGN=$(req POST "/api/deliveries/$O12/assign" "$OPS" '{"robotId":1}')
check "人工配送单不能分配机器人(409)" "$(echo "$MANUAL_ASSIGN" | jq -r .status)" "409"
req POST "/api/deliveries/$O12/manual-relay" "$OPS" '{"courierName":"夜班配送员老周"}' >/dev/null
req POST "/api/deliveries/$O12/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O12/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O12/sign" "$OPS" '{"signBy":"李四"}' >/dev/null
check "人工配送签收" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O12" MANUAL_RELAY && ok "MANUAL_RELAY(人工配送) 事件" || bad "缺少 MANUAL_RELAY"

say "12. 夜间限行：药品订单临时放行（有效期+适用楼栋）"
FROM=$(date -d '-1 hour' '+%Y-%m-%dT%H:%M:%S')
UNTIL=$(date -d '+2 hours' '+%Y-%m-%dT%H:%M:%S')
EX=$(req POST /api/communities/1/night-exemptions "$PROPERTY" "{\"orderType\":\"MEDICINE\",\"buildingId\":1,\"validFrom\":\"$FROM\",\"validUntil\":\"$UNTIL\",\"reason\":\"夜间急救药品绿色通道\"}")
EX_ID=$(echo "$EX" | jq -r .id)
check "临时放行创建生效" "$(echo "$EX" | jq -r .status)" "ACTIVE"
check "放行适用楼栋" "$(echo "$EX" | jq -r .building.id)" "1"
# 药品单（1栋）预计夜间送达 -> 豁免生效，仍机器人进楼
O13=$(new_order 1 false MEDICINE)
check "豁免药品单保持机器人履约" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .fulfillmentMode)" "ROBOT"
check "订单记录命中豁免" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .nightExemptionId)" "$EX_ID"
# 非药品单仍降级（对照）
O14=$(new_order 1 false GROCERY)
check "非豁免类型订单仍降级" "$(req GET "/api/orders/$O14" "$OPS" | jq -r .fulfillmentMode)" "GATE_PICKUP"
req POST "/api/deliveries/$O14/cancel" "$OPS" '{"reason":"对照单取消"}' >/dev/null
# 其他楼栋药品单不在放行范围（对照）
O15=$(req POST /api/orders "$OPS" '{"orderType":"MEDICINE","communityId":1,"buildingId":2,"roomNo":"101","estimatedDeliveryAt":"2026-12-31T23:59:00","userName":"王五","userPhone":"13800000003"}' | jq -r .id)
check "非适用楼栋药品单仍降级" "$(req GET "/api/orders/$O15" "$OPS" | jq -r .fulfillmentMode)" "GATE_PICKUP"
req POST "/api/deliveries/$O15/cancel" "$OPS" '{"reason":"对照单取消"}' >/dev/null
# 豁免单夜间申请门禁不自动拒绝
req POST "/api/deliveries/$O13/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE13=$(req POST "/api/deliveries/$O13/arrive-gate" "$OPS" '{"gateName":"南门"}')
check "豁免单夜间门禁不自动拒绝" "$(echo "$GATE13" | jq -r .status)" "PENDING"
AR13=$(echo "$GATE13" | jq -r .id)
req POST "/api/access-requests/$AR13/approve" "$PROPERTY" >/dev/null

say "13. 夜间限行：楼内机器人尽快完成并退出（物业端看结束时间与路线）"
# O13 机器人已在楼内（ENTERING）。规则变更（重新下发）立即触发退出要求
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"00:00","nightEnd":"00:00"}' >/dev/null
check "在楼机器人被标记尽快退出" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .nightExitRequired)" "true"
check "退出截止时间已下发" "$(req GET "/api/orders/$O13" "$OPS" | jq -r '.nightExitDeadline != null')" "true"
wait_event "$O13" NIGHT_EVACUATION_REQUIRED && ok "NIGHT_EVACUATION_REQUIRED 事件" || bad "缺少 NIGHT_EVACUATION_REQUIRED"
# 尽快完成当前订单
req POST "/api/deliveries/$O13/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O13/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O13/sign" "$OPS" '{"signBy":"赵女士"}' >/dev/null
# 退出楼栋
EXIT=$(req POST "/api/deliveries/$O13/exit-building" "$OPS")
check "机器人退出楼栋时间已记录" "$(echo "$EXIT" | jq -r '.exitedBuildingAt != null')" "true"
wait_event "$O13" ROBOT_EXITED_BUILDING && ok "ROBOT_EXITED_BUILDING 事件" || bad "缺少 ROBOT_EXITED_BUILDING"
# 物业端视图：结束时间 + 路线
VIEW=$(req GET /api/communities/1/night-curfew/robots "$PROPERTY")
V13=$(echo "$VIEW" | jq ".[] | select(.orderId==$O13)")
check "物业端可见要求结束时间" "$(echo "$V13" | jq -r '.nightExitDeadline != null')" "true"
check "物业端可见实际退出时间" "$(echo "$V13" | jq -r '.exitedBuildingAt != null')" "true"
check "物业端可见行进路线(>=3个途经点)" "$(echo "$V13" | jq '.route | length >= 3')" "true"
echo "  O13 路线: $(echo "$V13" | jq -c '[.route[].description]')"
# 撤销放行 -> 后续药品单恢复降级
req POST "/api/night-exemptions/$EX_ID/revoke" "$PROPERTY" >/dev/null
check "放行已撤销" "$(req GET /api/communities/1/night-exemptions "$PROPERTY" | jq -r ".[] | select(.id==$EX_ID) | .status")" "REVOKED"
O16=$(new_order 1 false MEDICINE)
check "撤销后药品单恢复夜间降级" "$(req GET "/api/orders/$O16" "$OPS" | jq -r .fulfillmentMode)" "GATE_PICKUP"
req POST "/api/deliveries/$O16/cancel" "$OPS" '{"reason":"放行已撤销，改期"}' >/dev/null
# 恢复社区1夜间规则(22:00-07:00)
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null
ok "已恢复社区1夜间规则(22:00-07:00 限制)"

say "14. 三端视图"
IMPACT=$(req GET "/api/analytics/property/passage-impact?communityId=1" "$PROPERTY")
check "物业端-避障停留次数>=2" "$(echo "$IMPACT" | jq '.obstacleStopCount >= 2')" "true"
check "物业端-施工绕行>=1" "$(echo "$IMPACT" | jq '.constructionDetours >= 1')" "true"
check "物业端-电梯满载>=1" "$(echo "$IMPACT" | jq '.elevatorFullCount >= 1')" "true"
check "物业端-夜间限制>=1" "$(echo "$IMPACT" | jq '.nightRestrictedCount >= 1')" "true"
check "物业端-投诉数>=1" "$(echo "$IMPACT" | jq '.complaintCount >= 1')" "true"
echo "  物业端通行影响: $(echo "$IMPACT" | jq -c '{impactLevel,affectsResidents,suggestion}')"
EFF=$(req GET "/api/analytics/ops/efficiency?communityId=1" "$OPS")
check "运营端-效率报表有数据" "$(echo "$EFF" | jq 'length > 0')" "true"
echo "  运营端效率(1栋FOOD): $(echo "$EFF" | jq -c '.[] | select(.buildingId==1 and .orderType=="FOOD") | {total,signed,returned,successRate}')"
EFF_MED=$(req GET "/api/analytics/ops/efficiency?communityId=1&orderType=MEDICINE" "$OPS")
check "运营端-按订单类型过滤(药品)" "$(echo "$EFF_MED" | jq 'all(.orderType=="MEDICINE")')" "true"
SUM=$(req GET "/api/cs/cases/summary" "$CS")
check "客服端-工单总数>=8" "$(echo "$SUM" | jq '.total >= 8')" "true"
echo "  客服端责任分布: $(echo "$SUM" | jq -c '.byResponsibility')"
EV_ALL=$(req GET "/api/events?communityId=1&eventType=ROUTE_ADJUSTED" "$OPS" | jq 'length')
check "统一事件流可按社区+类型检索" "$([ "$EV_ALL" -ge 2 ] && echo true || echo false)" "true"

say "结果"
if [ "$FAILS" -eq 0 ]; then
  echo "全部冒烟用例通过 ✅"
  exit 0
else
  echo "存在 $FAILS 个失败用例 ❌"
  exit 1
fi
