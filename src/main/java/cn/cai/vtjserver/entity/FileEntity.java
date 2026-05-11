package cn.cai.vtjserver.entity;

import cn.cai.vtjserver.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "vtj_files", autoResultMap = true)
public class FileEntity {
    @TableId
    private String id;
    private String projectId;
    private String platform;
    private String name;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> dsl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
