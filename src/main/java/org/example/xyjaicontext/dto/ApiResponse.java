package org.example.xyjaicontext.dto;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = 200; resp.msg = "success"; resp.data = data;
        return resp;
    }

    public static <T> ApiResponse<T> error(String msg) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = 500; resp.msg = msg;
        return resp;
    }
}

