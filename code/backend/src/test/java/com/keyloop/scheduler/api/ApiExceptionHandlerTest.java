package com.keyloop.scheduler.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionTimedOutException;
import com.keyloop.scheduler.service.BookingService;

class ApiExceptionHandlerTest {
    static Stream<RuntimeException> timeoutFailures() {
        return Stream.of(new CannotAcquireLockException("database lock expired"),
                new QueryTimeoutException("statement deadline expired"),
                new TransactionTimedOutException("transaction deadline expired"));
    }

    @ParameterizedTest
    @MethodSource("timeoutFailures")
    void timeoutFailuresReturnRetryableResponse(RuntimeException failure) throws Exception {
        BookingService service = mock(BookingService.class);
        when(service.getAppointment(1L)).thenThrow(failure);
        MockMvcBuilders.standaloneSetup(new BookingController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(get("/api/appointments/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.message").value("Booking is busy or timed out; please retry shortly."));
    }
}
