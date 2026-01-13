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
     * Implements retry logic with exponential backoff for better reliability.
     * @param unsubscribeUrl The unsubscribe URL
     * @param instructionsJson JSON string with actions to perform
     * @param userEmail The user's email address (for filling forms)
     * @return true if unsubscribe was successful, false otherwise
     */
    public boolean executeUnsubscribe(String unsubscribeUrl, String instructionsJson, String userEmail) {
        int maxRetries = 2; // Retry up to 2 times (3 total attempts)
        long baseDelayMs = 2000; // Base delay of 2 seconds
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long delayMs = baseDelayMs * (long) Math.pow(2, attempt - 1); // Exponential backoff
                log.info("Retrying unsubscribe attempt {} for URL: {} (waiting {}ms)", attempt + 1, unsubscribeUrl, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during retry delay");
                    return false;
                }
            }
            
            boolean success = attemptUnsubscribe(unsubscribeUrl, instructionsJson, userEmail, attempt);
            if (success) {
                return true;
            }
            
            log.warn("Unsubscribe attempt {} failed for URL: {}", attempt + 1, unsubscribeUrl);
        }
        
        log.error("All unsubscribe attempts failed for URL: {}", unsubscribeUrl);
        return false;
    }
    
    /**
     * Single attempt at executing unsubscribe actions.
     */
    private boolean attemptUnsubscribe(String unsubscribeUrl, String instructionsJson, String userEmail, int attemptNumber) {
        WebDriver driver = null;
        try {
            // Setup Chrome in headless mode with improved options
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-software-rasterizer");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-blink-features=AutomationControlled"); // Avoid detection
            options.addArguments("--disable-web-security"); // For some complex pages
            options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            
            // Use system Chrome binary if available (for Docker/Alpine)
            String chromeBin = System.getenv("CHROME_BIN");
            if (chromeBin != null && !chromeBin.isEmpty()) {
                options.setBinary(chromeBin);
            }
            
            driver = new ChromeDriver(options);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); // Increased timeout
            
            log.info("Attempt {}: Navigating to unsubscribe URL: {}", attemptNumber + 1, unsubscribeUrl);
            driver.get(unsubscribeUrl);
            
            // Wait for page to load with better detection
            waitForPageLoad(driver, wait);
            
            // Try simple HTTP-based unsubscribe first (faster and more reliable for simple cases)
            if (attemptNumber == 0 && attemptSimpleHttpUnsubscribe(unsubscribeUrl, userEmail)) {
                log.info("Simple HTTP unsubscribe succeeded");
                return true;
            }
            
            // Parse instructions JSON
            JsonNode instructions = null;
            JsonNode actions = null;
            try {
                instructions = objectMapper.readTree(instructionsJson);
                actions = instructions.get("actions");
            } catch (Exception e) {
                log.warn("Failed to parse instructions JSON, attempting simple click: {}", e.getMessage());
            }
            
            if (actions == null || !actions.isArray() || actions.size() == 0) {
                log.warn("Invalid or empty instructions format, attempting simple click on common unsubscribe elements");
                return attemptSimpleUnsubscribe(driver, wait);
            }
            
            // Execute each action with better error handling
            boolean allActionsSucceeded = true;
            for (JsonNode action : actions) {
                try {
                    String type = action.get("type").asText();
                    
                    boolean actionSuccess = false;
                    switch (type.toLowerCase()) {
                        case "fill":
                            String field = action.get("field").asText();
                            String value = action.has("value") ? action.get("value").asText() : userEmail;
                            String selector = action.has("selector") ? action.get("selector").asText() : null;
                            
                            actionSuccess = fillField(driver, wait, field, value, selector);
                            break;
                            
                        case "click":
                            String clickSelector = action.get("selector").asText();
                            actionSuccess = clickElement(driver, wait, clickSelector);
                            break;
                            
                        case "toggle":
                        case "check":
                            String toggleSelector = action.get("selector").asText();
                            boolean checked = action.has("checked") ? action.get("checked").asBoolean() : true;
                            actionSuccess = toggleCheckbox(driver, wait, toggleSelector, checked);
                            break;
                            
                        case "select":
                            String selectSelector = action.get("selector").asText();
                            String optionValue = action.get("value").asText();
                            actionSuccess = selectOption(driver, wait, selectSelector, optionValue);
                            break;
                            
                        default:
                            log.warn("Unknown action type: {}", type);
                            actionSuccess = false;
                    }
                    
                    if (!actionSuccess) {
                        allActionsSucceeded = false;
                    }
                    
                    // Small delay between actions
                    Thread.sleep(500);
                } catch (Exception actionException) {
                    log.warn("Error executing action: {}", actionException.getMessage());
                    allActionsSucceeded = false;
                }
            }
            
            // If actions failed, try simple unsubscribe as fallback
            if (!allActionsSucceeded) {
                log.warn("Some actions failed, attempting simple unsubscribe as fallback");
                return attemptSimpleUnsubscribe(driver, wait);
            }
            
            // Wait a bit for any final processing
            Thread.sleep(3000); // Increased wait time
            
            // Check for success indicators with more comprehensive detection
            boolean success = checkSuccessIndicators(driver);
            
            log.info("Unsubscribe attempt {} completed. Success: {}", attemptNumber + 1, success);
            return success;
            
        } catch (Exception e) {
            log.error("Error executing unsubscribe automation (attempt {}): {}", attemptNumber + 1, e.getMessage(), e);
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
    
    /**
     * Attempts simple HTTP-based unsubscribe (GET or POST) for simple unsubscribe links.
     * This is faster and more reliable than browser automation for simple cases.
     */
    private boolean attemptSimpleHttpUnsubscribe(String unsubscribeUrl, String userEmail) {
        try {
            java.net.URL url = new java.net.URL(unsubscribeUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                // Read response to check for success
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                reader.close();
                
                String responseText = response.toString().toLowerCase();
                boolean success = responseText.contains("unsubscribed") || 
                                responseText.contains("success") ||
                                responseText.contains("removed") ||
                                responseText.contains("confirmed") ||
                                responseText.contains("you have been unsubscribed");
                
                if (success) {
                    log.info("Simple HTTP unsubscribe succeeded");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Simple HTTP unsubscribe failed (expected for complex pages): {}", e.getMessage());
        }
        return false;
    }
    
    /**
     * Wait for page to load with better detection.
     */
    private void waitForPageLoad(WebDriver driver, WebDriverWait wait) {
        try {
            // Wait for document ready state
            wait.until(webDriver -> 
                ((org.openqa.selenium.JavascriptExecutor) webDriver)
                    .executeScript("return document.readyState").equals("complete")
            );
            Thread.sleep(2000); // Additional wait for dynamic content
        } catch (Exception e) {
            log.warn("Page load wait interrupted, continuing anyway: {}", e.getMessage());
        }
    }
    
    /**
     * Comprehensive success indicator detection.
     */
    private boolean checkSuccessIndicators(WebDriver driver) {
        try {
            String pageSource = driver.getPageSource().toLowerCase();
            String pageTitle = driver.getTitle().toLowerCase();
            String currentUrl = driver.getCurrentUrl().toLowerCase();
            
            // Check page source
            boolean successInSource = pageSource.contains("unsubscribed") || 
                                    pageSource.contains("success") ||
                                    pageSource.contains("removed") ||
                                    pageSource.contains("confirmed") ||
                                    pageSource.contains("you have been unsubscribed") ||
                                    pageSource.contains("subscription cancelled") ||
                                    pageSource.contains("opt-out successful");
            
            // Check page title
            boolean successInTitle = pageTitle.contains("unsubscribed") || 
                                   pageTitle.contains("success") ||
                                   pageTitle.contains("confirmed");
            
            // Check URL (some pages redirect to success page)
            boolean successInUrl = currentUrl.contains("success") ||
                                 currentUrl.contains("confirmed") ||
                                 currentUrl.contains("unsubscribed");
            
            // Check for common error indicators (if found, it's not a success)
            boolean hasError = pageSource.contains("error") && 
                             (pageSource.contains("unable") || pageSource.contains("failed") || pageSource.contains("invalid"));
            
            boolean success = (successInSource || successInTitle || successInUrl) && !hasError;
            
            log.debug("Success check - Source: {}, Title: {}, URL: {}, HasError: {}, Final: {}", 
                    successInSource, successInTitle, successInUrl, hasError, success);
            
            return success;
        } catch (Exception e) {
            log.warn("Error checking success indicators: {}", e.getMessage());
            return false;
        }
    }

    private boolean fillField(WebDriver driver, WebDriverWait wait, String fieldName, String value, String selector) {
        try {
            WebElement field = null;
            
            if (selector != null && !selector.isEmpty()) {
                // Use specific selector
                try {
                    field = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
                } catch (Exception e) {
                    log.debug("CSS selector '{}' not found, trying alternatives", selector);
                }
            }
            
            // If selector failed or not provided, try to find by name, id, or placeholder
            if (field == null) {
                field = findFieldByNameIdOrPlaceholder(driver, fieldName);
            }
            
            if (field != null) {
                // Scroll into view
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", field);
                Thread.sleep(200);
                
                field.clear();
                field.sendKeys(value);
                log.debug("Filled field '{}' with value: {}", fieldName, value);
                return true;
            } else {
                log.warn("Could not find field: {}", fieldName);
                return false;
            }
        } catch (Exception e) {
            log.warn("Error filling field '{}': {}", fieldName, e.getMessage());
            return false;
        }
    }

    private boolean clickElement(WebDriver driver, WebDriverWait wait, String selector) {
        try {
            WebElement element = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector)));
            
            // Scroll into view
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
            Thread.sleep(200);
            
            // Try regular click first
            try {
                element.click();
            } catch (Exception e) {
                // If regular click fails, try JavaScript click
                log.debug("Regular click failed, trying JavaScript click: {}", e.getMessage());
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
            }
            
            log.debug("Clicked element: {}", selector);
            return true;
        } catch (Exception e) {
            log.warn("Error clicking element '{}': {}", selector, e.getMessage());
            return false;
        }
    }

    private boolean toggleCheckbox(WebDriver driver, WebDriverWait wait, String selector, boolean checked) {
        try {
            WebElement checkbox = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
            
            // Scroll into view
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", checkbox);
            Thread.sleep(200);
            
            if (checkbox.isSelected() != checked) {
                checkbox.click();
            }
            log.debug("Toggled checkbox '{}' to {}", selector, checked);
            return true;
        } catch (Exception e) {
            log.warn("Error toggling checkbox '{}': {}", selector, e.getMessage());
            return false;
        }
    }

    private boolean selectOption(WebDriver driver, WebDriverWait wait, String selector, String optionValue) {
        try {
            WebElement select = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(selector)));
            
            // Scroll into view
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", select);
            Thread.sleep(200);
            
            org.openqa.selenium.support.ui.Select dropdown = new org.openqa.selenium.support.ui.Select(select);
            
            // Try by value first, then by visible text
            try {
                dropdown.selectByValue(optionValue);
            } catch (Exception e) {
                log.debug("Select by value failed, trying by visible text: {}", e.getMessage());
                dropdown.selectByVisibleText(optionValue);
            }
            
            log.debug("Selected option '{}' in '{}'", optionValue, selector);
            return true;
        } catch (Exception e) {
            log.warn("Error selecting option '{}' in '{}': {}", optionValue, selector, e.getMessage());
            return false;
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
