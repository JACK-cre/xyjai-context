package org.example.xyjaicontext.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMessageSender messageSender;

    public String processDocumentAsync(MultipartFile file) throws IOException {
        log.info("提交文档处理任务: {}", file.getOriginalFilename());
        // 发送消息到RabbitMQ并返回任务ID
        return messageSender.sendDocumentForProcessing(file);
    }
}