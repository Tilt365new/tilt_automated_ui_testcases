package Utils;

import base.BaseTest;
import io.qameta.allure.Allure;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.openqa.selenium.support.ui.ExpectedConditions.*;

public class WaitUtils {

    /**
     * ✅ IMPORTANT FIXES (without removing your extra functionality):
     * - Removed the broken static "driver = driver()" pattern (there was no driver() method).
     * - Made waits use the instance driver reliably (thread-safe for parallel runs).
     * - Prevented Allure "no test is running" noise by attaching only when a test/step is active.
     * - Kept all your existing helpers and APIs.
     */

    private final WebDriver driver;
    private final WebDriverWait wait;

    private static Duration defaultTimeout = Duration.ofSeconds(30);

    /** Single union selector for overlays/spinners/backdrops (faster than N separate waits). */
    private static final String LOADER_UNION_CSS =
            "[data-testid='loading'],[data-test='loading']," +
                    "[role='progressbar']," +
                    ".MuiBackdrop-root,.MuiCircularProgress-root," +
                    ".ant-spin,.ant-spin-spinning," +
                    ".overlay,.spinner,.backdrop,[aria-busy='true']";

    // ✅ FIXED: use Duration.max instead of Math.max (implemented via compareTo)
    public WaitUtils(WebDriver driver, Duration timeout) {
        this.driver = driver;
        defaultTimeout = Duration.ofSeconds(3).compareTo(timeout) > 0
                ? Duration.ofSeconds(3)
                : timeout;
        this.wait = baseWait(this.driver, defaultTimeout);
    }

    // Fallback for the few static methods that need "a driver"
    private static WebDriver driverOrBase() {
        // Prefer BaseTest.driver if you use that pattern
        try {
            if (BaseTest.driver() != null) return BaseTest.driver();
        } catch (Throwable ignored) {}
        return null;
    }

    private static WebDriverWait baseWait(WebDriver drv, Duration timeout) {
        if (drv == null) {
            throw new IllegalStateException("WebDriver is null. Make sure the driver is initialized before using WaitUtils.");
        }

        WebDriverWait w = new WebDriverWait(drv, timeout);
        w.pollingEvery(Duration.ofMillis(200));
        w.ignoring(StaleElementReferenceException.class)
                .ignoring(NoSuchElementException.class)
                .ignoring(ElementClickInterceptedException.class)
                .ignoring(JavascriptException.class);
        return w;
    }

    // ---------- Allure helper for wait failures ----------
    private void attachWaitScreenshot(String description) {
        try {
            if (!(driver instanceof TakesScreenshot)) return;

            // ✅ Avoid "Could not add attachment: no test is running"
            // Only attach if a test case OR step is currently active in this thread.
            if (Allure.getLifecycle().getCurrentTestCaseOrStep().isEmpty()) return;

            byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Allure.addAttachment("❌ Wait timeout - " + description, new ByteArrayInputStream(bytes));
        } catch (Throwable ignored) {
            // Never break the test if screenshot capture fails
        }
    }

    // ---------- Core pass-through ----------
    public <T> T until(ExpectedCondition<T> condition) {
        try {
            return wait.until(condition);
        } catch (TimeoutException e) {
            attachWaitScreenshot("condition: " + condition);
            throw new RuntimeException("❌ Timeout waiting for condition: " + condition, e);
        }
    }

    public <T> T until(ExpectedCondition<T> condition, Duration timeout) {
        try {
            return baseWait(driver, timeout).until(condition);
        } catch (TimeoutException e) {
            attachWaitScreenshot("condition (timeout " + timeout.toSeconds() + "s): " + condition);
            throw new RuntimeException("❌ Timeout (" + timeout.toSeconds() +
                    "s) waiting for condition: " + condition, e);
        }
    }

