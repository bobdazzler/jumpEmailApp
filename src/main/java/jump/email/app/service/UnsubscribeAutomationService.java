package jump.email.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Service for automating unsubscribe actions using browser automation.
 * Uses Selenium WebDriver to navigate unsubscribe pages and fill forms.
 */
@Slf4j
@Service
public class UnsubscribeAutomationService {
    private final ObjectMapper objectMapper;

    public UnsubscribeAutomationService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes unsubscribe actions based on AI-generated instructions.
     * @param unsubscribeUrl The unsubscribe URL
     * @param instructionsJson JSON string with actions to perform
     * @param userEmail The user's email address (for filling forms)
     * @return true if unsubscribe was successful, false otherwise
     */
    public boolean executeUnsubscribe(String unsubscribeUrl, String instructionsJson, String userEmail) {
        WebDriver driver = null;
        try {
            // Setup Chrome in headless mode
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-software-rasterizer");
            options.addArguments("--disable-extensions");
            options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            
            // Use system Chrome binary if available (for Docker/Alpine)
            String chromeBin = System.getenv("CHROME_BIN");
            if (chromeBin != null && !chromeBin.isEmpty()) {
                options.setBinary(chromeBin);
            }
            
            driver = new ChromeDriver(options);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            
            log.info("Navigating to unsubscribe URL: {}", unsubscribeUrl);
            driver.get(unsubscribeUrl);
            
            // Wait for page to load
            Thread.sleep(2000);
            
            // Parse instructions JSON
            JsonNode instructions = objectMapper.readTree(instructionsJson);
            JsonNode actions = instructions.get("actions");
            
            if (actions == null || !actions.isArray()) {
                log.warn("Invalid instructions format, attempting simple click on common unsubscribe elements");
                return attemptSimpleUnsubscribe(driver, wait);
            }
            
            // Execute each action
            for (JsonNode action : actions) {
                String type = action.get("type").asText();
                
                switch (type.toLowerCase()) {
                    case "fill":
                        String field = action.get("field").asText();
                        String value = action.has("value") ? action.get("value").asText() : userEmail;
                        String selector = action.has("selector") ? action.get("selector").asText() : null;
                        
                        fillField(driver, wait, field, value, selector);
                        break;
                        
                    case "click":
                        String clickSelector = action.get("selector").asText();
                        clickElement(driver, wait, clickSelector);
                        break;
                        
                    case "toggle":
                    case "check":
                        String toggleSelector = action.get("selector").asText();
                        boolean checked = action.has("checked") ? action.get("checked").asBoolean() : true;
                        toggleCheckbox(driver, wait, toggleSelector, checked);
                        break;
                        
                    case "select":
                        String selectSelector = action.get("selector").asText();
                        String optionValue = action.get("value").asText();
                        selectOption(driver, wait, selectSelector, optionValue);
                        break;
                        
                    default:
                        log.warn("Unknown action type: {}", type);
                }
                
                // Small delay between actions
                Thread.sleep(500);
            }
            
            // Wait a bit for any final processing
            Thread.sleep(2000);
            
            // Check for success indicators
            String pageSource = driver.getPageSource().toLowerCase();
            boolean success = pageSource.contains("unsubscribed") || 
                            pageSource.contains("success") ||
                            pageSource.contains("removed") ||
                            pageSource.contains("confirmed");
            
            log.info("Unsubscribe attempt completed. Success indicators found: {}", success);
            return success;
            
        } catch (Exception e) {
            log.error("Error executing unsubscribe automation: {}", e.getMessage(), e);
            return false;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Error closing browser: {}", e.getMessage());
                }
            }
        }
    }

    private void fillField(WebDriver driver, WebDriverWait wait, String fieldName, String value, String selector) {
        try {
            WebElement field;
            
            if (selector != null && !selector.isEmpty()) {
                // Use specific selector
                field = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
            } else {
                // Try to find by name, id, or placeholder
                field = findFieldByNameIdOrPlaceholder(driver, fieldName);
            }
            
            if (field != null) {
                field.clear();
                field.sendKeys(value);
                log.debug("Filled field '{}' with value: {}", fieldName, value);
            } else {
                log.warn("Could not find field: {}", fieldName);
            }
        } catch (Exception e) {
            log.warn("Error filling field '{}': {}", fieldName, e.getMessage());
        }
    }

    private void clickElement(WebDriver driver, WebDriverWait wait, String selector) {
        try {
            WebElement element = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector)));
            element.click();
            log.debug("Clicked element: {}", selector);
        } catch (Exception e) {
            log.warn("Error clicking element '{}': {}", selector, e.getMessage());
        }
    }

    private void toggleCheckbox(WebDriver driver, WebDriverWait wait, String selector, boolean checked) {
        try {
            WebElement checkbox = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
            if (checkbox.isSelected() != checked) {
                checkbox.click();
            }
            log.debug("Toggled checkbox '{}' to {}", selector, checked);
        } catch (Exception e) {
            log.warn("Error toggling checkbox '{}': {}", selector, e.getMessage());
        }
    }

    private void selectOption(WebDriver driver, WebDriverWait wait, String selector, String optionValue) {
        try {
            WebElement select = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
            org.openqa.selenium.support.ui.Select dropdown = new org.openqa.selenium.support.ui.Select(select);
            dropdown.selectByValue(optionValue);
            log.debug("Selected option '{}' in '{}'", optionValue, selector);
        } catch (Exception e) {
            log.warn("Error selecting option '{}' in '{}': {}", optionValue, selector, e.getMessage());
        }
    }

    private WebElement findFieldByNameIdOrPlaceholder(WebDriver driver, String fieldName) {
        String lowerFieldName = fieldName.toLowerCase();
        
        // Try by name
        try {
            return driver.findElement(By.name(lowerFieldName));
        } catch (Exception e) {
            // Continue
        }
        
        // Try by id
        try {
            return driver.findElement(By.id(lowerFieldName));
        } catch (Exception e) {
            // Continue
        }
        
        // Try by placeholder
        try {
            List<WebElement> inputs = driver.findElements(By.tagName("input"));
            for (WebElement input : inputs) {
                String placeholder = input.getAttribute("placeholder");
                if (placeholder != null && placeholder.toLowerCase().contains(lowerFieldName)) {
                    return input;
                }
            }
        } catch (Exception e) {
            // Continue
        }
        
        return null;
    }

    /**
     * Fallback method: attempts simple unsubscribe by clicking common unsubscribe buttons/links.
     */
    private boolean attemptSimpleUnsubscribe(WebDriver driver, WebDriverWait wait) {
        try {
            // Common CSS selectors for unsubscribe buttons
            String[] cssSelectors = {
                "a[href*='unsubscribe']",
                "a[href*='opt-out']",
                "a[href*='optout']",
                "input[value*='unsubscribe']",
                "input[value*='opt-out']",
                "button[type='submit']",
                ".unsubscribe",
                "#unsubscribe",
                "[data-action='unsubscribe']",
                "[class*='unsubscribe']",
                "[id*='unsubscribe']"
            };
            
            // Try CSS selectors first
            for (String selector : cssSelectors) {
                try {
                    List<WebElement> elements = driver.findElements(By.cssSelector(selector));
                    if (!elements.isEmpty()) {
                        WebElement element = elements.get(0);
                        // Scroll into view
                        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
                        Thread.sleep(500);
                        element.click();
                        Thread.sleep(2000);
                        log.info("Clicked unsubscribe element with CSS selector: {}", selector);
                        return true;
                    }
                } catch (Exception e) {
                    // Try next selector
                }
            }
            
            // Try XPath for buttons/links containing "unsubscribe" text
            try {
                List<WebElement> textElements = driver.findElements(
                    By.xpath("//a[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'unsubscribe')] | " +
                             "//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'unsubscribe')] | " +
                             "//input[@type='submit' and contains(translate(@value, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'unsubscribe')]")
                );
                if (!textElements.isEmpty()) {
                    WebElement element = textElements.get(0);
                    ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
                    Thread.sleep(500);
                    element.click();
                    Thread.sleep(2000);
                    log.info("Clicked unsubscribe element found by text");
                    return true;
                }
            } catch (Exception e) {
                log.debug("XPath search failed: {}", e.getMessage());
            }
            
            log.warn("Could not find unsubscribe button using common selectors");
            return false;
        } catch (Exception e) {
            log.error("Error in simple unsubscribe attempt: {}", e.getMessage(), e);
            return false;
        }
    }
}
