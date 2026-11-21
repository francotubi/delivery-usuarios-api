package cl.deliveryapp.usuarios;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ErroresApi {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail estado(ResponseStatusException error) {
        return error.getBody();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException error) {
        var detalle = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Revisa los campos enviados.");
        Map<String, String> campos = error.getBindingResult().getFieldErrors().stream().collect(Collectors.toMap(
                campo -> campo.getField(), campo -> campo.getDefaultMessage(), (primero, segundo) -> primero));
        detalle.setProperty("campos", campos);
        return detalle;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflicto() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "La operación entra en conflicto con los datos existentes.");
    }
}
