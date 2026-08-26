package com.cognizant.revintel.config;

import com.cognizant.revintel.repository.ClientRepository;
import com.cognizant.revintel.repository.DataCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Decides which dataset the app is running on.
 *
 * <p>{@code spring.sql.init.data-locations} points at the generated
 * {@code db/generated/data.sql} with an {@code optional:} prefix, so a missing generated file is
 * not a startup failure. Once initialisation has run, this component checks whether any rows
 * actually landed and, if not, populates the small hand-written fallback seed so the UI is never
 * blank -- with a loud warning telling the developer to run the generator.
 */
@Component
public class DataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataBootstrap.class);
    private static final String FALLBACK = "db/data-fallback.sql";

    private final DataSource dataSource;
    private final ClientRepository clients;
    private final DataCatalogRepository catalog;

    private volatile boolean usingFallback;

    public DataBootstrap(DataSource dataSource, ClientRepository clients, DataCatalogRepository catalog) {
        this.dataSource = dataSource;
        this.clients = clients;
        this.catalog = catalog;
    }

    /** True when the app is running on the hand-written seed rather than the generated dataset. */
    public boolean isUsingFallbackDataset() {
        return usingFallback;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (clients.count() > 0) {
            usingFallback = false;
            return;
        }

        usingFallback = true;
        log.warn("""

                ============================================================================
                No generated dataset found (classpath:db/generated/data.sql was missing or
                empty). Loading the minimal fallback seed from {} instead.

                To get the full synthetic dataset, run:
                    python data-tools/build_dataset.py
                then restart the backend.
                ============================================================================
                """, FALLBACK);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(FALLBACK));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logCatalog() {
        log.info("Dataset loaded ({}): {} rows across {} tables",
                usingFallback ? "fallback seed" : "generated", catalog.totalRows(), catalog.catalog().size());
    }
}