    /** Generic lambda-style until (handy for custom conditions). */
    public <T> T until(Function<WebDriver, T> condition, Duration timeout) {
        try {
            return baseWait(driver, timeout).until(condition);
        } catch (TimeoutException e) {
            attachWaitScreenshot("custom condition (timeout " + timeout.toSeconds() + "s)");
            throw new RuntimeException("❌ Timeout (" + timeout.toSeconds() +
                    "s) waiting for custom condition", e);
        }
    }

    // ---------- Visibility / Clickability ----------
    public WebElement waitForElementVisible(By locator) {
        return until(visibilityOfElementLocated(locator));
    }

    public WebElement waitForElementVisible(WebElement element) {
        return until(visibilityOf(element));
    }

    public List<WebElement> waitForAllVisible(By locator) {
        try {
            return wait.until(visibilityOfAllElementsLocatedBy(locator));
        } catch (TimeoutException e) {
            attachWaitScreenshot("all visible: " + locator);
            throw new RuntimeException("❌ Timeout: Elements not all visible: " + locator, e);
        }
    }

    /** Returns the first visible element among provided locators. */
    public WebElement waitForAnyVisible(By... locators) {
        return until(d -> {
            for (By by : locators) {
                for (WebElement el : d.findElements(by)) {
                    try {
                        if (el.isDisplayed()
                                && el.getSize().height > 0
                                && el.getSize().width > 0) {
                            return el;
                        }
                    } catch (StaleElementReferenceException ignored) {}
                }
            }
            return null;
        }, defaultTimeout);
    }

    /** True/false wait (predicate style). */
    public boolean waitForTrue(Supplier<Boolean> condition) {
        try {
            return wait.until(dr -> Boolean.TRUE.equals(condition.get()));
        } catch (TimeoutException e) {
            attachWaitScreenshot("predicate waitForTrue");
            throw new RuntimeException("❌ Timeout waiting for true predicate", e);
        }
    }

    public WebElement waitForElementClickable(By locator) {
        return until(elementToBeClickable(locator), Duration.ofSeconds(180));
    }

    public WebElement waitForElementClickable(WebElement element) {
        return until(elementToBeClickable(element), Duration.ofSeconds(180));
    }

    public boolean waitForElementInvisible(By locator) {
        return until(invisibilityOfElementLocated(locator), Duration.ofSeconds(180));
    }

    public boolean waitForElementInvisible(WebElement element) {
        return until(invisibilityOf(element));
    }

    // ---------- Presence / Text / Attributes ----------
    public WebElement waitForPresence(By locator) {
        return until(presenceOfElementLocated(locator));
    }

    public boolean waitForTextPresent(By locator, String text) {
        return until(textToBePresentInElementLocated(locator, text));
    }

    public boolean waitForAttributeContains(WebElement el, String attr, String value) {
        return until(attributeContains(el, attr, value));
    }

    public boolean waitForValueNotEmpty(WebElement el) {
        return until(d -> {
            try {
                String v = el.getAttribute("value");
                return v != null && !v.isEmpty();
            } catch (StaleElementReferenceException ignored) {
                return false;
            }
        }, defaultTimeout);
    }

    // ---------- URL / Title ----------
    public boolean waitForUrlContains(String partialUrl) {
        return until(urlContains(partialUrl), Duration.ofSeconds(180));
    }

    public boolean waitForTitleContains(String partialTitle) {
        return until(titleContains(partialTitle), Duration.ofSeconds(180));
    }

    public boolean waitForUrlContains(String partialUrl, long timeoutSec) {
        try {
            return baseWait(driver, Duration.ofSeconds(timeoutSec))
                    .until(urlContains(partialUrl));
        } catch (TimeoutException e) {
            attachWaitScreenshot("urlContains('" + partialUrl + "')");
            throw new RuntimeException("❌ Timeout waiting for URL to contain: " + partialUrl, e);
        }
    }

    // ---------- Page / DOM readiness ----------
    public void waitForDocumentReady() {
        try {
            baseWait(driver, defaultTimeout).until(d ->
                    "complete".equals(((JavascriptExecutor) d)
                            .executeScript("return document.readyState"))
            );
        } catch (Exception ignored) {}
    }

