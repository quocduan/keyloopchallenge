package com.keyloop.scheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class BookingLockConfigurationTest {
    @Test
    void heldLocksRenewTheirLeaseAndCancelRenewalOnUnlock() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.evalSha(anyString(), eq(ReturnType.BOOLEAN), anyInt(), any(byte[][].class)))
                .thenReturn(true);
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        RedisLockRegistry registry = (RedisLockRegistry) new BookingLockConfiguration()
                .bookingLockRegistry(factory, scheduler);
        try {
            Lock lock = registry.obtain("idempotency:test");
            assertThat(lock.tryLock()).isTrue();
            try {
                ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(Duration.ofSeconds(40)));
                clearInvocations(connection);
                task.getValue().run();
                // Renewal must reach Redis, rather than only extending local lock state.
                verify(connection).evalSha(anyString(), eq(ReturnType.BOOLEAN), eq(1), any(byte[][].class));
            } finally {
                lock.unlock();
            }
            verify(renewal).cancel(true);
        } finally {
            registry.destroy();
        }
    }
}
