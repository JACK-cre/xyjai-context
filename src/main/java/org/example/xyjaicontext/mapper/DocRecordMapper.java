package org.example.xyjaicontext.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.xyjaicontext.model.DocRecord;
@Mapper
public interface DocRecordMapper {
    int insert(DocRecord record);
    int updateStatus(Long id, Integer status, String errorMsg);
}

