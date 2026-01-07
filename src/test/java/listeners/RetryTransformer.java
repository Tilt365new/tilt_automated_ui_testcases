package listeners;

import org.testng.IAnnotationTransformer;
import org.testng.IRetryAnalyzer;
import org.testng.annotations.ITestAnnotation;
import org.testng.internal.annotations.DisabledRetryAnalyzer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public class RetryTransformer implements IAnnotationTransformer {

    // Avoid noisy logging (transform() is called a LOT)
    private static final AtomicBoolean PRINTED_HEADER = new AtomicBoolean(false);

    @Override
    @SuppressWarnings("rawtypes")   // TestNG uses raw types here
    public void transform(ITestAnnotation annotation,
                          Class testClass,
                          Constructor testConstructor,
                          Method testMethod) {

        final int retryMax = resolveRetryMax();
        final Class<? extends IRetryAnalyzer> existing = annotation.getRetryAnalyzerClass();

        final String methodName =
                (testMethod != null
                        ? testMethod.getDeclaringClass().getSimpleName() + "." + testMethod.getName()
                        : (testClass != null ? testClass.getSimpleName() + ".<unknown>" : "<no-method>"));

        if (PRINTED_HEADER.compareAndSet(false, true)) {
            System.out.println("[RetryTransformer] Loaded (retryMax=" + retryMax + ")");
        }

        // --- Case 1: retries globally disabled --------------------------------
        if (retryMax <= 0) {
            // If something else already set a custom analyzer, keep it.
            if (existing != null && existing != IRetryAnalyzer.class) {
                System.out.println("[RetryTransformer] retry=0 → keeping existing analyzer "
                        + existing.getSimpleName() + " for " + methodName);
            } else {
                System.out.println("[RetryTransformer] retry=0 → no retry analyzer for " + methodName);
            }
            return;
        }

        // --- Case 2: test already has a *custom* analyzer ---------------------
        // Respect any analyzer that is not default, not our RetryAnalyzer,
        // and not DisabledRetryAnalyzer.
        if (existing != null
                && existing != IRetryAnalyzer.class
                && existing != RetryAnalyzer.class
                && existing != DisabledRetryAnalyzer.class) {

            System.out.println("[RetryTransformer] " + methodName
                    + " already defines custom retryAnalyzer → keeping "
                    + existing.getSimpleName());
            return;
        }

        // --- Case 3: no analyzer, default analyzer, or DisabledRetryAnalyzer --
        // We apply our RetryAnalyzer so retries (and video on retry) are enabled.
        annotation.setRetryAnalyzer(RetryAnalyzer.class);

        System.out.println("[RetryTransformer] Applied RetryAnalyzer → " + methodName
                + " (retryMax=" + retryMax + ")");
    }

    private int resolveRetryMax() {
        // Keep backward compatibility: -Dretry.max also allowed
        String raw = System.getProperty("retry",
                System.getProperty("retry.max", "1"));

        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                System.out.println("[RetryTransformer] Negative retry='" + raw + "', using 0 (no retries).");
                return 0;
            }
            return value;
        } catch (NumberFormatException e) {
            System.out.println("[RetryTransformer] Invalid retry='" + raw + "', defaulting to 1");
            return 1;
        }
    }
}
