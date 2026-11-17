package com.itemmanagement.service;

import com.itemmanagement.dto.auth.LoginRequest;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.JwtService;
import com.itemmanagement.security.LoginAttemptService;
import com.itemmanagement.security.PasswordPolicy;
import com.itemmanagement.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the dummy-password comparison used to keep login timing consistent
 * for stores and usernames that do not exist.
 */
class AuthServiceLoginTimingTest {

    private StoreRepository storeRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        LoginAttemptService attempts = new LoginAttemptService(
                5, 900, 900, 20, 900, 900, 1_000, 10, 60, 300);
        authService = new AuthService(
                storeRepository,
                mock(BranchRepository.class),
                userRepository,
                passwordEncoder,
                mock(PasswordPolicy.class),
                mock(JwtService.class),
                mock(TokenBlacklistService.class),
                attempts,
                mock(AuditService.class));
    }

    @Test
    void unknownStoreStillPerformsOneDummyPasswordComparison() {
        when(storeRepository.findByStoreCodeIgnoreCase("NOSTORE")).thenReturn(Optional.empty());

        ApiException rejected = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("NOSTORE", "ghost", "Secret@123"),
                        "203.0.113.9", null));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
        assertEquals("Invalid store code, username or password", rejected.getMessage());
        assertDummyComparison("Secret@123");
    }

    @Test
    void unknownUsernameInAnExistingStoreStillPerformsOneDummyPasswordComparison() {
        Store store = Store.builder().id(7L).storeCode("SHOP").status(StoreStatus.ACTIVE).build();
        when(storeRepository.findByStoreCodeIgnoreCase("SHOP")).thenReturn(Optional.of(store));
        when(userRepository.findByStoreIdAndUsernameIgnoreCase(7L, "ghost")).thenReturn(Optional.empty());
        when(userRepository.findByStoreIdAndEmailIgnoreCase(7L, "ghost")).thenReturn(Optional.empty());

        ApiException rejected = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("SHOP", "ghost", "Secret@123"),
                        "203.0.113.9", null));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
        assertEquals("Invalid store code, username or password", rejected.getMessage());
        assertDummyComparison("Secret@123");
    }

    @Test
    void existingUserWithWrongPasswordIsComparedAgainstTheStoredHashOnly() {
        Store store = Store.builder().id(7L).storeCode("SHOP").status(StoreStatus.ACTIVE).build();
        String storedHash = "$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        User user = User.builder()
                .id(9L)
                .storeId(7L)
                .username("owner")
                .email("owner@test.local")
                .passwordHash(storedHash)
                .status(StoreStatus.ACTIVE)
                .build();
        when(storeRepository.findByStoreCodeIgnoreCase("SHOP")).thenReturn(Optional.of(store));
        when(userRepository.findByStoreIdAndUsernameIgnoreCase(7L, "owner"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Wrong@123", storedHash)).thenReturn(false);

        ApiException rejected = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("SHOP", "owner", "Wrong@123"),
                        "203.0.113.9", null));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
        assertEquals("Invalid store code, username or password", rejected.getMessage());
        verify(passwordEncoder, times(1)).matches("Wrong@123", storedHash);
        verify(passwordEncoder, never()).matches(anyString(), eq(AuthService.DUMMY_PASSWORD_HASH));
    }

    private void assertDummyComparison(String password) {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(1)).matches(eq(password), hash.capture());
        assertEquals(AuthService.DUMMY_PASSWORD_HASH, hash.getValue());
        assertTrue(hash.getValue().startsWith("$2a$10$"), "dummy hash must match encoder strength");
        assertEquals(60, hash.getValue().length());
    }
}
