package com.example.workbench.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SystemRoleBootstrap implements ApplicationRunner {

    private final AppUserRepository userRepository;
    private final java.util.Set<String> legacyAdminAccounts;

    public SystemRoleBootstrap(AppUserRepository userRepository,
                               @Value("${app.security.admin-accounts:}") String adminAccounts) {
        this.userRepository = userRepository;
        this.legacyAdminAccounts = java.util.Arrays.stream(adminAccounts.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(account -> !account.isBlank() && !"admin".equals(account))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        userRepository.findByAccount("admin").ifPresent(user -> {
            user.changeSystemRole(SystemRole.SUPER_ADMIN);
            userRepository.save(user);
        });
        for (String account : legacyAdminAccounts) {
            userRepository.findByAccount(account).ifPresent(user -> {
                if (user.getSystemRole() == SystemRole.USER) {
                    user.changeSystemRole(SystemRole.ADMIN);
                    userRepository.save(user);
                }
            });
        }
    }
}
