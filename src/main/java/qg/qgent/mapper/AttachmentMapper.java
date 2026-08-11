package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.AttachmentEntity;

/**
 * 附件元数据数据访问。
 */
@Mapper
public interface AttachmentMapper extends BaseMapper<AttachmentEntity> {
}
