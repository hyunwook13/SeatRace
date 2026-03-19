package org.example.seatrace.security;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

@Documented
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@PreAuthorize("hasRole('ADMIN')")
public @interface AdminOnly {
}
