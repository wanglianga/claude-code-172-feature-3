package com.example.robotdelivery.notification;

import com.example.robotdelivery.order.DeliveryOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通知服务：模拟短信与小程序推送（真实环境对接短信网关/微信开放平台）。
 */
@Slf4j
@Service
public class NotificationService {

    private static final Map<com.example.robotdelivery.order.OrderType, String> TYPE_CN = Map.of(
            com.example.robotdelivery.order.OrderType.FOOD, "外卖",
            com.example.robotdelivery.order.OrderType.MEDICINE, "药品",
            com.example.robotdelivery.order.OrderType.GROCERY, "商超");

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 到达门口后通过短信 + 小程序通知用户取件。
     */
    public List<Notification> notifyArrival(DeliveryOrder order) {
        String content = String.format(
                "【社区配送】您的%s订单%s已送达%s%s栋%s单元%s，载物仓%s号仓，请尽快取件。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                order.getCommunity().getName(),
                order.getBuilding().getName(),
                order.getUnitNo() == null ? "" : order.getUnitNo(),
                order.getRoomNo() == null ? "" : order.getRoomNo(),
                order.getCompartmentNo());
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户取件: deliveryId={} phone={} channels=[SMS, MINI_PROGRAM]",
                order.getId(), order.getUserPhone());
        return List.of(sms, mini);
    }

    /**
     * 夜间限行改门岗自提：包裹到小区门岗后通知用户凭取件码自取。
     */
    public List<Notification> notifyGatePickup(DeliveryOrder order, String gateName) {
        String content = String.format(
                "【社区配送】因物业夜间限行，您的%s订单%s已转%s%s门岗自提，请凭手机号%s到门岗取件。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                order.getCommunity().getName(),
                gateName == null ? "" : gateName,
                order.getUserPhone());
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户门岗自提: deliveryId={} gate={} channels=[SMS, MINI_PROGRAM]",
                order.getId(), gateName);
        return List.of(sms, mini);
    }

    private Notification save(Long deliveryId, NotifyChannel channel, String target, String content) {
        Notification notification = new Notification();
        notification.setDeliveryId(deliveryId);
        notification.setChannel(channel);
        notification.setTarget(target);
        notification.setContent(content);
        notification.setStatus("SENT");
        return notificationRepository.save(notification);
    }
}
