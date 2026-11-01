package com.shiparrival.repository;

import com.shiparrival.domain.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.shiparrival.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsernameForUpdate(@Param("username") String username);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update AppUser u set u.activeSessionExpiresAt = :newExpiry "
            + "where u.id = :id and u.sessionEpoch = :epoch and u.enabled = true "
            + "and u.activeSessionExpiresAt > :now")
    int extendActiveSession(@Param("id") Long id,
                            @Param("epoch") long epoch,
                            @Param("now") LocalDateTime now,
                            @Param("newExpiry") LocalDateTime newExpiry);

    boolean existsByUsernameIgnoreCase(String username);

    long countByRoleAndEnabledTrue(UserRole role);
}
