package com.itemmanagement.repository;

import com.itemmanagement.entity.PlatformSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, Integer> {
}
