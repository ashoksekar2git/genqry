package com.nlp.rag.seek.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nlp.rag.seek.model.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

/**
 * Redis configuration for the semantic query cache.
 *
 * - Uses Jedis as the connection driver
 * - Serialises keys as plain Strings
 * - Serialises values as JSON (Jackson2JsonRedisSerializer<CacheEntry>)
 *   with JavaTimeModule so Instant fields round-trip correctly
 * - Gracefully disabled when cache.semantic.enabled=false
 */
@Configuration
@ConditionalOnProperty(name = "cache.semantic.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.data.redis.host:localhost}") private String host;
    @Value("${spring.data.redis.port:6379}")      private int    port;
    @Value("${spring.data.redis.password:}")      private String password;
    @Value("${spring.data.redis.timeout:2000ms}")  private Duration timeout;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }

        JedisClientConfiguration clientCfg = JedisClientConfiguration.builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();

        JedisConnectionFactory factory = new JedisConnectionFactory(cfg, clientCfg);
        log.info("Redis connection factory configured → {}:{}", host, port);
        return factory;
    }

    @Bean
    public RedisTemplate<String, CacheEntry> redisCacheTemplate(
            RedisConnectionFactory connectionFactory) {

        // ObjectMapper with JavaTimeModule for Instant serialization
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Store type info so Jackson can deserialize the nested SQLGenerationResponse
        om.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        Jackson2JsonRedisSerializer<CacheEntry> valueSerializer =
                new Jackson2JsonRedisSerializer<>(om, CacheEntry.class);

        RedisTemplate<String, CacheEntry> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();

        log.info("RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer");
        return template;
    }
}