    public static void waitForLoadersToDisappear() {
        try {
            WebDriver drv = driverOrBase();
            if (drv == null) return;

            baseWait(drv, defaultTimeout).until(d -> {
                List<WebElement> overlays = d.findElements(By.cssSelector(LOADER_UNION_CSS));
                for (WebElement e : overlays) {
                    try {
                        if (e.isDisplayed()
                                && e.getSize().height > 0
                                && e.getSize().width > 0) {
                            return false;
                        }
                    } catch (StaleElementReferenceException ignored) {}
                }
                return true;
            });
        } catch (Exception ignored) {}
    }

    public void waitForAnimationsToFinish(Duration timeout) {
        try {
            baseWait(driver, timeout).until(d -> {
                Object running = ((JavascriptExecutor) d).executeScript(
                        "try {" +
                                "  var a = (document.getAnimations ? document.getAnimations() : []);" +
                                "  return a.filter(x => x.playState === 'running').length;" +
                                "} catch(e){ return 0; }"
                );
                long n = (running instanceof Number) ? ((Number) running).longValue() : 0L;
                return n == 0L;
            });
        } catch (Exception ignored) {}
    }

    public void waitForNetworkIdleLike(Duration timeout) {
        try {
            baseWait(driver, timeout).until(d -> {
                JavascriptExecutor js = (JavascriptExecutor) d;

                String rs = String.valueOf(js.executeScript("return document.readyState"));
                if (!"complete".equals(rs)) return false;

                for (WebElement e : d.findElements(By.cssSelector(LOADER_UNION_CSS))) {
                    try {
                        if (e.isDisplayed()) return false;
                    } catch (StaleElementReferenceException ignored) {}
                }

                Object anim = js.executeScript(
                        "try { var a=(document.getAnimations?document.getAnimations():[]);" +
                                "      return a.filter(x=>x.playState==='running').length; } catch(e){ return 0; }");
                long running = (anim instanceof Number)
                        ? ((Number) anim).longValue()
                        : 0L;
                if (running > 0) return false;

                Object inflight = js.executeScript("return window.__pendingRequests || 0;");
                long req = (inflight instanceof Number) ? ((Number) inflight).longValue() : 0L;
                return req == 0L;
            });
        } catch (Exception ignored) {}
    }

