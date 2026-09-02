package org.example.xyjaicontext.agent;

public final class AgentNodeNames {
    public static final String LOAD_MEMORY = "load_memory";
    public static final String ROUTE_INTENT = "route_intent";
    public static final String REWRITE_QUERY = "rewrite_query";
    public static final String RETRIEVE_CHROMA = "retrieve_chroma";
    public static final String COMPRESS_CONTEXT = "compress_context";
    public static final String GENERATE_ANSWER = "generate_answer";
    public static final String VERIFY_ANSWER = "verify_answer";
    public static final String PERSIST_MEMORY = "persist_memory";

    public static final String PARSE_DOCUMENT = "parse_document";
    public static final String SUMMARIZE_DOCUMENT = "summarize_document";
    public static final String EXTRACT_FACTS = "extract_facts";
    public static final String GENERATE_QUESTIONS = "generate_questions";
    public static final String INDEX_DOCUMENT = "index_document";
    public static final String FINALIZE_DOCUMENT = "finalize_document";

    private AgentNodeNames() {
    }
}
