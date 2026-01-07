package listeners;

import base.DriverManager;
import io.qameta.allure.Allure;
import io.qameta.allure.Attachment;
import org.openqa.selenium.*;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class TestListener implements ITestListener {

    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyyMMdd_HHmmss");

    // Enable/disable final failure screenshots (in addition to step-level ones)
    private static final boolean FINAL_SCREENSHOT_ENABLED =
            Boolean.parseBoolean(System.getProperty("final.screenshot.enabled", "true"));

    // Helps prevent spammy environment.properties writes if multiple suites are initialized
    private static final AtomicBoolean ENV_WRITTEN = new AtomicBoolean(false);

    // =========================================================
    // RETRY LABELING
    // =========================================================

    private void annotateRetryLabels(ITestResult result) {
        try {
            // Prefer our "official" attributes
            Object nextRunObj   = result.getAttribute(RetryAnalyzer.ATTR_NEXT_RUN_NUMBER);
            Object totalRunsObj = result.getAttribute(RetryAnalyzer.ATTR_TOTAL_RUNS);

            int attempt;
            int maxAttempts;
            boolean flaky;

            if (nextRunObj instanceof Integer && totalRunsObj instanceof Integer) {
                // NOTE: your RetryAnalyzer sets nextRunNumber as the upcoming run number.
                // For the CURRENT run, attempt = currentInvocationCount + 1 is more accurate.
                // But we keep your metadata logic and add a safe fallback below.
                attempt     = (Integer) nextRunObj;     // may represent "next run"
                maxAttempts = (Integer) totalRunsObj;
                flaky       = attempt > 1;
            } else {
                // legacy fallback
                Object rc = result.getAttribute("retryCount");
                Object mc = result.getAttribute("maxRetryCount");
                int retryCount = (rc instanceof Integer) ? (Integer) rc : 0;
                int maxRetry   = (mc instanceof Integer) ? (Integer) mc : 0;

                attempt     = retryCount + 1;
                maxAttempts = maxRetry + 1;
                flaky       = retryCount > 0;
            }

            // ✅ Strong fallback: if attempt/maxAttempts look invalid, derive from invocation count + retry system prop.
            if (maxAttempts <= 0) {
                maxAttempts = Math.max(1, Integer.parseInt(System.getProperty("retry",
                        System.getProperty("retry.max", "0"))) + 1);
            }
            if (attempt <= 0 || attempt > maxAttempts) {
                int inv = safeInvocation(result); // 0..N
                attempt = inv + 1;               // 1..N+1
                flaky = attempt > 1;
            }

            Allure.label("attempt", attempt + "/" + maxAttempts);
            if (flaky) {
                Allure.label("flaky", "true");
            }

            Allure.addAttachment(
                    "Retry Info",
                    "text/plain",
                    "attempt=" + attempt + "/" + maxAttempts + " | flaky=" + flaky
            );

        } catch (Throwable ignored) {
            // never break tests on reporting
        }
    }

    private int safeInvocation(ITestResult result) {
        try {
            return result.getMethod().getCurrentInvocationCount(); // 0 = first run
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // =========================================================
    // CORE HOOKS
    // =========================================================

    @Override
    public void onTestStart(ITestResult result) {
        annotateRetryLabels(result);

        // === NEW: Allure suite hierarchy ===
        try {
            Allure.label("parentSuite", "Tilt – UI Automation");

            String suiteName = System.getProperty("allure.suite", "Smoke – Dev");
            Allure.suite(suiteName);

            String className  = result.getMethod().getRealClass().getSimpleName();
            String methodName = result.getMethod().getMethodName();

            Allure.label("subSuite", className);
            Allure.label("thread", Thread.currentThread().getName());
            Allure.label("testMethod", methodName);
        } catch (Throwable ignored) {
            // don't break tests on reporting
        }

        String qName   = qualifiedName(result);
        int invocation = safeInvocation(result);

        System.out.printf(
                "[TEST START] %s | invocation=%d%n",
                qName, invocation
        );
    }

    @Override
    public void onTestFailure(ITestResult result) {
        WebDriver driver = currentDriver();

        System.out.println("[FAIL] " + qualifiedName(result) +
                " (invocation=" + safeInvocation(result) + ")");

        annotateRetryLabels(result);

        if (driver == null) {
            System.out.println("[FAIL] No WebDriver instance; skipping screenshot and artifacts.");
            return;
        }

        // Local screenshot + final-state Allure screenshot (optional toggle)
        if (FINAL_SCREENSHOT_ENABLED) {
            saveScreenshotToFile(driver, result);
            attachScreenshot(driver);
        }

        // Always attach supporting artifacts
        attachUrl(driver);
        attachPageSource(driver);
        attachBrowserConsole(driver);
        attachPerformanceLogIfAvailable(driver);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        System.out.println("[PASS] " + qualifiedName(result) +
                " (invocation=" + safeInvocation(result) + ")");
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        Throwable t = result.getThrowable();
        String reason = (t != null && t.getMessage() != null)
                ? t.getMessage()
                : "(no skip message)";

        System.out.println("[SKIP] " + qualifiedName(result) +
                " (invocation=" + safeInvocation(result) + ") — " + reason);

        annotateRetryLabels(result);

        try {
            Allure.addAttachment("Skip Reason", "text/plain", reason);
        } catch (Throwable ignored) {}

        WebDriver driver = currentDriver();
        if (driver != null) {
            attachUrl(driver);

            // Helpful on skips due to retries: capture state if you want (keep default OFF to avoid noise)
            boolean screenshotOnSkip =
                    Boolean.parseBoolean(System.getProperty("skip.screenshot.enabled", "false"));
            if (screenshotOnSkip) {
                saveScreenshotToFile(driver, result);
                attachScreenshot(driver);
            }
        }
    }

    // =========================================================
    // Allure attachments
    // =========================================================

    @Attachment(value = "Current URL", type = "text/plain")
    private byte[] attachUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "(no url)".getBytes(StandardCharsets.UTF_8);
        }
    }

    // Explicit Allure attachment for final-state screenshot
    private void attachScreenshot(WebDriver driver) {
        try {
            byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Allure.addAttachment(
                    "Final state screenshot (onTestFailure)",
                    "image/png",
                    new ByteArrayInputStream(bytes),
                    "png"
            );
        } catch (Throwable ignored) {
            Allure.addAttachment(
                    "Final state screenshot (onTestFailure)",
                    "text/plain",
                    "screenshot capture failed"
            );
        }
    }

    @Attachment(value = "Page Source", type = "text/html")
    private byte[] attachPageSource(WebDriver driver) {
        try {
            return driver.getPageSource().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "<no page source>".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Attachment(value = "Browser Console", type = "text/plain")
    private byte[] attachBrowserConsole(WebDriver driver) {
        try {
            Set<String> available = driver.manage().logs().getAvailableLogTypes();
            if (!available.contains(LogType.BROWSER)) {
                return "browser log type not available".getBytes(StandardCharsets.UTF_8);
            }

            String logs = driver.manage().logs().get(LogType.BROWSER).getAll()
                    .stream()
                    .map(this::fmt)
                    .collect(Collectors.joining(System.lineSeparator()));

            return logs.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "Browser logs not available".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Attachment(value = "Performance Log (CDP)", type = "application/json")
    private byte[] attachPerformanceLogIfAvailable(WebDriver driver) {
        try {
            Set<String> available = driver.manage().logs().getAvailableLogTypes();
            if (!available.contains(LogType.PERFORMANCE)) {
                return "performance log not available".getBytes(StandardCharsets.UTF_8);
            }

            String perf = driver.manage().logs().get(LogType.PERFORMANCE).getAll()
                    .stream()
                    .map(LogEntry::getMessage)
                    .collect(Collectors.joining("\n"));

            return perf.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "performance log read failed".getBytes(StandardCharsets.UTF_8);
        }
    }

    private String fmt(LogEntry e) {
        return "[" + e.getLevel() + "] " + e.getMessage();
    }

    // =========================================================
    // UTIL
    // =========================================================

    private WebDriver currentDriver() {
        try {
            return DriverManager.peek();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String qualifiedName(ITestResult r) {
        try {
            return r.getMethod().getRealClass().getSimpleName()
                    + "."
                    + r.getMethod().getMethodName();
        } catch (Throwable ignored) {
            return "(unknown-test)";
        }
    }

    private void saveScreenshotToFile(WebDriver driver, ITestResult result) {
        try {
            Path dir = Path.of("target", "screenshots");
            Files.createDirectories(dir);

            String method = result.getMethod().getMethodName()
                    .replaceAll("[^a-zA-Z0-9_.-]", "_");

            String ts;
            synchronized (TS) {
                ts = TS.format(new Date());
            }

            // Include invocation count to avoid overwriting when retries happen quickly
            int inv = safeInvocation(result);

            Path out = dir.resolve(method + "_inv" + inv + "_" + ts + ".png");
            Files.write(out, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));

            System.out.println("[Screenshot] Saved " + out.toAbsolutePath());
        } catch (Throwable ignored) {
            System.out.println("[Screenshot] Failed to write: " + ignored.getMessage());
        }
    }

    // =========================================================
    // Allure env
    // =========================================================

    @Override
    public void onStart(ITestContext context) {
        writeEnv();
    }

    private void writeEnv() {
        // Prevent multiple writes (parallel suites / retries / different runners)
        if (!ENV_WRITTEN.compareAndSet(false, true)) {
            return;
        }

        try {
            Properties p = new Properties();
            p.setProperty("env", System.getProperty("env", "dev"));

            // keep your existing key, but also record BASE_URL if you use it elsewhere
            p.setProperty("baseUrl", System.getProperty("baseUrl", System.getProperty("BASE_URL", "")));

            p.setProperty("retry", System.getProperty("retry", System.getProperty("retry.max", "0")));
            p.setProperty("headless", System.getProperty("headless", "false"));
            p.setProperty("java.version", System.getProperty("java.version", ""));
            p.setProperty("os.name", System.getProperty("os.name", ""));
            p.setProperty("parallel", System.getProperty("parallel", "methods"));

            Path results = Path.of("target", "allure-results");
            Files.createDirectories(results);

            try (OutputStream out = Files.newOutputStream(results.resolve("environment.properties"))) {
                p.store(out, "Generated by TestListener");
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================
    // No-op overrides
    // =========================================================

    @Override public void onTestFailedButWithinSuccessPercentage(ITestResult r) {}
    @Override public void onFinish(ITestContext context) {}
}
