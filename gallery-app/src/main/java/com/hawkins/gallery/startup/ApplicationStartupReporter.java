package com.hawkins.gallery.startup;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Logs one concise confirmation that the assembled application is ready. */
@Component
public class ApplicationStartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStartupReporter.class);

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final DataSource dataSource;

    public ApplicationStartupReporter(
            ApplicationContext applicationContext,
            Environment environment,
            DataSource dataSource) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            log.info("Database connected: {} {}", connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getDatabaseProductVersion());
        }

        String port = environment.getProperty("server.port", "8080");
        log.info("Gallery modules loaded; {} Spring beans are active", applicationContext.getBeanDefinitionCount());
        log.info("Gallery ready at http://localhost:{}/", port);
        log.info("Review workspace at http://localhost:{}/review", port);
        log.info("Health endpoint at http://localhost:{}/actuator/health", port);
    }
}
