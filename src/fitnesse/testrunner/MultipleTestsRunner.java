// Copyright (C) 2003-2009 by Object Mentor, Inc. All rights reserved.
// Released under the terms of the CPL Common Public License version 1.0.
package fitnesse.testrunner;

import fitnesse.FitNesseContext;
import fitnesse.components.ClassPathBuilder;
import fitnesse.responders.run.Stoppable;
import fitnesse.responders.run.SuiteContentsFinder;
import fitnesse.testsystems.*;
import fitnesse.wiki.WikiPage;
import util.TimeMeasurement;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class MultipleTestsRunner implements TestSystemListener, Stoppable {

  private final ResultsListener resultsListener;
  private final FitNesseContext fitNesseContext;
  private final WikiPage page;
  private final List<WikiPage> testPagesToRun;
  private boolean isFastTest = false;
  private boolean isRemoteDebug = false;

  private LinkedList<TestPage> processingQueue = new LinkedList<TestPage>();
  private WikiTestPage currentTest = null;

  private TestSystemGroup testSystemGroup = null;
  private volatile boolean isStopped = false;
  private String stopId = null;
  private PageListSetUpTearDownSurrounder surrounder;
  TimeMeasurement currentTestTime, totalTestTime;
  private CompositeExecutionLog log;

  public MultipleTestsRunner(final List<WikiPage> testPagesToRun,
                             final FitNesseContext fitNesseContext,
                             final WikiPage page,
                             final ResultsListener resultsListener) {
    this.testPagesToRun = testPagesToRun;
    this.resultsListener = resultsListener;
    this.page = page;
    this.fitNesseContext = fitNesseContext;
    surrounder = new PageListSetUpTearDownSurrounder(fitNesseContext.root);
    log = new CompositeExecutionLog(page);
  }

  public void setDebug(boolean isDebug) {
    isRemoteDebug = isDebug;
  }

  public void setFastTest(boolean isFastTest) {
    this.isFastTest = isFastTest;
  }

  public void executeTestPages() {
    try {
      internalExecuteTestPages();
      allTestingComplete();
    } catch (Exception exception) {
      //hoped to write exceptions to log file but will take some work.
      exception.printStackTrace(System.out);
      errorOccurred(exception);
    }
  }

  void allTestingComplete() throws IOException {
    TimeMeasurement completionTimeMeasurement = new TimeMeasurement().start();
    resultsListener.allTestingComplete(totalTestTime.stop());
    completionTimeMeasurement.stop(); // a non-trivial amount of time elapses here
  }

  private void internalExecuteTestPages() throws IOException, InterruptedException {
    testSystemGroup = new TestSystemGroup(fitNesseContext, this);
    stopId = fitNesseContext.runningTestingTracker.addStartedProcess(this);

    testSystemGroup.setFastTest(isFastTest);

    resultsListener.setExecutionLogAndTrackingId(stopId, log);
    PagesByTestSystem pagesByTestSystem = makeMapOfPagesByTestSystem();
    announceTotalTestsToRun(pagesByTestSystem);

    for (Map.Entry<WikiPageDescriptor, LinkedList<WikiTestPage>> PagesByTestSystem : pagesByTestSystem.entrySet()) {
      startTestSystemAndExecutePages(PagesByTestSystem.getKey(), PagesByTestSystem.getValue());
    }

    fitNesseContext.runningTestingTracker.removeEndedProcess(stopId);
  }

  private void startTestSystemAndExecutePages(WikiPageDescriptor descriptor, List<WikiTestPage> testSystemPages) throws IOException, InterruptedException {
    TestSystem testSystem = null;
    try {
      if (!isStopped) {
        testSystem = testSystemGroup.startTestSystem(new WikiPageDescriptor(descriptor,
                new ClassPathBuilder().buildClassPath(testPagesToRun)));
      }

      if (testSystem != null) {
        if (testSystem.isSuccessfullyStarted()) {
          executeTestSystemPages(testSystemPages, testSystem);
          waitForTestSystemToSendResults();
        }
      }
    } finally {
      if (!isStopped && testSystem != null) {
        testSystem.bye();
      }
    }
  }

  private void executeTestSystemPages(List<WikiTestPage> pagesInTestSystem, TestSystem testSystem) throws IOException, InterruptedException {
    for (TestPage testPage : pagesInTestSystem) {
      addToProcessingQueue(testPage);
      testSystem.runTests(testPage);
    }
  }

  void addToProcessingQueue(TestPage testPage) {
    processingQueue.addLast(testPage);
  }

  private void waitForTestSystemToSendResults() throws InterruptedException {
    while ((processingQueue.size() > 0) && isNotStopped())
      Thread.sleep(50);
  }

  PagesByTestSystem makeMapOfPagesByTestSystem() {
    return addSuiteSetUpAndTearDownToAllTestSystems(mapWithAllPagesButSuiteSetUpAndTearDown());
  }

  private PagesByTestSystem mapWithAllPagesButSuiteSetUpAndTearDown() {
    PagesByTestSystem pagesByTestSystem = new PagesByTestSystem();

    for (WikiPage testPage : testPagesToRun) {
      if (!SuiteContentsFinder.isSuiteSetupOrTearDown(testPage)) {
        addPageToListWithinMap(pagesByTestSystem, testPage);
      }
    }
    return pagesByTestSystem;
  }

  private void addPageToListWithinMap(PagesByTestSystem pagesByTestSystem, WikiPage wikiPage) {
    WikiTestPage testPage = new WikiTestPage(wikiPage);
    WikiPageDescriptor descriptor = new WikiPageDescriptor(wikiPage.readOnlyData(), isRemoteDebug, "");
    getOrMakeListWithinMap(pagesByTestSystem, descriptor).add(testPage);
  }

  private LinkedList<WikiTestPage> getOrMakeListWithinMap(PagesByTestSystem pagesByTestSystem, WikiPageDescriptor descriptor) {
    LinkedList<WikiTestPage> pagesForTestSystem;
    if (!pagesByTestSystem.containsKey(descriptor)) {
      pagesForTestSystem = new LinkedList<WikiTestPage>();
      pagesByTestSystem.put(descriptor, pagesForTestSystem);
    } else {
      pagesForTestSystem = pagesByTestSystem.get(descriptor);
    }
    return pagesForTestSystem;
  }

  private PagesByTestSystem addSuiteSetUpAndTearDownToAllTestSystems(PagesByTestSystem pagesByTestSystem) {
    if (testPagesToRun.size() == 0)
      return pagesByTestSystem;
    for (LinkedList<WikiTestPage> pagesForTestSystem : pagesByTestSystem.values())
      surrounder.surroundGroupsOfTestPagesWithRespectiveSetUpAndTearDowns(pagesForTestSystem);

    return pagesByTestSystem;
  }

  void announceTotalTestsToRun(PagesByTestSystem pagesByTestSystem) {
    int tests = 0;
    for (LinkedList<WikiTestPage> listOfPagesToRun : pagesByTestSystem.values()) {
      tests += listOfPagesToRun.size();
    }
    resultsListener.announceNumberTestsToRun(tests);
    totalTestTime = new TimeMeasurement().start();
  }

  @Override
  public void testSystemStarted(TestSystem testSystem) {
    resultsListener.testSystemStarted(testSystem);
  }

  @Override
  public void testOutputChunk(String output) throws IOException {
    TestPage firstInQueue = processingQueue.isEmpty() ? null : processingQueue.getFirst();
    boolean isNewTest = firstInQueue != null && firstInQueue != currentTest;
    if (isNewTest) {
      startingNewTest(firstInQueue);
    }
    resultsListener.testOutputChunk(output);
  }

  void startingNewTest(TestPage test) throws IOException {
    currentTest = (WikiTestPage) test;
    currentTestTime = new TimeMeasurement().start();
    resultsListener.newTestStarted(currentTest, currentTestTime);
  }

  @Override
  public void testComplete(TestSummary testSummary) throws IOException {
    TestPage testPage = processingQueue.removeFirst();
    resultsListener.testComplete((WikiTestPage) testPage, testSummary, currentTestTime.stop());
  }

  private void errorOccurred(Throwable e) {
    try {
      resultsListener.errorOccured();
      stop();
    } catch (Exception e1) {
      if (isNotStopped()) {
        e1.printStackTrace();
      }
    }
  }

  @Override
  public void testSystemStopped(TestSystem testSystem, ExecutionLog executionLog, Throwable cause) {
    log.add(testSystem.getName(), executionLog);
    if (cause != null) {
      errorOccurred(cause);
    }
  }

  @Override
  public void testAssertionVerified(Assertion assertion, TestResult testResult) {
    resultsListener.testAssertionVerified(assertion, testResult);
  }

  @Override
  public void testExceptionOccurred(Assertion assertion, ExceptionResult exceptionResult) {
    resultsListener.testExceptionOccurred(assertion, exceptionResult);
  }

  private boolean isNotStopped() {
    return !isStopped;
  }

  @Override
  public void stop() throws IOException {
    boolean wasNotStopped = isNotStopped();
    isStopped = true;
    if (stopId != null) {
      fitNesseContext.runningTestingTracker.removeEndedProcess(stopId);
    }

    if (wasNotStopped) {
      testSystemGroup.kill();
    }
  }
}

class PagesByTestSystem extends HashMap<WikiPageDescriptor, LinkedList<WikiTestPage>> {
  private static final long serialVersionUID = 1L;
}
