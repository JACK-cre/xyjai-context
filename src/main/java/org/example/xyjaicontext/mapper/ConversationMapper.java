package org.example.xyjaicontext.mapper;

import org.example.xyjaicontext.model.ConversationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationMapper {
    void insert(ConversationRecord record);
    void update(ConversationRecord record);
    // 修改后
    ConversationRecord selectByConversationId(@Param("conversationId") String conversationId, @Param("username") String username);
    List<ConversationRecord> selectByUsername(@Param("username") String username);
    void deleteByConversationId(@Param("conversationId") String conversationId, @Param("username") String username);
}
