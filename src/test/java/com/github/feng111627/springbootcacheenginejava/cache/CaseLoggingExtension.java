package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the business scenario each test covers and its result, so a test run
 * leaves an auditable line per case in the build logs.
 */
public class CaseLoggingExtension
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger("CACHE-TESTS");

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("[BEGIN] {} :: {}",
                context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Throwable error = context.getExecutionException().orElse(null);
        if (error == null) {
            log.info("[PASS]  {} :: {}",
                    context.getRequiredTestClass().getSimpleName(),
                    context.getDisplayName());
        } else {
            log.warn("[FAIL]  {} :: {} -> {}",
                    context.getRequiredTestClass().getSimpleName(),
                    context.getDisplayName(),
                    error.toString());
        }
    }
}
