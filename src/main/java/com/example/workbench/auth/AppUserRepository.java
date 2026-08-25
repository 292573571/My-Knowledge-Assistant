package com.example.workbench.auth;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByAccount(String account);

    Optional<AppUser> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    List<AppUser> findAllByOrderByCreatedAtDesc();

    Optional<AppUser> findFirstBySystemRole(SystemRole systemRole);
}
