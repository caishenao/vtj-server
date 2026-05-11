package cn.cai.vtjserver.mapper;

import cn.cai.vtjserver.entity.HistoryItemEntity;
import cn.cai.vtjserver.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

public interface HistoryItemMapper extends BaseMapper<HistoryItemEntity> {
    @Select("SELECT file_id, id, item, created_at, updated_at FROM vtj_history_items WHERE file_id = #{fileId} AND id = #{id}")
    HistoryItemEntity selectByFileAndId(@Param("fileId") String fileId, @Param("id") String id);

    @Insert("""
            INSERT INTO vtj_history_items(file_id, id, item, created_at, updated_at)
            VALUES (#{fileId}, #{id}, #{item,typeHandler=cn.cai.vtjserver.mybatis.JsonbTypeHandler}, now(), now())
            ON CONFLICT (file_id, id)
            DO UPDATE SET item = #{item,typeHandler=cn.cai.vtjserver.mybatis.JsonbTypeHandler}, updated_at = now()
            """)
    void upsert(@Param("fileId") String fileId, @Param("id") String id, @Param("item") Map<String, Object> item);

    @Delete("DELETE FROM vtj_history_items WHERE file_id = #{fileId} AND id = #{id}")
    int deleteByFileAndId(@Param("fileId") String fileId, @Param("id") String id);
}
