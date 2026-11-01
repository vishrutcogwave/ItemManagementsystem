package com.itemmanagement.security;

import com.itemmanagement.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One password-strength policy shared by registration, administrator resets,
 * first-time provisioning and self-service password changes, so a password
 * accepted in one flow cannot be rejected in another.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789",
            "qwerty123", "admin123", "letmein", "welcome1", "iloveyou",
            "abc12345", "changeme", "passw0rd", "administrator");

    public void validate(String password, String username, String email) {
        String error = problem(password, username, email);
        if (error != null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    error, Map.of("password", error));
        }
    }

    public String problem(String password, String username, String email) {
        if (password == null || password.isBlank()) {
            return "Password is required";
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return "Password must be " + MIN_LENGTH + "-" + MAX_LENGTH + " characters";
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            return "Password must not contain spaces";
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean special = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!(upper && lower && digit && special)) {
            return "Password must include upper-case, lower-case, number and special characters";
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        if (COMMON_PASSWORDS.contains(normalized)) {
            return "Choose a less common password";
        }
        if (username != null && normalized.equals(username.trim().toLowerCase(Locale.ROOT))) {
            return "Password must not match the username";
        }
        if (email != null) {
            String local = email.split("@", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!local.isEmpty() && normalized.equals(local)) {
                return "Password must not match the email address";
            }
        }
        return null;
    }
}
