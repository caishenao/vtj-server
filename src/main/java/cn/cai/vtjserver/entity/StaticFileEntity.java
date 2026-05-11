package cn.cai.vtjserver.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("vtj_static_files")
public class StaticFileEntity {
    @TableId
    private String id;
    private String projectId;
    private String filename;
    private String filepath;
    private String contentType;
    private Long sizeBytes;
    private OffsetDateTime createdAt;
}
