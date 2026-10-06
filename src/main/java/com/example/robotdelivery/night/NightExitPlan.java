package com.example.robotdelivery.night;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.robot.Robot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 夜间限行开始时机器人已在楼内：系统要求尽快完成当前订单并按路线撤离。
 * 物业端可查看催办时间、订单预计完成时间（expectedExitAt）与撤离路线。
 */
@Getter
@Setter
@Entity
@Table(name = "night_exit_plan")
public class NightExitPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NightExitPlanStatus status;

    /** 撤离路线（当前位置 -> 电梯/楼梯 -> 一层大堂 -> 小区门岗 -> 楼外等待区） */
    @Column(name = "exit_route", nullable = false, length = 512)
    private String exitRoute;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;

    /** 要求完成订单并撤离楼栋的截止时间 */
    @Column(name = "expected_exit_at", nullable = false)
    private LocalDateTime expectedExitAt;

    /** 当前订单实际完成（签收/退回）时间 */
    @Column(name = "finished_order_at")
    private LocalDateTime finishedOrderAt;

    /** 机器人实际撤离楼栋时间 */
    @Column(name = "exited_at")
    private LocalDateTime exitedAt;
}
