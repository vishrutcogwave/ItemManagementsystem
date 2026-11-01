package com.itemmanagement.config;

import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.PasswordPolicy;
import com.itemmanagement.service.StoreProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates the first platform administrator only when an operator explicitly
 * opts in and supplies every credential through environment variables. No
 * credential, username or fallback password exists in the source code, and an
 * existing platform administrator is never overwritten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformAdminProvisioner implements ApplicationRunner {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,40}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    @Value("${app.platform-admin.provision:false}")
    private boolean provision;

    @Value("${app.platform-admin.username:}")
    private String username;

    @Value("${app.platform-admin.password:}")
    private String password;

    @Value("${app.platform-admin.email:}")
    private String email;

    @Value("${app.platform-admin.full-name:Platform Administrator}")
    private String fullName;

    @Value("${app.platform-admin.mobile:}")
    private String mobile;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!provision) {
            return;
        }
        if (isBlank(username) || isBlank(password) || isBlank(email)) {
            throw new IllegalStateException(
                    "PLATFORM_ADMIN_USERNAME, PLATFORM_ADMIN_PASSWORD and PLATFORM_ADMIN_EMAIL "
                            + "are required when PROVISION_PLATFORM_ADMIN=true");
        }
        String cleanUsername = username.trim();
        String cleanEmail = email.trim();
        if (!USERNAME.matcher(cleanUsername).matches()) {
            throw new IllegalStateException("PLATFORM_ADMIN_USERNAME must be 3-40 letters, numbers, dot, underscore or hyphen");
        }
        if (!EMAIL.matcher(cleanEmail).matches()) {
            throw new IllegalStateException("PLATFORM_ADMIN_EMAIL must be a valid email address");
        }
        if (!userRepository.findPlatformAdmins().isEmpty()) {
            log.info("Platform administrator already exists; environment provisioning skipped");
            return;
        }
        if (userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull(cleanUsername).isPresent()
                || userRepository.findByEmailIgnoreCaseAndStoreIdIsNull(cleanEmail).isPresent()) {
            throw new IllegalStateException(
                    "A platform-level account already uses PLATFORM_ADMIN_USERNAME or PLATFORM_ADMIN_EMAIL");
        }
        passwordPolicy.validate(password, cleanUsername, cleanEmail);
        Role role = roleRepository.findByStoreIdIsNullAndCode(StoreProvisioningService.PLATFORM_ADMIN)
                .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
        userRepository.save(User.builder()
                .storeId(null)
                .username(cleanUsername)
                .email(cleanEmail)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(isBlank(fullName) ? "Platform Administrator" : fullName.trim())
                .mobile(isBlank(mobile) ? null : mobile.trim())
                .status(StoreStatus.ACTIVE)
                .mustChangePassword(true)
                .roles(Set.of(role))
                .branches(Set.of())
                .build());
        log.info("Provisioned the initial platform administrator from environment variables; "
                + "the password must be changed at first sign-in");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
