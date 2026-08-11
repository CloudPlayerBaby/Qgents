package qg.qgent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMemberMapper {
    @Select("SELECT project_id, user_id, role FROM project_members WHERE user_id = #{userId}")
    @Results({
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    List<ProjectMemberEntity> selectByUserId(UUID userId);

    @Select("SELECT project_id, user_id, role FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    @Results({
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    ProjectMemberEntity selectByProjectAndUser(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
