package com.daou.erdstudio.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByOidcSub(String oidcSub);

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByMcpTokenHash(String mcpTokenHash);

    long countBySuperAdminTrue();
}
