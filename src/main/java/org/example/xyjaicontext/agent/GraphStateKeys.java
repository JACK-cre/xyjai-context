package org.example.xyjaicontext.agent;

/** Shared state keys used by the chat and document StateGraphs. */
public final class GraphStateKeys {
    public static final String RUN_ID = "runId";
    public static final String TASK_ID = "taskId";
    public static final String USERNAME = "username";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String QUESTION = "question";
    public static final String USE_RAG = "useRag";
    public static final String ROUTE = "route";
    public static final String REWRITTEN_QUERY = "rewrittenQuery";//重写后的查询
    public static final String RECENT_MESSAGES = "recentMessages";
    public static final String CONVERSATION_SUMMARY = "conversationSummary";
    public static final String RETRIEVED_CHUNKS = "retrievedChunks";//检索到的数据块
    public static final String COMPRESSED_CONTEXT = "compressedContext";//压缩上下文
    public static final String DRAFT_ANSWER = "draftAnswer";//拟定答案
    public static final String FINAL_ANSWER = "finalAnswer";
    public static final String VERIFIED = "verified";//已验证
    public static final String RETRY_COUNT = "retryCount";//重试次数
    public static final String ERROR = "error";

    public static final String FILE_NAME = "fileName";
    public static final String FILE_CONTENT = "fileContent";
    public static final String FILE_REFERENCE = "fileReference";
    public static final String CHECKSUM = "checksum";//校验和
    public static final String PARSED_TEXT = "parsedText";//已解析文本
    public static final String CHUNKS = "chunks";//块
    public static final String PARENT_CHUNKS = "parentChunks";
    public static final String STRUCTURED_TABLES = "structuredTables";
    public static final String PARENT_COUNT = "parentCount";
    public static final String MIME_TYPE = "mimeType";
    public static final String PARSER_NAME = "parserName";
    public static final String PAGE_COUNT = "pageCount";
    public static final String TABLE_COUNT = "tableCount";
    public static final String DOCUMENT_SUMMARY = "documentSummary";//文档摘要
    public static final String FACTS = "facts";
    public static final String QUESTIONS = "questions";
    public static final String CHUNK_COUNT = "chunkCount";//分块数量
    public static final String DUPLICATE_OF = "duplicateOf";//副本
    public static final String DOCUMENT_STATUS = "documentStatus";
    public static final String CURRENT_NODE = "currentNode";//当前节点

    private GraphStateKeys() {
    }
}
