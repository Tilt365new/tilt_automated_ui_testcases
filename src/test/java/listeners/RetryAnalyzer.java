package listeners;

import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RetryAnalyzer implements IRetryAnalyzer {

    /**
     * How many times to retry a failed test.
     *  -Dretry=1  (default)  → first run + 1 retry   = up to 2 executions
     *  -Dretry=0            → retries disabled       = 1 execution only
     *  You can also use -Dretry.max as an alias.
     */
    private final int max;

    // Attribute keys for other listeners (TestListener, video recorder, etc.)
    public static final String ATTR_RETRY_INDEX      = "retryIndex";       // 1..max
    public static final String ATTR_MAX_RETRY        = "maxRetryCount";
    public static final String ATTR_NEXT_RUN_NUMBER  = "nextRunNumber";    // 2..(max+1)
    public static final String ATTR_TOTAL_RUNS       = "totalRuns";        // max+1
    public static final String ATTR_RETRY_SCHEDULED  = "retryScheduled";   // true/false

    // 🔒 Legacy internal per-test retry counter key (kept for compatibility with any existing consumers)
    private static final String ATTR_RETRY_USED = "retryUsed";

    /**
     * ✅ FIX:
     * TestNG may create a fresh ITestResult on retries, so result attributes can be lost.
     * We persist retry counts in a static, thread-safe map keyed by the unique test invocation.
     */
    private static final ConcurrentMap<String, Integer> RETRY_USED_BY_KEY = new ConcurrentHashMap<>();

    // Avoid spamming logs: TestNG may instantiate RetryAnalyzer per method
    private static final AtomicBoolean PRINTED_CONFIG = new AtomicBoolean(false);

    public RetryAnalyzer() {
        this.max = resolveMaxRetries();
        if (PRINTED_CONFIG.compareAndSet(false, true)) {
            System.out.println("[RetryAnalyzer] Configured max retries = " + max);
        }
    }

    private int resolveMaxRetries() {
        String raw = System.getProperty("retry",
                System.getProperty("retry.max", "1"));

        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                System.out.println("[RetryAnalyzer] Negative retry value " + value + " → using 0.");
                return 0;
            }
            return value;
        } catch (Exception e) {
            System.out.println("[RetryAnalyzer] Invalid retry value '" + raw + "', defaulting to 1.");
            return 1;
        }
    }

    @Override
    public boolean retry(ITestResult result) {

        // ---- Build a stable key for the same test invocation across retries ----
        // Qualified name + parameters is usually enough; include instance identity to avoid collisions
        // when the same test method runs concurrently in different instances.
        String key = buildKey(result);

        // ✅ Persist retry count outside ITestResult (thread-safe)
        int used = RETRY_USED_BY_KEY.merge(key, 1, Integer::sum); // 1..N

        // Keep the old attribute too (best-effort; useful for logs/compat)
        result.setAttribute(ATTR_RETRY_USED, used);

        if (used <= max) {
            int retryIndex = used;            // 1..max
            int totalRuns  = max + 1;         // first run + retries
            int nextRun    = retryIndex + 1;  // 2..(max+1)

            // Preserve ALL your metadata
            result.setAttribute(ATTR_RETRY_INDEX, retryIndex);
            result.setAttribute(ATTR_MAX_RETRY, max);
            result.setAttribute(ATTR_NEXT_RUN_NUMBER, nextRun);
            result.setAttribute(ATTR_TOTAL_RUNS, totalRuns);
            result.setAttribute(ATTR_RETRY_SCHEDULED, Boolean.TRUE);

            System.out.printf(
                    "[RetryAnalyzer] Scheduling retry %d/%d → next run %d/%d for test %s%n",
                    retryIndex,
                    max,
                    nextRun,
                    totalRuns,
                    result.getName()
            );

            return true; // ✅ schedule retry
        }

        // No more retries
        result.setAttribute(ATTR_RETRY_SCHEDULED, Boolean.FALSE);

        System.out.printf(
                "[RetryAnalyzer] No more retries left for test %s (max=%d)%n",
                result.getName(),
                max
        );

        // Optional cleanup: once we're done retrying, drop the counter to avoid map growth.
        // (Only safe when the test won't be invoked again with the same key in the same JVM.)
        RETRY_USED_BY_KEY.remove(key);

        return false;
    }

    private static String buildKey(ITestResult result) {
        String qName = result.getMethod().getQualifiedName(); // class + method
        Object[] params = result.getParameters();

        // Stable identity per test instance (important for parallel runs)
        int instanceId = System.identityHashCode(result.getInstance());

        return qName
                + "|instance=" + instanceId
                + "|params=" + Arrays.deepToString(params != null ? params : new Object[0]);
    }

    /**
     * ✅ Helper for listeners/logs:
     * attempt = 1 on first run, 2 on first retry, etc.
     * This is more reliable than TestNG invocation counters.
     */
    public static int getAttemptNumber(ITestResult result) {
        try {
            String key = buildKey(result);
            Integer used = RETRY_USED_BY_KEY.get(key); // increments only when a retry is scheduled
            int retriesUsedSoFar = (used == null ? 0 : used);
            return retriesUsedSoFar + 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /**
     * Optional helper if you want to clear between suites/runs.
     * Call from @BeforeSuite / @AfterSuite if desired.
     */
    public static void resetAll() {
        RETRY_USED_BY_KEY.clear();
        PRINTED_CONFIG.set(false);
    }
}
