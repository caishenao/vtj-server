package cn.cai.vtjserver.entity;

import cn.cai.vtjserver.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "vtj_templates", autoResultMap = true)
public class TemplateEntity {
    @TableId
    private String id;
    private String platform;
    private String category;
    private String title;
    private String description;
    private String cover;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> dsl;
    private String creator;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
