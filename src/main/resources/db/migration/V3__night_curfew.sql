-- 物业夜间限行：订单履约方式降级 / 楼内机器人退出跟踪 / 药品订单临时放行

alter table delivery_order
    add column fulfillment_mode    varchar(20) not null default 'ROBOT',
    add column night_exit_required boolean     not null default false,
    add column night_exit_deadline timestamp,
    add column exited_building_at  timestamp,
    add column night_exemption_id  bigint;

-- 物业临时放行：某类订单（如药品）在有效期内、指定楼栋可不受夜间限行约束
create table night_exemption (
    id           bigserial primary key,
    community_id bigint      not null references community (id),
    building_id  bigint      not null references building (id),
    order_type   varchar(20) not null,
    valid_from   timestamp   not null,
    valid_until  timestamp   not null,
    status       varchar(20) not null default 'ACTIVE',
    reason       varchar(255),
    created_by   varchar(64),
    created_at   timestamp   not null,
    revoked_at   timestamp
);
create index idx_exemption_lookup on night_exemption (community_id, building_id, order_type, status);
