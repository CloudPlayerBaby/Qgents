package qg.qgent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MergeRequestGroupEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * MR 与需求群关系访问。复合主键关联表，使用专用 Mapper 方法，不伪造单主键。
 */
@Mapper
public interface MergeRequestGroupMapper {

    @Insert("INSERT INTO merge_request_groups (merge_request_id, requirement_group_id) " +
            "VALUES (#{mergeRequestId}, #{requirementGroupId})")
    void insert(MergeRequestGroupEntity entity);

    @Select("SELECT merge_request_id, requirement_group_id FROM merge_request_groups " +
            "WHERE merge_request_id = #{mergeRequestId}")
    @Results({
            @Result(column = "merge_request_id", property = "mergeRequestId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<MergeRequestGroupEntity> selectByMergeRequestId(@Param("mergeRequestId") UUID mergeRequestId);

    @Select("SELECT merge_request_id, requirement_group_id FROM merge_request_groups " +
            "WHERE requirement_group_id = #{requirementGroupId}")
    @Results({
            @Result(column = "merge_request_id", property = "mergeRequestId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<MergeRequestGroupEntity> selectByRequirementGroupId(@Param("requirementGroupId") UUID requirementGroupId);

    @Select("<script>SELECT merge_request_id, requirement_group_id FROM merge_request_groups " +
            "WHERE merge_request_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    @Results({
            @Result(column = "merge_request_id", property = "mergeRequestId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<MergeRequestGroupEntity> selectByMergeRequestIds(@Param("ids") List<UUID> ids);
}
