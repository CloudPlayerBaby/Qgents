package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * 分支质量门禁与强制测试集关系访问。复合主键关联表，使用专用 Mapper 方法。
 */
@Mapper
public interface RepositoryBranchConfigTestsetMapper extends BaseMapper<RepositoryBranchConfigTestsetEntity> {

    @Select("SELECT branch_config_id, testset_id FROM repository_branch_config_testsets " +
            "WHERE branch_config_id = #{branchConfigId}")
    @Results({
            @Result(column = "branch_config_id", property = "branchConfigId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "testset_id", property = "testsetId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<RepositoryBranchConfigTestsetEntity> selectByBranchConfigId(@Param("branchConfigId") UUID branchConfigId);

    /**
     * 返回引用指定 Testset 的门禁数量，用于阻止删除仍在使用的配置。
     */
    @Select("SELECT COUNT(*) FROM repository_branch_config_testsets WHERE testset_id = #{testsetId}")
    long countByTestsetId(@Param("testsetId") UUID testsetId);

}