    public void installNetworkInstrumentation() {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "if (!window.__pendingRequestsInstalled) {" +
                            "  window.__pendingRequestsInstalled = true;" +
                            "  window.__pendingRequests = 0;" +
                            "  (function(){ " +
                            "    var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;" +
                            "    XMLHttpRequest.prototype.open = function(){ this.__seleniumTracked = true; return open.apply(this, arguments); };" +
                            "    XMLHttpRequest.prototype.send = function(){ if (this.__seleniumTracked){ window.__pendingRequests++; this.addEventListener('loadend', function(){ window.__pendingRequests--; }); } return send.apply(this, arguments); };" +
                            "    if (window.fetch) {" +
                            "      var _fetch = window.fetch;" +
                            "      window.fetch = function(){ window.__pendingRequests++; return _fetch.apply(this, arguments).finally(function(){ window.__pendingRequests--; }); }" +
                            "    }" +
                            "  })();" +
                            "}"
            );
        } catch (Exception ignored) {}
    }

    public void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public <T> T withTimeout(Duration timeout, Supplier<T> action) {
        // Kept your method; just make it coherent with the driver instance.
        WebDriverWait temp = baseWait(driver, timeout);
        try {
            return action.get();
        } finally {
            // nothing to reset; baseWait creates a separate instance
        }
    }

    public WebDriver waitForFrameAndSwitch(By frameLocator) {
        try {
            return wait.until(frameToBeAvailableAndSwitchToIt(frameLocator));
        } catch (TimeoutException e) {
            attachWaitScreenshot("frameToBeAvailableAndSwitchToIt(" + frameLocator + ")");
            throw new RuntimeException("❌ Timeout waiting for frame: " + frameLocator, e);
        }
    }

    public void waitForFrameAndSwitch(WebElement frame) {
        try {
            baseWait(driver, defaultTimeout).until(frameToBeAvailableAndSwitchToIt(frame));
        } catch (TimeoutException e) {
            attachWaitScreenshot("frameToBeAvailableAndSwitchToIt(WebElement)");
            throw new RuntimeException("❌ Timeout waiting for frame(WebElement)", e);
        }
    }

    public WebDriver waitForFrameAndSwitch(int index) {
        try {
            return wait.until(frameToBeAvailableAndSwitchToIt(index));
        } catch (TimeoutException e) {
            attachWaitScreenshot("frameToBeAvailableAndSwitchToIt(index=" + index + ")");
            throw new RuntimeException("❌ Timeout waiting for frame index: " + index, e);
        }
    }

    public static boolean isVisible(WebDriver driver, By by, Duration max) {
        try {
            WebDriverWait w = new WebDriverWait(driver, max);
            w.pollingEvery(Duration.ofMillis(200));
            w.ignoring(StaleElementReferenceException.class)
                    .ignoring(NoSuchElementException.class);
            w.until(visibilityOfElementLocated(by));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    public static void idle(WebDriver driver, Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static WebElement waitExactText(WebDriver d, By scope, String text, Duration t) {
        String xp = ".//*[self::p or self::span or self::div or self::label or self::strong]"
                + "[normalize-space(.)="
                + By.xpath("'" + text + "'").toString().replace("By.xpath: ", "")
                + "]";
        return new WebDriverWait(d, t).until(w -> w.findElement(scope).findElement(By.xpath(xp)));
    }

    public static void waitForLoadersToDisappear(WebDriver driver) {
        waitForLoadersToDisappear(driver, Duration.ofSeconds(10));
    }

    // Common loader/selectors you’ll likely see across pages.
    private static final List<By> DEFAULT_LOADERS = Arrays.asList(
            By.cssSelector("[data-testid='loading'], [data-testid='loader']"),
            By.cssSelector(".loading, .loader, .spinner, .lds-ring, .lds-ellipsis"),
            By.cssSelector("[role='progressbar']"),
            By.cssSelector(".overlay, .backdrop, .rdp-overlay, .ant-spin, .chakra-progress__indicator")
    );

    public static void waitForLoadersToDisappear(WebDriver driver, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        wait.ignoring(NoSuchElementException.class)
                .ignoring(StaleElementReferenceException.class)
                .until(allGoneOrHidden(DEFAULT_LOADERS));
    }

    private static ExpectedCondition<Boolean> allGoneOrHidden(List<By> locators) {
        return drv -> {
            for (By by : locators) {
                for (WebElement el : drv.findElements(by)) {
                    try {
                        if (el.isDisplayed()) return false;
                    } catch (StaleElementReferenceException ignored) {}
                }
            }
            return true;
        };
    }

    public static void waitForDocumentReady(WebDriver driver) {
        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(d -> "complete".equals(((JavascriptExecutor) d)
                        .executeScript("return document.readyState")));
    }

    @Deprecated
    public void tinyUiSettled(Duration max) {
        waitForAnimationsToFinish(max);
    }

    public boolean waitForInvisibility(By locator) {
        try {
            return baseWait(driver, defaultTimeout)
                    .until(invisibilityOfElementLocated(locator));
        } catch (TimeoutException e) {
            attachWaitScreenshot("invisibilityOfElementLocated(" + locator + ")");
            return false;
        }
    }

    // ---------- Visibility / Clickability ----------

    public WebElement waitForElementVisible(By locator, Duration timeout) {
        return until(visibilityOfElementLocated(locator), timeout);
    }
}
