package base;

import Utils.Config;
import Utils.MailSlurpUtils;
import Utils.StripeCheckoutHelper;

import com.mailslurp.models.InboxDto;

import io.qameta.allure.Allure;

import listeners.RetryAnalyzer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.annotations.*;

import pages.Individuals.IndividualsPage;
import pages.LoginPage;
import pages.Shop.AssessmentEntryPage;
import pages.Shop.OrderPreviewPage;
import pages.Shop.PurchaseRecipientSelectionPage;
import pages.Shop.Stripe.StripeCheckoutPage;
import pages.menuPages.DashboardPage;
import pages.menuPages.ShopPage;
import pages.teams.TeamDetailsPage;
import pages.teams.TeamsPage;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;   // ✅ NEW

import static Utils.Config.joinUrl;
import static io.qameta.allure.Allure.step;
import static org.testng.Assert.assertTrue;
import static tests.teams.TeamAssessmentPurchaseAndAssignment.extractSessionIdFromUrl;

public class BaseTest {

    public static final Logger logger = LogManager.getLogger(BaseTest.class);

    /** Suite-scoped inbox prepared once. */
    protected static volatile InboxDto fixedInbox;

    private static final ThreadLocal<Long> START = new ThreadLocal<>();

    // ✅ NEW: simple concurrency counters to see how many tests really run in parallel
    private static final AtomicInteger CURRENT_CONCURRENCY = new AtomicInteger(0);
    private static final AtomicInteger MAX_CONCURRENCY = new AtomicInteger(0);

    // =====================================================================
    // ALLURE SUITE HIERARCHY
    // =====================================================================
    @BeforeMethod(alwaysRun = true)
    public void applyAllureSuiteHierarchy(Method method) {

    }

    // =====================================================================
    // SUITE INITIALIZATION (MailSlurp)
    // =====================================================================

