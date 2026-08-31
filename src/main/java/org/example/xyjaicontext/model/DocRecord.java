package org.example.xyjaicontext.model;

import lombok.Data;
import java.time.LocalDateTime;
@Data
public class DocRecord {
    private Long id;
    private String fileName;
    private Integer status;
    private String errorMessage;
    private LocalDateTime createTime;
}

