package com.keyloop.scheduler.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DefaultLockRegistry;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class BookingLockConfiguration {
    @Bean
    @ConditionalOnProperty(name = "scheduler.booking.redis-locks-enabled", havingValue = "false")
    LockRegistry localBookingLockRegistry() {
        return new DefaultLockRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "scheduler.booking.redis-locks-enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler bookingLockRenewalScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("booking-lock-renewal-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "scheduler.booking.redis-locks-enabled", havingValue = "true", matchIfMissing = true)
    LockRegistry bookingLockRegistry(RedisConnectionFactory connectionFactory,
                                    ThreadPoolTaskScheduler bookingLockRenewalScheduler) {
        // The hash tag keeps the registry's Lua script keys in one Redis Cluster slot.
        RedisLockRegistry registry = new RedisLockRegistry(
                connectionFactory, "booking-locks{booking}", Duration.ofMinutes(2).toMillis());
        // Renew held locks every third of the lease (40 seconds). Unlock cancels renewal;
        // the lease still expires automatically if this instance stops running.
        registry.setRenewalTaskScheduler(bookingLockRenewalScheduler);
        return registry;
    }
}
