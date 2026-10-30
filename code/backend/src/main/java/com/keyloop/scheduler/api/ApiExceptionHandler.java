package com.keyloop.scheduler.api;

import com.keyloop.scheduler.service.BookingException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BookingException.class)
    ResponseEntity<ApiModels.ApiError> bookingError(BookingException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getStatus() == org.springframework.http.HttpStatus.ACCEPTED) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(new ApiModels.ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiModels.ApiError> invalidRequest(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiModels.ApiError("Required IDs must be positive and all fields must be supplied."));
    }
}
