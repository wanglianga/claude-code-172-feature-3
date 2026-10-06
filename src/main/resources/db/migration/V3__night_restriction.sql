-- 物业夜间限行增强：
-- 1) 接单前按预计到达时间校验，落入限行时段的订单改门岗自提/人工配送（履约方式落到订单）
-- 2) 限行开始时已在楼内的机器人：催办尽快完成当前订单并按路线撤离，物业端可见结束时间与路线
-- 3) 物业可临时放行某类药品订单，带有效期与适用楼栋

alter table delivery_order add column fulfillment_mode    varchar(20) not null default 'ROBOT';
alter table delivery_order add column night_rechecked     boolean     not null default false;
alter table delivery_order add column night_expedited     boolean     not null default false;
alter table delivery_order add column night_expedited_at  timestamp;
alter table delivery_order add column exit_due_at         timestamp;

-- 物业夜间临时放行（如急用药）：有效时间窗 + 适用楼栋（building_id 为空表示全小区楼栋适用）
create table night_medicine_exemption (
    id           bigserial primary key,
    community_id bigint       not null references community (id),
    order_type   varchar(20)  not null default 'MEDICINE',
    building_id  bigint references building (id),
    valid_from   timestamp    not null,
    valid_to     timestamp    not null,
    reason       varchar(255),
    enabled      boolean      not null default true,
    created_by   varchar(64)  not null,
    created_at   timestamp    not null,
    updated_at   timestamp    not null
);
create index idx_exemption_query
    on night_medicine_exemption (community_id, enabled, valid_from, valid_to);

-- 限行开始时已在楼内的机器人撤离计划：每单一条，物业端可查预计完成时间、撤离路线与实际撤离时间
create table night_exit_plan (
    id                 bigserial primary key,
    delivery_id        bigint      not null references delivery_order (id),
    robot_id           bigint      not null,
    community_id       bigint      not null references community (id),
    building_id        bigint      not null,
    status             varchar(20) not null default 'NOTIFIED',
    exit_route         text        not null,
    notified_at        timestamp   not null,
    expected_exit_at   timestamp   not null,
    finished_order_at  timestamp,
    exited_at          timestamp
);
create index idx_exit_plan_community on night_exit_plan (community_id, status);
create unique index uq_exit_plan_delivery on night_exit_plan (delivery_id);
