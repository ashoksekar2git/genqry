package com.nlp.rag.seek.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for AI and RAG pipeline.
 *
 * <p>In <b>secretsfree</b> mode the app starts with {@code spring.ai.openai.api-key=NOT_SET}.
 * Spring AI auto-config still creates beans, but they can't reach OpenAI.
 * After the admin uploads thiravucoal.json the {@link SecretStore} holds the real key and
 * {@link #reinitialize(String)} is called by {@code SecretBootstrapService} to rebuild
 * the {@link ChatModel}, {@link EmbeddingModel} and both {@link ChatClient} instances
 * with the live key.</p>
 */
@Configuration
public class AIConfig {

    private static final Logger log = LoggerFactory.getLogger(AIConfig.class);

    // ── System prompts (kept as constants so reinitialize() reuses them) ─────
    private static final String SQL_SYSTEM = """
            You are an expert PostgreSQL developer.
            Always generate syntactically correct, efficient SQL.
            Prefer explicit JOINs. End statements with a semicolon.
            """;

    private static final String DOC_SYSTEM = """
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
            """;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String chatModelName;

    @Value("${spring.ai.openai.chat.options.temperature:0.0}")
    private double chatTemperature;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}")
    private String embeddingModelName;

    // ── Mutable holders — updated by reinitialize() ─────────────────────────
    private volatile ChatClient sqlChatClientInstance;
    private volatile ChatClient docChatClientInstance;
    private volatile ChatModel chatModelInstance;
    private volatile EmbeddingModel embeddingModelInstance;

    // ── Beans exposed to the rest of the application ─────────────────────────

    /**
     * Primary ChatClient for SQL generation — used by SQLGenerationService.
     * Returns null until a valid API key is available (secretsfree startup).
     */
    @Primary
    @Bean("sqlChatClient")
    public ChatClient chatClient() {
        return sqlChatClientInstance;  // null at startup in secretsfree mode
    }

    /**
     * Separate ChatClient for document-grounded Q&A — used by DocumentRagService.
     */
    @Bean("docChatClient")
    public ChatClient docChatClient() {
        return docChatClientInstance;  // null at startup in secretsfree mode
    }

    // ── Holders that return the live reference ──────────────────────────────
    /**
     * Thread-safe accessor for the current sqlChatClient.
     * Services should prefer this over the @Autowired field when they need
     * to pick up a post-bootstrap reinitialised client.
     */
    public ChatClient getSqlChatClient() {
        return sqlChatClientInstance;
    }

    public ChatClient getDocChatClient() {
        return docChatClientInstance;
    }

    public ChatModel getChatModel() {
        return chatModelInstance;
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModelInstance;
    }

    // ── Re-initialisation (called by SecretBootstrapService) ─────────────────

    /**
     * Rebuild OpenAI ChatModel, EmbeddingModel and both ChatClients with the
     * given (real) API key. Thread-safe via volatile fields.
     */
    public void reinitialize(String apiKey) {
        log.info("AIConfig: reinitializing OpenAI clients with live API key (length={})", apiKey.length());

        // 1. Build a fresh OpenAiApi with the real key
        OpenAiApi api = new OpenAiApi(apiKey);

        // 2. Rebuild ChatModel
        OpenAiChatOptions chatOpts = OpenAiChatOptions.builder()
                .withModel(chatModelName)
                .withTemperature((float) chatTemperature)
                .build();
        chatModelInstance = new OpenAiChatModel(api, chatOpts);

        // 3. Rebuild EmbeddingModel
        OpenAiEmbeddingOptions embeddingOpts = OpenAiEmbeddingOptions.builder()
                .withModel(embeddingModelName)
                .build();
        embeddingModelInstance = new OpenAiEmbeddingModel(api,
                org.springframework.ai.document.MetadataMode.EMBED,
                embeddingOpts);

        // 4. Rebuild ChatClients
        sqlChatClientInstance = ChatClient.builder(chatModelInstance)
                .defaultSystem(SQL_SYSTEM)
                .build();

        docChatClientInstance = ChatClient.builder(chatModelInstance)
                .defaultSystem(DOC_SYSTEM)
                .build();

        log.info("AIConfig: ✅ OpenAI ChatModel ({}), EmbeddingModel ({}) and ChatClients rebuilt",
                chatModelName, embeddingModelName);
    }
}
