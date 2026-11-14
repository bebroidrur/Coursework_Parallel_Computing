package com.coursework.backend.error;
import com.coursework.backend.dto.ErrorResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse("error", e.code(), e.getMessage()));
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> "Некоректний JSON або тип поля запиту";
            case 404 -> "Маршрут не знайдено";
            case 405 -> "HTTP-метод не підтримується для цього маршруту";
            case 415 -> "Потрібний Content-Type: application/json";
            default -> "Не вдалося обробити HTTP-запит";
        };
        return new ResponseEntity<>(new ErrorResponse("error", "HTTP_" + status.value(), message), headers, status);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        logger.error("Unexpected API failure", e);
        return ResponseEntity.internalServerError().body(new ErrorResponse("error", "INTERNAL_ERROR", "Внутрішня помилка сервера"));
    }
}
