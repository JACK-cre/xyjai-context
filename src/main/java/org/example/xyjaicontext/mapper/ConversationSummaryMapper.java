package org.example.xyjaicontext.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.xyjaicontext.model.ConversationSummary;

@Mapper
public interface ConversationSummaryMapper {

    ConversationSummary selectByConversationId(@Param("conversationId") String conversationId,
                                                @Param("username") String username);

    int insert(ConversationSummary summary);

    int updateIfVersionMatches(ConversationSummary summary);

    int deleteByConversationId(@Param("conversationId") String conversationId,
                                @Param("username") String username);
}
