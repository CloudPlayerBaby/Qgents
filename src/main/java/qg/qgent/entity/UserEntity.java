package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("users")
public class UserEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String passwordHash;
    private String passwordAlgorithm;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
