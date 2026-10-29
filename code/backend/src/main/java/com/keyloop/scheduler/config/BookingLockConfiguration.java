package com.keyloop.scheduler.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;

@Configuration
public class BookingLockConfiguration {
    @Bean
    @ConditionalOnProperty(name = "scheduler.booking.redis-locks-enabled", havingValue = "false")
    LockRegistry localBookingLockRegistry() {
        return new DefaultLockRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "scheduler.booking.redis-locks-enabled", havingValue = "true", matchIfMissing = true)
    LockRegistry bookingLockRegistry(RedisConnectionFactory connectionFactory) {
        // The hash tag keeps the registry's Lua script keys in one Redis Cluster slot.
        return new RedisLockRegistry(connectionFactory, "booking-locks{booking}", Duration.ofMinutes(2).toMillis());
    }
}
