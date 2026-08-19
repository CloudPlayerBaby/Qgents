package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.UserEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 锁定用户行，用作该用户通知序号分配的跨实例串行化锚点。
     */
    @Select("SELECT id, email, display_name, avatar_url, avatar_object_key, password_hash, password_algorithm, "
            + "status, created_at, updated_at "
            + "FROM users WHERE id = #{userId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "password_algorithm", property = "passwordAlgorithm"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "avatar_object_key", property = "avatarObjectKey"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt")
    })
    UserEntity selectByIdForUpdate(@Param("userId") UUID userId);
}