    @BeforeSuite(alwaysRun = true)
    public void mailSlurpSuiteInit() {

        // ✅ Make sure common config is visible to listeners (Allure, etc.)
        Config.syncToSystemProperties();

        // Ensure mailslurp.debug is visible as a system prop for MailSlurpUtils
        String msDebug = Config.getAny("mailslurp.debug", "MAILSLURP_DEBUG");
        System.setProperty("mailslurp.debug", msDebug == null ? "true" : msDebug);

        // Small env summary (helps a LOT when reading Jenkins logs)
        String envName = Config.getEnvironmentName();
        String baseUrl = null;
        try {
            baseUrl = Config.getBaseUrl();
        } catch (Exception ignore) {
            baseUrl = "(unset)";
        }
        String msBasePath = Optional.ofNullable(
                Config.getAny("mailslurp.basePath", "MAILSLURP_BASE_PATH")
        ).orElse("https://api.mailslurp.com");

        logger.info("[MailSlurp][Suite] env={} | BASE_URL={} | MailSlurp.basePath={}",
                envName, baseUrl, msBasePath);

        // If suite explicitly says "email not required" and not forced, skip
        if (!isEmailRequiredForSuite() && !isMailSlurpForceOn()) {
            logger.info("[MailSlurp][Suite] Email not required → skipping inbox init.");
            fixedInbox = null;
            return;
        }

        try {
            final boolean allowCreate = Config.getMailSlurpAllowCreate();

            // Legacy single fixed inbox ID
            String singleFixedRaw = Config.getMailSlurpFixedInboxId();
            String singleFixed = (singleFixedRaw == null || singleFixedRaw.isBlank())
                    ? null
                    : singleFixedRaw.trim();
            boolean hasSingleFixed = singleFixed != null;

            // NEW: any numbered pool inbox MAILSLURP_INBOX_ID_1..10
            boolean hasPoolFixed = false;
            int poolMax = 10; // must match MailSlurpUtils.resolveApiKeyFromPoolOrNull()
            for (int i = 1; i <= poolMax; i++) {
                String id = Config.getMailSlurpInboxIdByNumber(i);
                if (id != null && !id.isBlank()) {
                    hasPoolFixed = true;
                    break;
                }
            }

            boolean fixedIdPresent = hasSingleFixed || hasPoolFixed;

            String idPrefix = "";
            if (singleFixed != null) {
                idPrefix = singleFixed.substring(0, Math.min(8, singleFixed.length()));
            }

            logger.info(
                    "[MailSlurp][Suite] allowCreate={} | singleFixedPresent={} | poolFixedPresent={}{}",
                    allowCreate,
                    hasSingleFixed,
                    hasPoolFixed,
                    singleFixed != null ? " | singleIdPrefix=" + idPrefix : ""
            );

            // Hard guard: if we know for sure there is *no* inbox and we are not allowed to create, bail out
            if (!allowCreate && !fixedIdPresent && !isMailSlurpForceOn()) {
                logger.warn("[MailSlurp][Suite] No inbox id (single or pool) and inbox creation disabled.");
                fixedInbox = null;
                return;
            }

            // From here, delegate to MailSlurpUtils, which knows about:
            // - API key pool (MAILSLURP_API_KEY_1..N)
            // - pool inboxes MAILSLURP_INBOX_ID_1..N
            // - single fixed inbox (MAILSLURP_FIXED_INBOX_ID / MAILSLURP_INBOX_ID)
            // - creation fallback (if allowed; pool failure → single-key)
            InboxDto inbox = MailSlurpUtils.resolveFixedOrCreateInbox();
            if (inbox != null) {
                fixedInbox = inbox;

                Integer poolSlot = MailSlurpUtils.getSelectedPoolNumber();
                String keyFp     = MailSlurpUtils.currentKeyFingerprint();

                if (poolSlot != null) {
                    logger.info(
                            "[MailSlurp][Suite] Resolved inbox {} <{}> via POOL slot #{} (key fp={})",
                            fixedInbox.getId(),
                            fixedInbox.getEmailAddress(),
                            poolSlot,
                            keyFp
                    );
                } else {
                    logger.info(
                            "[MailSlurp][Suite] Resolved inbox {} <{}> via SINGLE-KEY MailSlurp config (key fp={})",
                            fixedInbox.getId(),
                            fixedInbox.getEmailAddress(),
                            keyFp
                    );
                }

                // Clean it once per suite
                MailSlurpUtils.clearInboxEmails(fixedInbox.getId());
            } else {
                logger.warn("[MailSlurp][Suite] resolveFixedOrCreateInbox() returned null.");
                fixedInbox = null;
            }
        } catch (SkipException se) {
            // Skip is "expected" control flow → INFO instead of WARN
            logger.info("[MailSlurp][Suite] MailSlurp skipped: {}", se.getMessage());
            fixedInbox = null;
        } catch (Exception e) {
            logger.warn("[MailSlurp][Suite] MailSlurp unavailable: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage());
            fixedInbox = null;
        }
    }



    @BeforeSuite(alwaysRun = true)
    public void resetRetryState() {
        RetryAnalyzer.resetAll();
    }


    // =====================================================================
    // TEST INITIALIZATION
    // =====================================================================
    @BeforeMethod(alwaysRun = true)
    public void setUp(Method method) {
        final String adminEmail = Config.getAdminEmail();
        final String adminPass  = Config.getAdminPassword();

        if (adminEmail == null || adminEmail.isBlank() || adminPass == null || adminPass.isBlank()) {
            throw new SkipException("[Config] Admin credentials missing (ADMIN_EMAIL / ADMIN_PASSWORD).");
        }

        if (isEmailRequiredForTest(method)) {
            if (fixedInbox == null && !isMailSlurpForceOn()) {
                throw new SkipException("[MailSlurp][Guard] Inbox required but not available for test: " + method.getName());
            }
            logger.info("[MailSlurp][TestCtx] Using inbox: {} <{}>",
                    fixedInbox != null ? fixedInbox.getId() : "(none)",
                    fixedInbox != null ? fixedInbox.getEmailAddress() : "(unset)");
        } else {
            logger.info("[MailSlurp][TestCtx] Email not required (ui-only group).");
        }

        // ENV LOG
        final String baseUrl = Config.getBaseUrl();
        boolean headless     = Config.isHeadless();
        String chromeBin     = Config.getChromeBinaryPath();
        String wdmVersion    = Config.getAny("wdm.browserVersion", "WDM_BROWSER_VERSION");
        Duration timeout     = Config.getTimeout();

        logger.info(
                "[Env] BASE_URL={} | HEADLESS={} | CHROME_BIN={} | WDM_VERSION={} | TIMEOUT={}s",
                baseUrl,
                headless,
                chromeBin == null ? "(auto)" : chromeBin,
                wdmVersion == null ? "(auto)" : wdmVersion,
                timeout.toSeconds()
        );

        // DRIVER INIT
        DriverManager.init();
        WebDriver d = driver();

        d.manage().timeouts().implicitlyWait(Duration.ZERO);

        // Browser capabilities → attach to Allure
        attachDriverCapabilitiesToAllure(d, baseUrl, headless, chromeBin, wdmVersion, timeout, adminEmail);

        // Set explicit timeouts
        Duration pageLoad = max(Duration.ofSeconds(30), timeout.plusSeconds(20));
        Duration script   = max(Duration.ofSeconds(30), timeout);
        d.manage().timeouts().pageLoadTimeout(pageLoad);
        d.manage().timeouts().scriptTimeout(script);

        // Clean state
        clearCookiesAndStorage(d);
        normalizeViewport(d);

        // 🔥 Enable downloads in headless Chrome (CI-safe)
        Path downloadDir = getDownloadDir();
        try {
            Files.createDirectories(downloadDir);
        } catch (IOException e) {
            logger.warn("[Download] Could not create download dir: {}", downloadDir);
        }
        enableHeadlessDownloads(d, downloadDir);

        START.set(System.currentTimeMillis());

        // ✅ NEW: log thread + concurrency on test start
        int current = CURRENT_CONCURRENCY.incrementAndGet();
        MAX_CONCURRENCY.updateAndGet(prev -> Math.max(prev, current));

        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        logger.info("▶▶ [THREAD {} | {}] START test: {}.{} | concurrent={} (max={})",
                threadId,
                threadName,
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                current,
                MAX_CONCURRENCY.get()
        );

        logger.info("========== STARTING TEST: {} ==========", method.getName());
    }

    // =====================================================================
    // TEARDOWN
    // =====================================================================
    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) {
        Long st = START.get();
        double secs = st == null ? 0.0 : (System.currentTimeMillis() - st) / 1000.0;

        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();

        logger.info("◀◀ [THREAD {} | {}] END test: {} → {} ({}s)",
                threadId,
                threadName,
                result.getMethod().getMethodName(),
                statusToString(result.getStatus()),
                secs
        );

        logger.info("========== FINISHED TEST: {} ({}s) ==========",
                result.getMethod().getMethodName(), secs);

        try {
            if (DriverManager.isInitialized()) {
                WebDriver d = DriverManager.get();

                // Screenshot only on failure
                if (result.getStatus() == ITestResult.FAILURE) {
                    takeScreenshot(d, result.getMethod().getMethodName());
                }

                // Always attach browser console logs (for all tests)
                attachBrowserConsoleLogs(d, result);

                DriverManager.quit();
            }
        } catch (Throwable t) {
            logger.warn("[Teardown] Cleanup suppressed: {}", t.getMessage());
        } finally {
            // ✅ NEW: decrement concurrency counter
            int current = CURRENT_CONCURRENCY.decrementAndGet();
            logger.debug("[Concurrency] After {} → current={}", result.getMethod().getMethodName(), current);
        }
    }

