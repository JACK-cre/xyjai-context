package org.example.xyjaicontext.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final ChatClient.Builder chatClientBuilder;

    /**
     * 获取默认模型的ChatClient
     */
    public ChatClient getChatClient() {
        return chatClientBuilder
                .build();
    }
}