package com.example.workbench.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByAccount(String account);

    boolean existsByPublicId(String publicId);
}
