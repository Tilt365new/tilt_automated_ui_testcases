package listeners;

import io.qameta.allure.Allure;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

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

    // 🔒 NEW: internal per-test retry counter key
    private static final String ATTR_RETRY_USED = "retryUsed";

    public RetryAnalyzer() {
        this.max = resolveMaxRetries();
        System.out.println("[RetryAnalyzer] Configured max retries = " + max);
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

        // ✅ Per-test retry counter (thread-safe)
        Integer used = (Integer) result.getAttribute(ATTR_RETRY_USED);
        if (used == null) {
            used = 0;
        }

        if (used < max) {
            used++;
            result.setAttribute(ATTR_RETRY_USED, used);

            int retryIndex = used;           // 1..max
            int totalRuns  = max + 1;        // first run + retries
            int nextRun    = retryIndex + 1; // 2..(max+1)

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
        return false;
    }
}
