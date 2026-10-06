package com.example.robotdelivery.night;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.order.OrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 物业临时放行：指定某类订单（如药品）在有效期内、指定楼栋，
 * 不受夜间限行约束（可机器人进楼配送）。
 */
@Getter
@Setter
@Entity
@Table(name = "night_exemption")
public class NightExemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    /** 适用楼栋 */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    /** 放行的订单类型（如 MEDICINE 药品） */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    /** 有效期起 */
    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    /** 有效期止 */
    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExemptionStatus status = ExemptionStatus.ACTIVE;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ExemptionStatus.ACTIVE;
        }
    }
}
