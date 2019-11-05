package io.elastest.codeurjc.qe.openvidu.webrtc;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.elastest.codeurjc.qe.openvidu.BrowserClient;
import io.elastest.codeurjc.qe.openvidu.CountDownLatchWithException;
import io.elastest.codeurjc.qe.openvidu.CountDownLatchWithException.AbortedException;

public class OpenviduCheckWebRTC
        extends io.elastest.codeurjc.qe.openvidu.webrtc.WebRtcBaseTest {

    private static CountDownLatchWithException waitForSessionReadyLatch;
    public static ExecutorService browserInitializationTaskExecutor = Executors
            .newCachedThreadPool();

    private double currentJitterAverage = 0;
    private List<Integer> jitterList;
    private double currentDelayAverage = 0;
    private List<Integer> delayList;
    private int STATS_LIMIT = 150;

    @Test
    public void printJitterAndDelay(TestInfo info)
            throws SessionNotCreatedException, TimeoutException, IOException {
        startBrowsers(info);

        final int WAIT_TIME = 60;
        long endWaitTime = System.currentTimeMillis() + WAIT_TIME * 1000;
        boolean toMuchDelayOrJitter = false;

        String statsLimit = System.getenv("STATS_LIMIT");

        if (statsLimit != null) {
            STATS_LIMIT = Integer.valueOf(statsLimit);
        }

        logger.info("Printing stats while {}s", WAIT_TIME);
        while (System.currentTimeMillis() < endWaitTime
                && !toMuchDelayOrJitter) {

            for (BrowserClient browserClient : browserClientList) {
                currentJitterAverage = 0;
                jitterList = new ArrayList<Integer>();
                currentDelayAverage = 0;
                delayList = new ArrayList<Integer>();
                try {
                    extractJitterAndDelay(browserClient);

                    for (Integer jitter : jitterList) {
                        currentJitterAverage += jitter;
                    }

                    currentJitterAverage = currentJitterAverage
                            / jitterList.size();

                    for (Integer delay : delayList) {
                        currentDelayAverage += delay;
                    }
                    currentDelayAverage = currentDelayAverage
                            / delayList.size();

                    if (currentJitterAverage > STATS_LIMIT
                            || currentDelayAverage > STATS_LIMIT) {
                        toMuchDelayOrJitter = true;
                        break;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            sleep(1000);
        }

        if (toMuchDelayOrJitter) {
            Assertions.fail("Delay (" + currentDelayAverage
                    + ") or/and Jitter (" + currentJitterAverage
                    + "), are greather than " + STATS_LIMIT);
        }

        logger.info("End");
        browserInitializationTaskExecutor.shutdown();

        try {
            logger.info("Await termination");
            browserInitializationTaskExecutor.awaitTermination(5,
                    TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            logger.error(
                    "Browsers threads could not be finished after 5 minutes");
            Assertions.fail(e.getMessage());
            return;
        }
    }

    private void extractJitterAndDelay(BrowserClient browserClient)
            throws Exception {
        JsonObject eventsAndStats = browserClient
                .getBrowserEventsAndStatsObject();
        JsonObject stats = browserClient.getStatsFromObject(eventsAndStats);

        String currentUserId = browserClient.getUserId();

        logger.info("Stats received from user {}: {}",
                browserClient.getUserId(), stats);
        if (stats != null) {
            // Received stats from current user contains stats from all users
            // For each user in stats
            for (Entry<String, JsonElement> user : stats.entrySet()) {
                logger.info("Stats from user {}. User stats: {}", currentUserId,
                        user.getKey());
                JsonArray userStats = (JsonArray) user.getValue();
                if (userStats != null) {
                    // For each user stats
                    for (JsonElement userStatsElement : userStats) {
                        if (userStatsElement != null) {
                            JsonObject userStatsObj = (JsonObject) userStatsElement;
                            for (Entry<String, JsonElement> userSingleStat : userStatsObj
                                    .entrySet()) {
                                if (userSingleStat != null) {
                                    String statName = userSingleStat.getKey();
                                    if ("jitter".equals(statName)) {
                                        Integer jitter = userSingleStat
                                                .getValue().getAsInt();
                                        logger.info(
                                                "Current User '{}', User:{}, Jitter = {}",
                                                currentUserId, user.getKey(),
                                                jitter);

                                        synchronized (jitterList) {
                                            jitterList.add(jitter);
                                        }

                                    } else if ("delay".equals(statName)) {
                                        Integer delay = userSingleStat
                                                .getValue().getAsInt();
                                        logger.info(
                                                "Current User '{}', User:{}, Delay = {}",
                                                currentUserId, user.getKey(),
                                                delay);
                                        synchronized (delayList) {
                                            delayList.add(delay);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }

    }

    private void startBrowsers(TestInfo info)
            throws TimeoutException, IOException {
        waitForSessionReadyLatch = new CountDownLatchWithException(
                USERS_BY_SESSION);

        final List<Runnable> browserThreads = new ArrayList<>();
        // Start N browsers
        for (int i = 0; i < USERS_BY_SESSION; i++) {
            final String userId = "user-" + (i + 1);
            browserThreads.add(() -> {
                try {
                    this.startBrowser(info, userId);
                    waitForSessionReadyLatch.countDown();
                } catch (TimeoutException | IOException | NullPointerException
                        | SessionNotCreatedException e) {
                    logger.error("Error on start browser of user {}: {}",
                            userId, e.getMessage());
                    waitForSessionReadyLatch.abort(e.getMessage());
                }
            });
        }

        for (Runnable r : browserThreads) {
            browserInitializationTaskExecutor.execute(r);
        }

        try {
            logger.info("Waiting for all browsers  are ready");
            waitForSessionReadyLatch.await();
        } catch (AbortedException e) {
            logger.error("Some browser does not have a stable session: {}",
                    e.getMessage());
            Assertions.fail("Session did not reach stable status in timeout: "
                    + e.getMessage());
            return;
        }

        logger.info("All browsers of session {} are now ready!");
    }

    @SuppressWarnings("unchecked")
    public void startBrowser(TestInfo info, String userId)
            throws TimeoutException, IOException, SessionNotCreatedException {
        logger.info("Starting browser for user {} ", userId);

        String testName = info.getTestMethod().get().getName();

        WebDriver driver;
        DesiredCapabilities capabilities = DesiredCapabilities.chrome();
        ChromeOptions options = new ChromeOptions();
        // This flag avoids to grant the user media
        options.addArguments("--use-fake-ui-for-media-stream");
        // This flag fakes user media with synthetic video
        options.addArguments("--use-fake-device-for-media-stream");
        // This flag allows to load fake media files from host
        options.addArguments("--allow-file-access-from-files");
        capabilities.setAcceptInsecureCerts(true);
        if (EUS_URL == null) {
            capabilities.setCapability(ChromeOptions.CAPABILITY, options);

            driver = new ChromeDriver(options);
        } else {
            logger.info("Using ElasTest EUS URL: {}", EUS_URL);

            capabilities.setCapability("testName",
                    testName + "_" + userId.replaceAll("-", "_"));
            // AWS capabilities for browsers

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> awsConfigMap = mapper
                    .readValue(awsConfig.toString(), Map.class);

            capabilities.setCapability("awsConfig", awsConfigMap);

            // This flag sets the video input
            options.addArguments("--use-file-for-fake-video-capture="
                    + "/opt/openvidu/fakevideo.y4m");
            // This flag sets the audio input
            options.addArguments("--use-file-for-fake-audio-capture="
                    + "/opt/openvidu/fakeaudio.wav");
            capabilities.setCapability(ChromeOptions.CAPABILITY, options);
            capabilities.setCapability("elastestTimeout", 3600);

            String browserVersion = System.getProperty("browserVersion");
            if (browserVersion != null) {
                capabilities.setVersion(browserVersion);
            }
            // Create browser session
            try {
                driver = new RemoteWebDriver(new URL(EUS_URL), capabilities);
            } catch (SessionNotCreatedException e) {
                String msg = "Error on create new RemoteWebDriver (SessionNotCreatedException) => "
                        + e.getMessage();
                throw new SessionNotCreatedException(msg);
            }
        }
        int SESSION_ID = 1;
        BrowserClient browserClient = new BrowserClient(driver, userId,
                SESSION_ID);
        browserClientList.add(browserClient);

        String publicUrl = OPENVIDU_SUT_URL
                + (OPENVIDU_SUT_URL.endsWith("/") ? "" : "/");

        browserClient.getDriver()
        			.get(OPENVIDU_WEBAPP_URL + "?publicurl=" + publicUrl
                        + "&secret=" + OPENVIDU_SECRET + "&sessionId="
                        + SESSION_ID + "&userId=" + userId);
        
        Alert alert =browserClient.getDriver().switchTo().alert();
        alert.accept();
        
        browserClient.getDriver()
        	.get(browserClient.getDriver().getCurrentUrl().substring(0, browserClient.getDriver().getCurrentUrl().length() - 19));

        WebDriverWait wait = new WebDriverWait(browserClient.getDriver(), 10);  // you can reuse this one

        WebElement elem = driver.findElement(By.id("test-btn"));
        elem.click();
        wait.until(ExpectedConditions.visibilityOf(elem));
        
        browserClient.getDriver().findElement(By.tagName("test-btn")).click();
        
        WebElement elem2 = driver.findElement(By.id("mat-input-0"));
        wait.until(ExpectedConditions.visibilityOf(elem2));
        elem2.sendKeys("MY_SECRET");

        browserClient.getDriver().findElement(By.id("join-btn")).click();
    }

}
