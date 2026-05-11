package cn.cai.vtjserver.entity;

import cn.cai.vtjserver.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "vtj_materials", autoResultMap = true)
public class MaterialEntity {
    @TableId
    private String projectId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> materials;
    private OffsetDateTime updatedAt;
}
