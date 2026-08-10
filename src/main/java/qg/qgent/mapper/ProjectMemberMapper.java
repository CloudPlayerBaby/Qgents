package qg.qgent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.typehandler.UuidBinaryTypeHandler;

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
}
