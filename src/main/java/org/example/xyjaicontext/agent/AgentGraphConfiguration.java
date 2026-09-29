package org.example.xyjaicontext.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class AgentGraphConfiguration {

    @Bean(name = "agentGraphKeyStrategyFactory")
    public KeyStrategyFactory agentGraphKeyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(KeyStrategy.REPLACE)
                .addStrategy(GraphStateKeys.RECENT_MESSAGES, KeyStrategy.REPLACE)
                .addStrategy(GraphStateKeys.RETRIEVED_CHUNKS, KeyStrategy.REPLACE)
                .addStrategy(GraphStateKeys.CHUNKS, KeyStrategy.REPLACE)
                .addStrategy(GraphStateKeys.PARENT_CHUNKS, KeyStrategy.REPLACE)
                .addStrategy(GraphStateKeys.STRUCTURED_TABLES, KeyStrategy.REPLACE)
                .build();
    }

    @Bean
    public RedisSaver agentRedisSaver(RedissonClient redissonClient) {
        return RedisSaver.builder().redisson(redissonClient).build();
    }

    @Bean(name = "chatStateGraph")
    public StateGraph chatStateGraph(AgentGraphNodes nodes,
                                     @Qualifier("agentGraphKeyStrategyFactory") KeyStrategyFactory keyFactory)
            throws GraphStateException {
        StateGraph graph = new StateGraph("chat_agent_workflow", keyFactory);
        graph.addNode(AgentNodeNames.LOAD_MEMORY, AsyncNodeAction.node_async(nodes::loadMemory))
                .addNode(AgentNodeNames.ROUTE_INTENT, AsyncNodeAction.node_async(nodes::routeIntent))
                .addNode(AgentNodeNames.REWRITE_QUERY, AsyncNodeAction.node_async(nodes::rewriteQuery))
                .addNode(AgentNodeNames.RETRIEVE_CHROMA, AsyncNodeAction.node_async(nodes::retrieveChroma))
                .addNode(AgentNodeNames.COMPRESS_CONTEXT, AsyncNodeAction.node_async(nodes::compressContext))
                .addNode(AgentNodeNames.GENERATE_ANSWER, AsyncNodeAction.node_async(nodes::generateAnswer))
                .addNode(AgentNodeNames.VERIFY_ANSWER, AsyncNodeAction.node_async(nodes::verifyAnswer))
                .addNode(AgentNodeNames.PERSIST_MEMORY, AsyncNodeAction.node_async(nodes::persistMemory))
                .addEdge(StateGraph.START, AgentNodeNames.LOAD_MEMORY)
                .addEdge(AgentNodeNames.LOAD_MEMORY, AgentNodeNames.ROUTE_INTENT)
                .addConditionalEdges(AgentNodeNames.ROUTE_INTENT,
                        AsyncEdgeAction.edge_async(nodes::routeAfterIntent),
                        Map.of("RAG", AgentNodeNames.REWRITE_QUERY,
                                "NORMAL", AgentNodeNames.GENERATE_ANSWER))
                .addEdge(AgentNodeNames.REWRITE_QUERY, AgentNodeNames.RETRIEVE_CHROMA)
                .addEdge(AgentNodeNames.RETRIEVE_CHROMA, AgentNodeNames.COMPRESS_CONTEXT)
                .addEdge(AgentNodeNames.COMPRESS_CONTEXT, AgentNodeNames.GENERATE_ANSWER)
                .addEdge(AgentNodeNames.GENERATE_ANSWER, AgentNodeNames.VERIFY_ANSWER)
                .addConditionalEdges(AgentNodeNames.VERIFY_ANSWER,
                        AsyncEdgeAction.edge_async(nodes::routeAfterVerification),
                        Map.of("RETRY", AgentNodeNames.GENERATE_ANSWER,
                                "DONE", AgentNodeNames.PERSIST_MEMORY))
                .addEdge(AgentNodeNames.PERSIST_MEMORY, StateGraph.END);
        return graph;
    }

    @Bean(name = "chatCompiledGraph")
    public CompiledGraph chatCompiledGraph(@Qualifier("chatStateGraph") StateGraph graph,
                                           RedisSaver saver) throws GraphStateException {
        return graph.compile(CompileConfig.builder()
                .recursionLimit(18)
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build());
    }

    @Bean(name = "documentStateGraph")
    public StateGraph documentStateGraph(AgentGraphNodes nodes,
                                         @Qualifier("agentGraphKeyStrategyFactory") KeyStrategyFactory keyFactory)
            throws GraphStateException {
        StateGraph graph = new StateGraph("document_analysis_workflow", keyFactory);
        graph.addNode(AgentNodeNames.PARSE_DOCUMENT, AsyncNodeAction.node_async(nodes::parseDocument))
                .addNode(AgentNodeNames.SUMMARIZE_DOCUMENT, AsyncNodeAction.node_async(nodes::summarizeDocument))
                .addNode(AgentNodeNames.EXTRACT_FACTS, AsyncNodeAction.node_async(nodes::extractFacts))
                .addNode(AgentNodeNames.GENERATE_QUESTIONS, AsyncNodeAction.node_async(nodes::generateQuestions))
                .addNode(AgentNodeNames.INDEX_DOCUMENT, AsyncNodeAction.node_async(nodes::indexDocument))
                .addNode(AgentNodeNames.FINALIZE_DOCUMENT, AsyncNodeAction.node_async(nodes::finalizeDocument))
                .addEdge(StateGraph.START, AgentNodeNames.PARSE_DOCUMENT)
                .addEdge(AgentNodeNames.PARSE_DOCUMENT, AgentNodeNames.SUMMARIZE_DOCUMENT)
                .addEdge(AgentNodeNames.SUMMARIZE_DOCUMENT, AgentNodeNames.EXTRACT_FACTS)
                .addEdge(AgentNodeNames.EXTRACT_FACTS, AgentNodeNames.GENERATE_QUESTIONS)
                .addEdge(AgentNodeNames.GENERATE_QUESTIONS, AgentNodeNames.INDEX_DOCUMENT)
                .addEdge(AgentNodeNames.INDEX_DOCUMENT, AgentNodeNames.FINALIZE_DOCUMENT)
                .addEdge(AgentNodeNames.FINALIZE_DOCUMENT, StateGraph.END);
        return graph;
    }

    @Bean(name = "documentCompiledGraph")
    public CompiledGraph documentCompiledGraph(@Qualifier("documentStateGraph") StateGraph graph,
                                               RedisSaver saver) throws GraphStateException {
        return graph.compile(CompileConfig.builder()
                .recursionLimit(16)
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build());
    }
}
