package io.elastest.codeurjc.qe.openvidu;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BrowserClient {
    final Logger logger = getLogger(lookup().lookupClass());

    private Thread pollingThread;
    private WebDriver driver;
    private String userId;
    private int session;

    private AtomicBoolean stopped = new AtomicBoolean(false);

    private Queue<JsonObject> eventQueue;
    private Map<String, AtomicInteger> numEvents;
    private Map<String, CountDownLatch> eventCountdowns;

    JsonParser jsonParser = new JsonParser();

    public BrowserClient(WebDriver driver, String userId, int session) {
        this.driver = driver;
        this.userId = userId;
        this.session = session;

        this.eventQueue = new ConcurrentLinkedQueue<JsonObject>();
        this.numEvents = new ConcurrentHashMap<>();
        this.eventCountdowns = new ConcurrentHashMap<>();
    }

    public Thread getPollingThread() {
        return pollingThread;
    }

    public String getUserId() {
        return userId;
    }

    public int getSession() {
        return session;
    }

    public AtomicBoolean getStopped() {
        return stopped;
    }

    public Queue<JsonObject> getEventQueue() {
        return eventQueue;
    }

    public Map<String, AtomicInteger> getNumEvents() {
        return numEvents;
    }

    public Map<String, CountDownLatch> getEventCountdowns() {
        return eventCountdowns;
    }

    public JsonParser getJsonParser() {
        return jsonParser;
    }

    public WebDriver getDriver() {
        return driver;
    }

    public void startEventPolling(boolean processEvents, boolean processStats) {
        logger.info("Starting event polling in user {} session {}", userId,
                session);
        this.pollingThread = new Thread(() -> {
            while (!this.stopped.get()) {
                this.getBrowserEvents(processEvents, processStats);
                try {
                    Thread.sleep(BaseTest.BROWSER_POLL_INTERVAL);
                } catch (InterruptedException e) {
                    logger.debug("OpenVidu events polling thread interrupted");
                }
            }
        });

        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable ex) {
                if (ex.getClass().getSimpleName()
                        .equals("NoSuchSessionException")) {
                    logger.error(
                            "Disposing driver when running 'executeScript'");
                }
            }
        };

        this.pollingThread.setUncaughtExceptionHandler(handler);
        this.pollingThread.start();
    }

    public void stopEventPolling() {
        this.eventCountdowns.clear();
        this.numEvents.clear();
        this.stopped.set(true);
        this.pollingThread.interrupt();
    }

    public void waitForEvent(String eventName, int eventNumber)
            throws TimeoutException {
        logger.info(
                "Waiting for event {} to occur {} times in user {} session {}",
                eventName, eventNumber, userId, session);

        CountDownLatch eventSignal = new CountDownLatch(eventNumber);
        this.setCountDown(eventName, eventSignal);
        try {
            int timeoutInSecs = 240;
            if (!eventSignal.await(timeoutInSecs * 1000,
                    TimeUnit.MILLISECONDS)) {
                throw (new TimeoutException("Timeout (" + timeoutInSecs
                        + "sec) in waiting for event " + eventName));
            }
        } catch (TimeoutException e) {
            throw e;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public JsonObject getBrowserEventsAndStatsObject() throws Exception {
        String eventsRaw = (String) ((JavascriptExecutor) driver)
                .executeScript("window.collectEventsAndStats();"
                        + "var result = JSON.stringify(window.openviduLoadTest);"
                        + "window.resetEventsAndStats();" + "return result;");
        return jsonParser.parse(eventsRaw).getAsJsonObject();

    }

    public JsonArray getEventsFromObject(JsonObject eventsAndStats) {
        return eventsAndStats.get("events").getAsJsonArray();
    }

    public JsonObject getStatsFromObject(JsonObject eventsAndStats) {
        return eventsAndStats.get("stats").getAsJsonObject();
    }

    public void getBrowserEvents(boolean processEvents, boolean processStats) {
        JsonObject eventsAndStats = null;
        try {
            eventsAndStats = getBrowserEventsAndStatsObject();
        } catch (Exception e) {
            return;
        }

        if (eventsAndStats == null || eventsAndStats.isJsonNull()) {
            return;
        }

        // EVENTS
        if (processEvents) {
            JsonArray events = getEventsFromObject(eventsAndStats);
            for (JsonElement ev : events) {
                JsonObject event = ev.getAsJsonObject();
                String eventName = event.get("event").getAsString();
                logger.info("New event received in user {} of session {}: {}",
                        userId, session, event);
                this.eventQueue.add(event);
                getNumEvents(eventName).incrementAndGet();

                if (this.eventCountdowns.get(eventName) != null) {
                    doCountDown(eventName);
                }
            }
        }

        // STATS
        if (processStats) {
            JsonObject stats = getStatsFromObject(eventsAndStats);
            if (stats != null) {
                for (Entry<String, JsonElement> user : stats.entrySet()) {
                    JsonArray userStats = (JsonArray) user.getValue();
                    if (userStats != null) {
                        for (JsonElement userStatsElement : userStats) {
                            if (userStatsElement != null) {
                                JsonObject userStatsObj = (JsonObject) userStatsElement;
                                for (Entry<String, JsonElement> userSingleStat : userStatsObj
                                        .entrySet()) {
                                    if (userSingleStat != null && ("jitter"
                                            .equals(userSingleStat.getKey())
                                            || "delay".equals(
                                                    userSingleStat.getKey()

                                            ))) {
                                        logger.info("User '{}' Stat: {} = {}",
                                                userId, userSingleStat.getKey(),
                                                userSingleStat.getValue());
                                    }
                                }
                            }
                        }
                    }

                }
            }

        }
    }

    private AtomicInteger getNumEvents(String eventName) {
        return this.numEvents.computeIfAbsent(eventName,
                k -> new AtomicInteger(0));
    }

    private void setCountDown(String eventName, CountDownLatch cd) {
        logger.info("Setting countDownLatch for event {} in user {} session {}",
                eventName, userId, session);
        this.eventCountdowns.put(eventName, cd);
        for (int i = 0; i < getNumEvents(eventName).get(); i++) {
            doCountDown(eventName);
        }
    }

    private void doCountDown(String eventName) {
        logger.info("Doing countdown of event {} in user {} session {}",
                eventName, userId, session);
        this.eventCountdowns.get(eventName).countDown();

    }

    public void dispose() {
        try {
            if (driver != null) {
                logger.info(
                        "Stopping browser of user {} session {}. This process can take a while, since the videos are going to be downloaded",
                        userId, session);
                driver.quit();
            }
        } catch (Exception e) {
        }
    }

    public JsonArray getStreams() throws Exception {
        String streams = (String) ((JavascriptExecutor) driver)
                .executeScript("var result = window.session.streamManagers.toString();"
                        + "return result;");
        return jsonParser.parse(streams).getAsJsonArray();

    }

    public List<JsonObject> getOnlySubscriberStreams() throws Exception {
        JsonArray streams = getStreams();
        return filterSubscriberStreams(streams);
    }

    public List<JsonObject> filterSubscriberStreams(JsonArray streams)
            throws Exception {
        List<JsonObject> subscriberStreams = new ArrayList<JsonObject>();
        for (Object stream : streams) {
            if (stream instanceof JsonObject) {
                JsonObject streamObj = (JsonObject) stream;
                if (streamObj != null && streamObj.get("remote") != null) {
                    if ("true".equals(streamObj.get("remote").getAsString())
                            || streamObj.get("remote").getAsBoolean()) {
                        subscriberStreams.add(streamObj);
                    }
                }
            }
        }
        return subscriberStreams;
    }

    public String initLocalRecorder(JsonObject stream) throws Exception {
        String localRecorderId = (String) ((JavascriptExecutor) driver)
                .executeScript(
                        "var localRecorder = window.OpenVidu.prototype.initLocalRecorder("
                                + stream + ");"
                                + "window['localRecorder' + localRecorder.id] = localRecorder;"
                                + "return localRecorder.id;");
        return localRecorderId;
    }

    public void startRecording(String localRecorderId) throws Exception {
        ((JavascriptExecutor) driver).executeScript(
                "window['localRecorder' + " + localRecorderId + "].record();");
    }

    public void stopRecording(String localRecorderId) throws Exception {
        ((JavascriptExecutor) driver).executeScript(
                "window['localRecorder' + " + localRecorderId + "].stop();");
    }

    public void downloadRecording(String localRecorderId) throws Exception {
        ((JavascriptExecutor) driver).executeScript("window['localRecorder' + "
                + localRecorderId + "].download();");
    }
}
