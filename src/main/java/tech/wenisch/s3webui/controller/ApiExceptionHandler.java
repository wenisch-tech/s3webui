package tech.wenisch.s3webui.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tech.wenisch.s3webui.service.MissingS3ConfigurationException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingS3ConfigurationException.class)
    public ResponseEntity<Map<String, String>> handleMissingS3Configuration(MissingS3ConfigurationException exception) {
        return ResponseEntity.status(428).body(Map.of("message", exception.getMessage()));
    }
}
