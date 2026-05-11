package cn.cai.vtjserver.entity;

import cn.cai.vtjserver.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "vtj_history_items", autoResultMap = true)
public class HistoryItemEntity {
    private String fileId;
    @TableId
    private String id;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> item;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
