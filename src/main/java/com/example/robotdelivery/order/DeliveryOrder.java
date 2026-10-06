package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.robot.Robot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "delivery_order")
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(name = "unit_no", length = 20)
    private String unitNo;

    @Column(name = "room_no", length = 20)
    private String roomNo;

    /** 是否需要冷藏 */
    @Column(name = "cold_chain", nullable = false)
    private boolean coldChain;

    /** 预计送达时间 */
    @Column(name = "estimated_delivery_at")
    private LocalDateTime estimatedDeliveryAt;

    @Column(name = "user_name", nullable = false, length = 64)
    private String userName;

    /** 用户联系方式（手机号） */
    @Column(name = "user_phone", nullable = false, length = 32)
    private String userPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeliveryStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "robot_id")
    private Robot robot;

    /** 载物仓编号 */
    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 人工接力配送员 */
    @Column(name = "relay_courier", length = 64)
    private String relayCourier;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_reason", length = 255)
    private String returnReason;

    /** 冷藏订单履约截止时间 */
    @Column(name = "cold_chain_deadline")
    private LocalDateTime coldChainDeadline;

    @Column(name = "cold_chain_timed_out", nullable = false)
    private boolean coldChainTimedOut;

    @Column(name = "pickup_timed_out", nullable = false)
    private boolean pickupTimedOut;

    /** 履约方式：机器人进楼 / 门岗自提 / 人工配送（夜间限行接单前校验决定） */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_mode", nullable = false, length = 20)
    private FulfillmentMode fulfillmentMode;

    /** 接单前是否已按夜间限行规则完成预计到达校验（含命中放行的情况） */
    @Column(name = "night_rechecked", nullable = false)
    private boolean nightRechecked;

    /** 限行开始时机器人已在楼内：系统催办尽快完成当前订单 */
    @Column(name = "night_expedited", nullable = false)
    private boolean nightExpedited;

    @Column(name = "night_expedited_at")
    private LocalDateTime nightExpeditedAt;

    /** 夜间限行要求机器人撤离楼栋的截止时间 */
    @Column(name = "exit_due_at")
    private LocalDateTime exitDueAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
