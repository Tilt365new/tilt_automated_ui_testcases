package pages.menuPages;

import org.openqa.selenium.*;
import pages.BasePage;
import pages.Individuals.IndividualsPage;
import pages.LoginPage;
import pages.teams.TeamClimatePage;
import pages.teams.TeamDetailsPage;
import pages.teams.TeamsPage;

import java.time.Duration;
import java.time.Instant;

public class DashboardPage extends BasePage{


    // Example element to confirm dashboard is loaded
    private By dashboardHeader = By.xpath("//h1[normalize-space()=\"Assessment\"]");
    private By userName = By.xpath("//div[@id='__next']//div//div//main//header//div//div//a");
    private By newAssessmentBtn = By.xpath("//button[normalize-space()='New Assessment']");
    private By individualsButton = By.xpath("//nav//button[normalize-space()='Individuals']");
    private By shopButton = By.xpath("//button[normalize-space()='Shop']");
    private By teamsButton = By.xpath("//span[normalize-space()=\"Teams\"]");


    private By myjourneyTitle = By.xpath("//h1[normalize-space()=\"My journey\"]");
    private By welcomeText = By.xpath("//h4[normalize-space()=\"Welcome to Tilt365!\"]");
    private By startTrueTiltProfile = By.xpath("//button[normalize-space()=\"Start True Tilt Profile\"]");
    private By settingsButton = By.xpath("//span[normalize-space()='Settings']");
    private By logOutButton = By.xpath("//span[normalize-space()=\"Logout\"]");



    public DashboardPage(WebDriver driver) {
        super(driver); // Call the constructor of BasePage
    }

    public boolean isLoaded() {
        return  waitForUrlContains("/dashboard") &&
                wait.waitForElementVisible(dashboardHeader).isDisplayed();
    }


    public boolean isUserNameDisplayed() {
        return isVisible(userName);
    }

    public String getUserName() {
        return waitForElementVisible(userName).getText();
    }

    public boolean isNewAssessmentButtonVisible() {
        return isVisible(newAssessmentBtn);
    }

    public boolean isCoreDashboardUiVisible() {
        // Pick elements that are always present for this admin user
        return isVisible(userName) && isVisible(individualsButton);
    }


    public IndividualsPage goToIndividuals() {
        WebElement individualsButtonSideBarMenu = wait.waitForElementClickable(individualsButton);
        individualsButtonSideBarMenu.click();
        return new IndividualsPage(driver);
    }

    public ShopPage goToShop() {
        wait.waitForElementClickable(shopButton).click();
        return new ShopPage(driver); // ✅ driver comes from BasePage
    }

    public TeamsPage goToTeams() {
        wait.waitForElementVisible(teamsButton).click();
        return new TeamsPage(driver);
    }


    @Override
    public DashboardPage waitUntilLoaded() {
        // Hard cap for the entire method
        final Duration MAX_WAIT = Duration.ofSeconds(180);
        final Instant deadline = Instant.now().plus(MAX_WAIT);

        wait.waitForDocumentReady();
        wait.waitForLoadersToDisappear();

        By[] possibleDashboardMarkers = new By[] {
                userName,           // ALWAYS present for any logged-in user
                newAssessmentBtn,   // Appears for new OR existing users
                myjourneyTitle,     // Appears when at least 1 assessment exists
                welcomeText,        // Appears for brand new users
                startTrueTiltProfile// CTA for brand new users
        };

        boolean anyVisible = false;

        for (By locator : possibleDashboardMarkers) {
            // How much time we still have before hitting the 180s cap?
            Duration remaining = Duration.between(Instant.now(), deadline);

            if (remaining.isZero() || remaining.isNegative()) {
                break; // hard cap reached
            }

            try {
                // Use the remaining time for this particular locator
                wait.waitForElementVisible(locator, remaining);
                anyVisible = true;
                break; // any marker visible → dashboard loaded
            } catch (TimeoutException | NoSuchElementException ignored) {
                // Try next locator within whatever time is still left
            }
        }

        if (!anyVisible) {
            throw new TimeoutException(
                    "❌ Dashboard did not load within 180 seconds — no known markers became visible."
            );
        }

        return this;
    }



    public ResourcesPage goToResources() {
        // adjust locator to your existing side-nav selector
        By resourcesNav = By.xpath("//span[normalize-space()=\"Resources\"]");
        safeClick(resourcesNav);
        ResourcesPage resourcesPage = new ResourcesPage(driver);
        return resourcesPage;
    }


    public SettingsPage goToSettings() {
        wait.waitForElementClickable(settingsButton).click();
        SettingsPage settingsPage = new SettingsPage(driver);
        return settingsPage.waitUntilLoaded();
    }

    public LoginPage logout() {
        wait.waitForElementClickable(logOutButton).click();
        return new LoginPage(driver);
    }



    /**
     * Convenience helper for tests:
     *  - Clicks the left-nav "Teams" entry.
     *  - Waits for TeamsPage to load.
     *  - Asks TeamsPage to open the Team Climate / Analytics view
     *    for the team matching the given path/name.
     *
     * Accepts:
     *   "Org B / Validation Merge Test / Analytics"
     *   "Org B / Validation Merge Test"
     *   "Validation Merge Test"
     */

    public TeamClimatePage openTeamByName(String teamPath) {
        WebElement teamsLink = waitForElementClickable(teamsButton);
        teamsLink.click();

        TeamsPage teamsPage = new TeamsPage(driver).waitUntilLoaded();
        return teamsPage.openTeamClimateDetails(teamPath);
    }


    public DashboardPage open(String baseUrl) {
        driver.navigate().to(baseUrl + "/dashboard");
        waitUntilLoaded();
        return this;
    }


}