    // ✅ NEW: at the end of the suite, log the max concurrency actually reached
    @AfterSuite(alwaysRun = true)
    public void logMaxConcurrency() {
        logger.info("🌐 Max concurrent tests during this run: {}", MAX_CONCURRENCY.get());
    }

    // =====================================================================
    // START FRESH SESSION
    // =====================================================================

    public static DashboardPage startFreshSession(WebDriver driver) {
        return startFreshSession(driver, 3); // default: 3 attempts for transient issues
    }

    public static DashboardPage startFreshSession(WebDriver driver, int maxAttempts) {
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }

        // Ensure we have a driver
        if (driver == null) {
            try {
                logger.info("[BaseTest] startFreshSession received null driver; initializing via DriverManager.");
                DriverManager.init();
                driver = DriverManager.get();
            } catch (Throwable t) {
                throw new SkipException(
                        "[BaseTest] Unable to initialize WebDriver in startFreshSession: " + t.getMessage(), t
                );
            }
        }

        final String baseUrl   = Config.getBaseUrl();
        final String adminUser = Config.getAdminEmail();
        final String adminPass = Config.getAdminPassword();

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new SkipException("[Config] BASE_URL missing.");
        }
        if (adminUser == null || adminUser.isBlank()) {
            throw new SkipException("[Config] ADMIN_EMAIL missing.");
        }
        if (adminPass == null || adminPass.isBlank()) {
            throw new SkipException("[Config] ADMIN_PASSWORD missing.");
        }

        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("[startFreshSession] attempt {}/{} → clean browser & login as admin", attempt, maxAttempts);

                // Clean state each attempt
                clearCookiesAndStorage(driver);

                // Navigate to base URL (app will redirect to sign-in)
                driver.get(baseUrl);

                // Do the login via LoginPage POM
                LoginPage loginPage = new LoginPage(driver);
                DashboardPage dashboard = loginPage.safeLoginAsAdmin(adminUser, adminPass, Duration.ofSeconds(30));

                if (dashboard == null) {
                    throw new IllegalStateException("DashboardPage was null after loginAsAdmin().");
                }

                logger.info("[startFreshSession] Logged in as admin and obtained DashboardPage.");
                return dashboard;
            } catch (Throwable t) {
                lastError = t;
                logger.warn("[startFreshSession] attempt {}/{} failed: {}", attempt, maxAttempts, t.toString());

                // For next attempt, re-init a clean driver
                if (attempt < maxAttempts) {
                    try {
                        DriverManager.reinit();
                        driver = DriverManager.get();
                    } catch (Throwable re) {
                        logger.warn("[startFreshSession] Failed to reinitialize WebDriver: {}", re.toString());
                        lastError = re;
                        break;
                    }
                }
            }
        }

        // If we reach here, all attempts failed
        String msg = "[startFreshSession] Could not open Dashboard after "
                + maxAttempts + " attempt(s). Last error: "
                + (lastError != null ? lastError.getMessage() : "(none)");
        throw new SkipException(msg, lastError);
    }

    public static DashboardPage startFreshSession() {
        return startFreshSession(driver());
    }

    public static InboxDto getSuiteInbox() { return fixedInbox; }

    public static InboxDto requireInboxOrSkip() {
        if (fixedInbox == null) {
            throw new SkipException("[MailSlurp] Suite inbox not available.");
        }
        return fixedInbox;
    }

    // =====================================================================
    // DRIVER HELPERS
    // =====================================================================

    public static WebDriver driver() {
        return DriverManager.get();
    }

    private static void clearCookiesAndStorage(WebDriver driver) {
        try { driver.manage().deleteAllCookies(); } catch (Exception ignored) {}
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "localStorage.clear(); sessionStorage.clear();"
            );
        } catch (Exception ignored) {}
    }

    private static void normalizeViewport(WebDriver driver) {
        try {
            // Always force a deterministic desktop viewport (works in headless too)
            driver.manage().window().setSize(new Dimension(1488, 930));
        } catch (Exception e) {
            logger.warn("[Viewport] setSize failed: {}", e.getMessage());
        }
    }

    private static void enableHeadlessDownloads(WebDriver d, Path downloadDir) {
        try {
            if (d instanceof ChromeDriver) {
                Map<String, Object> params = new HashMap<>();
                params.put("behavior", "allow");
                params.put("downloadPath", downloadDir.toAbsolutePath().toString());
                ((ChromeDriver) d).executeCdpCommand("Page.setDownloadBehavior", params);
            }
        } catch (Exception e) {
            System.out.println("[Download] CDP enable failed: " + e.getMessage());
        }
    }


    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    // =====================================================================
    // SCREENSHOT
    // =====================================================================
    private static void takeScreenshot(WebDriver driver, String methodName) {
        try {
            Path dir = Paths.get("target", "screenshots");
            Files.createDirectories(dir);
            Path file = dir.resolve(methodName + ".png");
            byte[] data = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Files.write(file, data);

            // Also attach to Allure
            Allure.addAttachment("Screenshot - " + methodName, "image/png",
                    Files.newInputStream(file), ".png");
        } catch (Exception e) {
            logger.warn("[Screenshot] Failed: {}", e.getMessage());
        }
    }

    // =====================================================================
    // INBOX FLAGS
    // =====================================================================
    private static boolean isMailSlurpForceOn() {
        String raw = Config.getAny("mailslurp.force", "MAILSLURP_FORCE");
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equals("1"));
    }

    private static boolean isEmailRequiredForSuite() {
        String raw = Config.getAny("mailslurp.required", "MAILSLURP_REQUIRED");
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw);
    }

    private static boolean isEmailRequiredForTest(Method m) {
        if (isMailSlurpForceOn()) return true;
        Test ann = m.getAnnotation(Test.class);
        if (ann == null) return true;

        for (String g : ann.groups()) {
            if ("ui-only".equalsIgnoreCase(g)) return false;
        }
        return true;
    }

    // =====================================================================
    // PDF/PNG WAITER
    // =====================================================================
    protected Path waitForNewPdf(Path dir, Instant start, Duration timeout) throws Exception {
        return waitForNewFile(dir, start, timeout, ".pdf");
    }

    protected Path waitForNewPng(Path dir, Instant start, Duration timeout) throws Exception {
        return waitForNewFile(dir, start, timeout, ".png");
    }

    protected Path waitForNewFile(Path dir, Instant start, Duration timeout, String ext) throws Exception {
        long end = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < end) {
            try (var stream = Files.list(dir)) {
                Optional<Path> newest = stream
                        .filter(p -> {
                            try {
                                return Files.isRegularFile(p)
                                        && p.getFileName().toString().toLowerCase().endsWith(ext)
                                        && Files.getLastModifiedTime(p).toMillis() >= start.toEpochMilli();
                            } catch (IOException e) { return false; }
                        })
                        .findFirst();

                if (newest.isPresent()) return newest.get();
            }
            Thread.sleep(500);
        }
        return null;
    }

    // =====================================================================
    // ALLURE CAPABILITY ATTACHMENT
    // =====================================================================
    private static void attachDriverCapabilitiesToAllure(
            WebDriver d,
            String baseUrl,
            boolean headless,
            String chromeBin,
            String wdmVersion,
            Duration timeout,
            String adminEmail
    ) {
        try {
            Capabilities caps = null;
            if (d instanceof HasCapabilities) caps = ((HasCapabilities)d).getCapabilities();

            String browserName    = caps != null ? caps.getBrowserName() : "(unknown)";
            String browserVersion = caps != null ? caps.getBrowserVersion() : "(unknown)";
            Object plStrategy     = caps != null ? caps.getCapability(CapabilityType.PAGE_LOAD_STRATEGY) : null;
            Object platformName   = caps != null ? caps.getCapability("platformName") : null;
            Object insecureCerts  = caps != null ? caps.getCapability("acceptInsecureCerts") : null;

            StringBuilder sb = new StringBuilder();
            sb.append("BASE_URL=").append(baseUrl).append('\n');
            sb.append("HEADLESS=").append(headless).append('\n');
            sb.append("CHROME_BIN=").append(chromeBin).append('\n');
            sb.append("WDM_BROWSER_VERSION=").append(wdmVersion).append('\n');
            sb.append("TIMEOUT_SECONDS=").append(timeout.toSeconds()).append('\n');
            sb.append("ADMIN_USER=").append(maskEmail(adminEmail)).append('\n');
            sb.append("BROWSER_NAME=").append(browserName).append('\n');
            sb.append("BROWSER_VERSION=").append(browserVersion).append('\n');
            sb.append("PAGE_LOAD_STRATEGY=").append(plStrategy).append('\n');
            sb.append("PLATFORM_NAME=").append(platformName).append('\n');
            sb.append("ACCEPT_INSECURE_CERTS=").append(insecureCerts).append('\n');

            Allure.addAttachment("Test Context", "text/plain", sb.toString());
        } catch (Throwable ignored) {}
    }

    protected static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(blank)";
        int at = email.indexOf('@');
        String user = at > -1 ? email.substring(0, at) : email;
        String dom  = at > -1 ? email.substring(at) : "";
        if (user.length() <= 2) return user.charAt(0) + "****" + dom;
        return user.charAt(0) + "****" + user.charAt(user.length() - 1) + dom;
    }

    // =====================================================================
    // BROWSER CONSOLE LOGS → ALLURE ATTACHMENT
    // =====================================================================
    private void attachBrowserConsoleLogs(WebDriver driver, ITestResult result) {
        if (driver == null) return;

        try {
            LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
            if (logs == null || logs.getAll().isEmpty()) {
                logger.debug("[BrowserLogs] No BROWSER console entries for test {}",
                        result.getMethod().getMethodName());
                return;
            }

            String status = statusToString(result.getStatus());

            StringBuilder builder = new StringBuilder();
            builder.append("Test: ").append(result.getMethod().getMethodName()).append('\n');
            builder.append("Status: ").append(status).append('\n');
            builder.append("Thread: ")
                    .append(Thread.currentThread().getName())
                    .append("\n\n");


            for (LogEntry entry : logs) {
                builder.append('[')
                        .append(java.time.Instant.ofEpochMilli(entry.getTimestamp()))
                        .append("] ")
                        .append(entry.getLevel())
                        .append(" - ")
                        .append(entry.getMessage())
                        .append('\n');
            }

            Allure.addAttachment(
                    "Browser Console – " + status,
                    "text/plain",
                    builder.toString()
            );

        } catch (Throwable t) {
            logger.warn("[BrowserLogs] Failed to capture browser console logs: {}", t.getMessage());
        }
    }

    private static String statusToString(int status) {
        switch (status) {
            case ITestResult.SUCCESS:
                return "PASS";
            case ITestResult.FAILURE:
                return "FAIL";
            case ITestResult.SKIP:
                return "SKIP";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    /**
     * Creates a team via the Shop flow using Stripe CLI (no UI payment).
     * - Goes through TTP Team purchase
     * - Creates a new team with teamName
     * - Adds a single base user
     * - Triggers checkout.session.completed via Stripe CLI
     * - Lands back in app, opens the newly created team details page.
     */
    protected TeamDetailsPage createTeamViaShopFlow(String teamName, String baseFirst, String baseLast, String baseEmail) throws InterruptedException {
        // 1) Login as admin
        LoginPage login = new LoginPage(driver());
        login.navigateTo();
        DashboardPage dashboard = login.login(
                Config.getAdminEmail(),
                Config.getAdminPassword()
        );
        Assert.assertTrue(dashboard.isLoaded(), "Dashboard did not load after login");

        // 2) Go to Shop → Buy TTP for Team
        ShopPage shopPage = dashboard.goToShop();
        Assert.assertTrue(shopPage.isLoaded(), "Shop page did not load");

        PurchaseRecipientSelectionPage sel = shopPage.clickBuyNowForTrueTilt();
        sel.waitUntilLoaded().selectTeam();
        AssessmentEntryPage entry = sel.clickNext().waitUntilLoaded();

        // 3) Create new team
        entry.selectCreateNewTeam();
        entry.setOrganizationName("Auto Org " + System.currentTimeMillis());
        entry.setGroupName(teamName);

        // 4) Add base member
        entry.selectManualEntry();
        entry.enterNumberOfIndividuals("1");
        entry.fillUserDetailsAtIndex(1, baseFirst, baseLast, baseEmail);
        Assert.assertTrue(entry.isProceedToPaymentEnabled(),
                "'Proceed to payment' must be enabled after filling base user.");

        // 5) Order preview
        OrderPreviewPage preview = entry.clickProceedToPayment().waitUntilLoaded();
        Assert.assertTrue(preview.isLoaded(), "Order Preview did not load");

        // 6) Stripe – get Checkout URL + Session ID
        String stripeUrl = preview.proceedToStripeAndGetCheckoutUrl();
        String sessionId = extractSessionIdFromUrl(stripeUrl); // reuse your existing helper
        Assert.assertNotNull(sessionId, "Could not parse Stripe session id from URL");
        System.out.println("[Stripe] checkoutUrl=" + stripeUrl + " | sessionId=" + sessionId);

        // 7) Stripe – fetch metadata.body + trigger checkout.session.completed via CLI
        String bodyJson = StripeCheckoutHelper.fetchCheckoutBodyFromStripe(sessionId);
        Assert.assertNotNull(bodyJson, "metadata.body not found in Checkout Session");
        System.out.println("[Stripe] metadata.body length=" + bodyJson.length());

        var trig = StripeCheckoutHelper.triggerCheckoutCompletedWithBody(bodyJson);
        System.out.println("[Stripe] Triggered eventId=" + trig.eventId +
                (trig.requestLogUrl != null ? " | requestLog=" + trig.requestLogUrl : ""));

        // 8) Back to app – order confirmation
        driver().navigate().to(joinUrl(Config.getBaseUrl(), "/dashboard/orders/confirmation"));

        // 9) Open the newly created team from Teams page
        DashboardPage dashboardPage = new DashboardPage(driver()).open(Config.getBaseUrl());
        TeamsPage teams = dashboardPage.goToTeams().waitUntilLoaded();
        teams.openTeamDetails(teamName);

        return new TeamDetailsPage(driver()).waitUntilLoaded();
    }


    protected Path getDownloadDir() {
        String custom = Config.getAny("download.dir", "DOWNLOAD_DIR");
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom).toAbsolutePath();
        }
        // fallback: target/downloads
        return Path.of("target/downloads").toAbsolutePath();
    }

}
