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
     * 清理保留窗口前的投递记录：PROCESSED/IGNORED/FAILED 按 updated_at 过期删除；
     * RECEIVED 仅当超过保留窗口（处理中断的僵尸记录，不可能仍在处理中）时一并清除。
     */
    @Delete("DELETE FROM github_webhook_deliveries "
            + "WHERE (status <> 'RECEIVED' OR received_at < #{before}) AND updated_at < #{before}")
    int deleteCompletedBefore(@Param("before") LocalDateTime before);
}
