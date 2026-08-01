package com.hawkins.gallery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The only Java entry point for the complete Gallery application.
 *
 * <p>All Maven feature modules use packages below {@code com.hawkins.gallery}.
 * The explicit entity and repository scans make the assembly intent clear and
 * protect discovery if classes are moved between modules later.</p>
 */
@EnableCaching
@EnableScheduling
@EntityScan(basePackages = "com.hawkins.gallery")
@EnableJpaRepositories(basePackages = "com.hawkins.gallery")
@SpringBootApplication(scanBasePackages = "com.hawkins.gallery")
public class GalleryApplication {

    public static void main(String[] args) {
        SpringApplication.run(GalleryApplication.class, args);
    }
}
