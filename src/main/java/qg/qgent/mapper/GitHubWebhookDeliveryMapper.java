package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.GitHubWebhookDeliveryEntity;

import java.time.LocalDateTime;

@Mapper
public interface GitHubWebhookDeliveryMapper extends BaseMapper<GitHubWebhookDeliveryEntity> {

    /**
     * 按 provider delivery id 原子领取投递记录（行锁）。
     * 并发重复投递时，同一 delivery 只会有一个请求成功领取并继续处理。
     */
    @Select("SELECT * FROM github_webhook_deliveries WHERE provider_delivery_id = #{deliveryId} FOR UPDATE")
    GitHubWebhookDeliveryEntity selectByProviderDeliveryIdForUpdate(@Param("deliveryId") String deliveryId);

    /**
     * 清理保留窗口前已完成的投递记录。
     * 只删除 PROCESSED/IGNORED/FAILED 的过期记录，RECEIVED（处理中或中断）保留以便审计和重投。
     */
    @Delete("DELETE FROM github_webhook_deliveries "
            + "WHERE status <> 'RECEIVED' AND updated_at < #{before}")
    int deleteCompletedBefore(@Param("before") LocalDateTime before);
}
