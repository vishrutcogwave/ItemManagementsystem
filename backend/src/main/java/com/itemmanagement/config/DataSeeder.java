package com.itemmanagement.config;

import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataSeeder demoDataSeeder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull("platform").isEmpty()) {
            Role role = roleRepository.findByStoreIdIsNullAndCode("PLATFORM_ADMIN")
                    .orElseThrow(() -> new IllegalStateException("PLATFORM_ADMIN role is missing"));
            userRepository.save(User.builder()
                    .storeId(null)
                    .username("platform")
                    .email("platform@ims.local")
                    .passwordHash(passwordEncoder.encode("Admin@123"))
                    .fullName("Platform Administrator")
                    .mobile("9000000000")
                    .status(StoreStatus.ACTIVE)
                    .roles(Set.of(role))
                    .branches(Set.of())
                    .build());
            log.info("Seeded platform administrator (username: platform)");
        }
        demoDataSeeder.seed();
    }
}
