package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import qg.qgent.entity.MergeRequestDeliveryOperationEntity;

import java.util.UUID;

@Mapper
public interface MergeRequestDeliveryOperationMapper extends BaseMapper<MergeRequestDeliveryOperationEntity> {
    @Select("select * from merge_request_delivery_operations where operation_key=#{operationKey} for update")
    MergeRequestDeliveryOperationEntity selectByKeyForUpdate(@Param("operationKey") String operationKey);

    @Select("select * from merge_request_delivery_operations where id=#{id} for update")
    MergeRequestDeliveryOperationEntity selectByIdForUpdate(@Param("id") UUID id);

    @Select("select * from merge_request_delivery_operations where project_repository_id=#{repositoryId} "
            + "and source_branch=#{sourceBranch} and target_branch=#{targetBranch} "
            + "and status in ('RUNNING','REMOTE_CREATED') order by created_at limit 1 for update")
    MergeRequestDeliveryOperationEntity selectActiveBranchForUpdate(@Param("repositoryId") UUID repositoryId,
            @Param("sourceBranch") String sourceBranch, @Param("targetBranch") String targetBranch);
}
