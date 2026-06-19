package com.parallelcart.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            @Value("${app.cache.product.ttl-seconds:60}") long productTtlSeconds,
            @Value("${app.cache.carts.ttl:15m}") Duration cartsTtl,
            @Value("${app.cache.orders.ttl:30m}") Duration ordersTtl) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        RedisCacheConfiguration productCacheConfig = defaultConfig.entryTtl(Duration.ofSeconds(productTtlSeconds));
        RedisCacheConfiguration cartsCacheConfig = defaultConfig.entryTtl(cartsTtl);
        RedisCacheConfiguration ordersCacheConfig = defaultConfig.entryTtl(ordersTtl);

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("products", productCacheConfig)
                .withCacheConfiguration("carts", cartsCacheConfig)
                .withCacheConfiguration("orders", ordersCacheConfig)
                .build();
    }
}
