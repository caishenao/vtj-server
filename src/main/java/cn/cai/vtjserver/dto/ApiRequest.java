package cn.cai.vtjserver.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ApiRequest {
    private String type;
    private Object data;
    private Map<String, Object> query;
}
