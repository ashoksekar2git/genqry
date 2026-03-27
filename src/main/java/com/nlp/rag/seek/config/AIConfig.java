package com.nlp.rag.seek.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for AI and RAG pipeline
 */
@Configuration
public class AIConfig {

    /**
     * Primary ChatClient for SQL generation — used by SQLGenerationService.
     */
    @Primary
    @Bean("sqlChatClient")
    @ConditionalOnProperty(name = "spring.ai.openai.api-key", matchIfMissing = false)
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are an expert PostgreSQL developer.
                        Always generate syntactically correct, efficient SQL.
                        Prefer explicit JOINs. End statements with a semicolon.
                        """)
                .build();
    }

    /**
     * Separate ChatClient for document-grounded Q&A — used by DocumentRagService.
     * Explicitly instructed NOT to generate SQL and to answer only from provided context.
     */
    @Bean("docChatClient")
    @ConditionalOnProperty(name = "spring.ai.openai.api-key", matchIfMissing = false)
    public ChatClient docChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are a helpful document assistant that answers questions strictly \
                        from the document excerpts provided in the prompt.
                        You do NOT generate SQL queries. You do NOT query databases.
                        You only read and reason over the text excerpts given.
                        If the answer is not found in the provided excerpts, say so clearly.
                        Be concise, accurate, and cite the relevant excerpt number where possible.

                        IMPORTANT — Financial and Tax Document Tables:
                        PDF-extracted financial tables may appear as dense text with column headers \
                        listed first, followed by their values. For example:
                        "Total Proceeds  Total Cost Basis  Total Wash Sale Loss Disallowed  Total Net Gain or Loss(-)  \
                        313,368.69  265,795.44  -10,049.81  37,523.44"
                        means: Total Proceeds=313,368.69, Cost Basis=265,795.44, \
                        Wash Sale Loss Disallowed=-10,049.81, Net Gain or Loss=37,523.44.
                        Always map the N-th value to the N-th column header. \
                        Report the exact dollar amount for the specific column asked about.
                        """)
                .build();
    }
}
