/*
 * Copyright © 2014-2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.services.http.handlers;

import co.cask.cdap.AppWithMultipleSchedules;
import co.cask.cdap.AppWithSchedule;
import co.cask.cdap.AppWithServices;
import co.cask.cdap.AppWithWorker;
import co.cask.cdap.AppWithWorkflow;
import co.cask.cdap.DummyAppWithTrackingTable;
import co.cask.cdap.SleepingWorkflowApp;
import co.cask.cdap.WordCountApp;
import co.cask.cdap.api.Config;
import co.cask.cdap.api.ProgramStatus;
import co.cask.cdap.api.artifact.ArtifactSummary;
import co.cask.cdap.api.schedule.SchedulableProgramType;
import co.cask.cdap.api.service.ServiceSpecification;
import co.cask.cdap.api.service.http.HttpServiceHandlerSpecification;
import co.cask.cdap.api.service.http.ServiceHttpEndpoint;
import co.cask.cdap.api.workflow.ScheduleProgramInfo;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.id.Id;
import co.cask.cdap.common.queue.QueueName;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.data2.queue.ConsumerConfig;
import co.cask.cdap.data2.queue.DequeueStrategy;
import co.cask.cdap.data2.queue.QueueClientFactory;
import co.cask.cdap.data2.queue.QueueConsumer;
import co.cask.cdap.data2.queue.QueueEntry;
import co.cask.cdap.data2.queue.QueueProducer;
import co.cask.cdap.gateway.handlers.ProgramLifecycleHttpHandler;
import co.cask.cdap.internal.app.ServiceSpecificationCodec;
import co.cask.cdap.internal.app.runtime.SystemArguments;
import co.cask.cdap.internal.app.runtime.schedule.ProgramScheduleStatus;
import co.cask.cdap.internal.app.runtime.schedule.constraint.ConcurrencyConstraint;
import co.cask.cdap.internal.app.runtime.schedule.store.Schedulers;
import co.cask.cdap.internal.app.runtime.schedule.trigger.OrTrigger;
import co.cask.cdap.internal.app.runtime.schedule.trigger.PartitionTrigger;
import co.cask.cdap.internal.app.runtime.schedule.trigger.TimeTrigger;
import co.cask.cdap.internal.app.services.http.AppFabricTestBase;
import co.cask.cdap.internal.provision.MockProvisioner;
import co.cask.cdap.internal.schedule.constraint.Constraint;
import co.cask.cdap.proto.ApplicationDetail;
import co.cask.cdap.proto.BatchProgramHistory;
import co.cask.cdap.proto.Instances;
import co.cask.cdap.proto.ProgramRecord;
import co.cask.cdap.proto.ProgramRunClusterStatus;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.ProtoConstraint;
import co.cask.cdap.proto.ProtoTrigger;
import co.cask.cdap.proto.RunRecord;
import co.cask.cdap.proto.ScheduleDetail;
import co.cask.cdap.proto.ServiceInstances;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProfileId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.profile.Profile;
import co.cask.cdap.test.SlowTests;
import co.cask.cdap.test.XSlowTests;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpResponse;
import com.google.common.base.Charsets;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.tephra.TransactionAware;
import org.apache.tephra.TransactionExecutorFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Tests for {@link ProgramLifecycleHttpHandler}
 */
public class ProgramLifecycleHttpHandlerTest extends AppFabricTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(ProgramLifecycleHttpHandlerTest.class);

  private static final Gson GSON = new GsonBuilder()
    .create();
  private static final Type LIST_OF_JSONOBJECT_TYPE = new TypeToken<List<JsonObject>>() { }.getType();
  private static final Type LIST_OF_RUN_RECORD = new TypeToken<List<RunRecord>>() { }.getType();

  private static final String WORDCOUNT_APP_NAME = "WordCountApp";
  private static final String WORDCOUNT_FLOW_NAME = "WordCountFlow";
  private static final String WORDCOUNT_MAPREDUCE_NAME = "VoidMapReduceJob";
  private static final String WORDCOUNT_FLOWLET_NAME = "StreamSource";
  private static final String DUMMY_APP_ID = "dummy";
  private static final String DUMMY_MR_NAME = "dummy-batch";
  private static final String SLEEP_WORKFLOW_APP_ID = "SleepWorkflowApp";
  private static final String SLEEP_WORKFLOW_NAME = "SleepWorkflow";
  private static final String APP_WITH_SERVICES_APP_ID = "AppWithServices";
  private static final String APP_WITH_SERVICES_SERVICE_NAME = "NoOpService";
  private static final String APP_WITH_WORKFLOW_APP_ID = "AppWithWorkflow";
  private static final String APP_WITH_WORKFLOW_WORKFLOW_NAME = "SampleWorkflow";

  private static final String EMPTY_ARRAY_JSON = "[]";
  private static final String STOPPED = "STOPPED";
  private static final String RUNNING = "RUNNING";

  @Category(XSlowTests.class)
  @Test
  public void testProgramStartStopStatus() throws Exception {
    // deploy, check the status
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    Id.Flow wordcountFlow1 = Id.Flow.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME);
    Id.Flow wordcountFlow2 = Id.Flow.from(TEST_NAMESPACE2, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME);

    // flow is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(wordcountFlow1));

    // start flow in the wrong namespace and verify that it does not start
    startProgram(wordcountFlow2, 404);
    Assert.assertEquals(STOPPED, getProgramStatus(wordcountFlow1));

    // start a flow and check the status
    startProgram(wordcountFlow1);
    waitState(wordcountFlow1, RUNNING);

    // stop the flow and check the status
    stopProgram(wordcountFlow1);
    waitState(wordcountFlow1, STOPPED);

    // deploy another app in a different namespace and verify
    deploy(DummyAppWithTrackingTable.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    Id.Program dummyMR1 = Id.Program.from(TEST_NAMESPACE1, DUMMY_APP_ID, ProgramType.MAPREDUCE, DUMMY_MR_NAME);
    Id.Program dummyMR2 = Id.Program.from(TEST_NAMESPACE2, DUMMY_APP_ID, ProgramType.MAPREDUCE, DUMMY_MR_NAME);

    // mapreduce is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(dummyMR2));

    // start mapreduce in the wrong namespace and verify it does not start
    startProgram(dummyMR1, 404);
    Assert.assertEquals(STOPPED, getProgramStatus(dummyMR2));

    // start map-reduce and verify status
    startProgram(dummyMR2);
    waitState(dummyMR2, RUNNING);

    // stop the mapreduce program and check the status
    stopProgram(dummyMR2);
    waitState(dummyMR2, STOPPED);

    // start multiple runs of the map-reduce program
    startProgram(dummyMR2);
    startProgram(dummyMR2);
    verifyProgramRuns(dummyMR2, ProgramRunStatus.RUNNING, 1);

    // stop all runs of the map-reduce program
    stopProgram(dummyMR2, 200);
    waitState(dummyMR2, STOPPED);

    // deploy an app containing a workflow
    deploy(SleepingWorkflowApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    Id.Program sleepWorkflow1 =
      Id.Program.from(TEST_NAMESPACE1, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);
    Id.Program sleepWorkflow2 =
      Id.Program.from(TEST_NAMESPACE2, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);

    // workflow is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(sleepWorkflow2));

    // start workflow in the wrong namespace and verify that it does not start
    startProgram(sleepWorkflow1, 404);
    Assert.assertEquals(STOPPED, getProgramStatus(sleepWorkflow2));

    // start workflow and check status
    startProgram(sleepWorkflow2);
    waitState(sleepWorkflow2, RUNNING);

    // workflow will stop itself
    waitState(sleepWorkflow2, STOPPED);

    // start multiple runs of the workflow
    startProgram(sleepWorkflow2, ImmutableMap.of("sleep.ms", "5000"));
    startProgram(sleepWorkflow2, ImmutableMap.of("sleep.ms", "5000"));
    verifyProgramRuns(sleepWorkflow2, ProgramRunStatus.RUNNING, 1);

    List<RunRecord> runs = getProgramRuns(sleepWorkflow2, ProgramRunStatus.RUNNING);
    Assert.assertEquals(2, runs.size());
    stopProgram(sleepWorkflow2, runs.get(0).getPid(), 200);
    stopProgram(sleepWorkflow2, runs.get(1).getPid(), 200);
    waitState(sleepWorkflow2, STOPPED);

    // verify batch runs endpoint
    List<ProgramId> programs = ImmutableList.of(sleepWorkflow2.toEntityId(), dummyMR2.toEntityId(),
                                                wordcountFlow2.toEntityId());
    List<BatchProgramHistory> batchRuns = getProgramRuns(new NamespaceId(TEST_NAMESPACE2), programs);
    BatchProgramHistory sleepRun = batchRuns.get(0);
    BatchProgramHistory dummyMR2Run = batchRuns.get(1);
    BatchProgramHistory wordcountFlow2Run = batchRuns.get(2);

    // verify results come back in order
    Assert.assertEquals(sleepWorkflow2.getId(), sleepRun.getProgramId());
    Assert.assertEquals(dummyMR2.getId(), dummyMR2Run.getProgramId());
    Assert.assertEquals(wordcountFlow2.getId(), wordcountFlow2Run.getProgramId());

    // verify status. Wordcount was never deployed in NS2 and should not exist
    Assert.assertEquals(200, sleepRun.getStatusCode());
    Assert.assertEquals(200, dummyMR2Run.getStatusCode());
    Assert.assertEquals(404, wordcountFlow2Run.getStatusCode());

    // verify the run record is correct
    RunRecord runRecord = getProgramRuns(sleepWorkflow2, ProgramRunStatus.ALL).iterator().next();
    Assert.assertEquals(runRecord.getPid(), sleepRun.getRuns().iterator().next().getPid());

    runRecord = getProgramRuns(dummyMR2, ProgramRunStatus.ALL).iterator().next();
    Assert.assertEquals(runRecord.getPid(), dummyMR2Run.getRuns().iterator().next().getPid());

    Assert.assertTrue(wordcountFlow2Run.getRuns().isEmpty());

    // cleanup
    HttpResponse response = doDelete(getVersionedAPIPath("apps/",
                                                         Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
    Assert.assertEquals(200, response.getResponseCode());
    response = doDelete(getVersionedAPIPath("apps/", Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2));
    Assert.assertEquals(200, response.getResponseCode());
  }

  @Test
  public void testVersionedProgramStartStopStatus() throws Exception {
    Id.Artifact wordCountArtifactId = Id.Artifact.from(Id.Namespace.DEFAULT, "wordcountapp", VERSION1);
    addAppArtifact(wordCountArtifactId, WordCountApp.class);
    AppRequest<? extends Config> wordCountRequest = new AppRequest<>(
      new ArtifactSummary(wordCountArtifactId.getName(), wordCountArtifactId.getVersion().getVersion()));

    ApplicationId wordCountApp1 = NamespaceId.DEFAULT.app("WordCountApp", VERSION1);
    ProgramId wordcountFlow1 = wordCountApp1.program(ProgramType.FLOW, "WordCountFlow");

    Id.Application wordCountAppDefault = Id.Application.fromEntityId(wordCountApp1);
    Id.Program wordcountFlowDefault = Id.Program.fromEntityId(wordcountFlow1);

    ApplicationId wordCountApp2 = NamespaceId.DEFAULT.app("WordCountApp", VERSION2);
    ProgramId wordcountFlow2 = wordCountApp2.program(ProgramType.FLOW, "WordCountFlow");

    // Start wordCountApp1
    Assert.assertEquals(200, deploy(wordCountApp1, wordCountRequest).getResponseCode());

    // Start wordCountApp1 with default version
    Assert.assertEquals(200, deploy(wordCountAppDefault, wordCountRequest).getResponseCode());

    // flow is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(wordcountFlow1));
    // start flow
    startProgram(wordcountFlow1, 200);
    waitState(wordcountFlow1, RUNNING);
    // same flow cannot be run concurrently in the same app version
    startProgram(wordcountFlow1, 409);

    // start flow in a wrong namespace
    startProgram(new NamespaceId(TEST_NAMESPACE1)
                            .app(wordcountFlow1.getApplication(), wordcountFlow1.getVersion())
                            .program(wordcountFlow1.getType(), wordcountFlow1.getProgram()), 404);

    // Start the second version of the app
    Assert.assertEquals(200, deploy(wordCountApp2, wordCountRequest).getResponseCode());

    // same flow cannot be run concurrently in multiple versions of the same app
    startProgram(wordcountFlow2, 409);
    startProgram(wordcountFlowDefault, 409);

    stopProgram(wordcountFlow1, null, 200, null);
    waitState(wordcountFlow1, STOPPED);

    // wordcountFlow2 can be run after wordcountFlow1 is stopped
    startProgram(wordcountFlow2, 200);
    waitState(wordcountFlow2, RUNNING);
    stopProgram(wordcountFlow2, null, 200, null);
    waitState(wordcountFlow2, STOPPED);

    ProgramId wordFrequencyService1 = wordCountApp1.program(ProgramType.SERVICE, "WordFrequencyService");
    ProgramId wordFrequencyService2 = wordCountApp2.program(ProgramType.SERVICE, "WordFrequencyService");
    Id.Program wordFrequencyServiceDefault = Id.Program.fromEntityId(wordFrequencyService1);
    // service is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(wordFrequencyService1));
    // start service
    startProgram(wordFrequencyService1, 200);
    waitState(wordFrequencyService1, RUNNING);
    // wordFrequencyService2 is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(wordFrequencyService2));
    // start service in version2
    startProgram(wordFrequencyService2, 200);
    waitState(wordFrequencyService2, RUNNING);
    // wordFrequencyServiceDefault is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(wordFrequencyServiceDefault));
    // start service in default version
    startProgram(wordFrequencyServiceDefault, 200);
    waitState(wordFrequencyServiceDefault, RUNNING);
    // same service cannot be run concurrently in the same app version
    startProgram(wordFrequencyService1, 409);
    stopProgram(wordFrequencyService1, null, 200, null);
    waitState(wordFrequencyService1, STOPPED);
    Assert.assertEquals(STOPPED, getProgramStatus(wordFrequencyService1));
    // wordFrequencyService1 can be run after wordFrequencyService1 is stopped
    startProgram(wordFrequencyService1, 200);
    waitState(wordFrequencyService1, RUNNING);

    stopProgram(wordFrequencyService1, null, 200, null);
    stopProgram(wordFrequencyService2, null, 200, null);
    stopProgram(wordFrequencyServiceDefault, null, 200, null);
    waitState(wordFrequencyService1, STOPPED);
    waitState(wordFrequencyService2, STOPPED);
    waitState(wordFrequencyServiceDefault, STOPPED);

    Id.Artifact sleepWorkflowArtifactId = Id.Artifact.from(Id.Namespace.DEFAULT, "sleepworkflowapp", VERSION1);
    addAppArtifact(sleepWorkflowArtifactId, SleepingWorkflowApp.class);
    AppRequest<? extends Config> sleepWorkflowRequest = new AppRequest<>(
      new ArtifactSummary(sleepWorkflowArtifactId.getName(), sleepWorkflowArtifactId.getVersion().getVersion()));

    ApplicationId sleepWorkflowApp1 = new ApplicationId(Id.Namespace.DEFAULT.getId(), "SleepingWorkflowApp", VERSION1);
    final ProgramId sleepWorkflow1 = sleepWorkflowApp1.program(ProgramType.WORKFLOW, "SleepWorkflow");

    ApplicationId sleepWorkflowApp2 = new ApplicationId(Id.Namespace.DEFAULT.getId(), "SleepingWorkflowApp", VERSION2);
    final ProgramId sleepWorkflow2 = sleepWorkflowApp2.program(ProgramType.WORKFLOW, "SleepWorkflow");

    // Start wordCountApp1
    Assert.assertEquals(200, deploy(sleepWorkflowApp1, sleepWorkflowRequest).getResponseCode());
    // workflow is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(sleepWorkflow1));
    // start workflow in a wrong version
    startProgram(sleepWorkflow2, 404);
    // Start wordCountApp2
    Assert.assertEquals(200, deploy(sleepWorkflowApp2, sleepWorkflowRequest).getResponseCode());

    // start multiple workflow simultaneously with a long sleep time
    Map<String, String> args = Collections.singletonMap("sleep.ms", "120000");
    startProgram(sleepWorkflow1, args, 200);
    startProgram(sleepWorkflow2, args, 200);
    startProgram(sleepWorkflow1, args, 200);
    startProgram(sleepWorkflow2, args, 200);

    // Make sure they are all running. Otherwise on slow machine, it's possible that the TMS states hasn't
    // been consumed and write to the store before we stop the program and query for STOPPED state.
    Tasks.waitFor(2, () -> getProgramRuns(sleepWorkflow1, ProgramRunStatus.RUNNING).size(),
                  10, TimeUnit.SECONDS, 200, TimeUnit.MILLISECONDS);
    Tasks.waitFor(2, () -> getProgramRuns(sleepWorkflow2, ProgramRunStatus.RUNNING).size(),
                  10, TimeUnit.SECONDS, 200, TimeUnit.MILLISECONDS);

    // stop multiple workflow simultaneously
    // This will stop all concurrent runs of the Workflow version 1.0.0
    stopProgram(sleepWorkflow1, null, 200, null);
    // This will stop all concurrent runs of the Workflow version 2.0.0
    stopProgram(sleepWorkflow2, null, 200, null);

    // Wait until all are stopped
    waitState(sleepWorkflow1, STOPPED);
    waitState(sleepWorkflow2, STOPPED);

    //Test for runtime args
    testVersionedProgramRuntimeArgs(sleepWorkflow1);

    // cleanup
    deleteApp(wordCountApp1, 200);
    deleteApp(wordCountApp2, 200);
    deleteApp(wordCountAppDefault, 200);
    deleteApp(sleepWorkflowApp1, 200);
    deleteApp(sleepWorkflowApp2, 200);
  }

  @Category(XSlowTests.class)
  @Test
  public void testProgramStartStopStatusErrors() throws Exception {
    // deploy, check the status
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // start unknown program
    startProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, "noexist"), 404);
    // start program in unknonw app
    startProgram(Id.Program.from(TEST_NAMESPACE1, "noexist", ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);
    // start program in unknown namespace
    startProgram(Id.Program.from("noexist", WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);

    // debug unknown program
    debugProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, "noexist"), 404);
    // debug a program that does not support it
    debugProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.MAPREDUCE, WORDCOUNT_MAPREDUCE_NAME),
                 501); // not implemented

    // status for unknown program
    programStatus(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, "noexist"), 404);
    // status for program in unknonw app
    programStatus(Id.Program.from(TEST_NAMESPACE1, "noexist", ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);
    // status for program in unknown namespace
    programStatus(Id.Program.from("noexist", WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);

    // stop unknown program
    stopProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, "noexist"), 404);
    // stop program in unknonw app
    stopProgram(Id.Program.from(TEST_NAMESPACE1, "noexist", ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);
    // stop program in unknown namespace
    stopProgram(Id.Program.from("noexist", WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 404);
    // stop program that is not running
    stopProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 400);
    // stop run of a program with ill-formed run id
    stopProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME),
                "norunid", 400);

    // start program twice
    startProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME));
    verifyProgramRuns(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME),
                      ProgramRunStatus.RUNNING);

    startProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME),
                 409); // conflict

    // get run records for later use
    List<RunRecord> runs = getProgramRuns(
      Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME),
                      ProgramRunStatus.RUNNING);
    Assert.assertEquals(1, runs.size());
    String runId = runs.get(0).getPid();

    // stop program
    stopProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), 200);
    waitState(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME), "STOPPED");

    // get run records again, should be empty now
    Tasks.waitFor(true, () -> {
      Id.Program id = Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME);
      return getProgramRuns(id, ProgramRunStatus.RUNNING).isEmpty();
    }, 10, TimeUnit.SECONDS);

    // stop run of the program that is not running
    stopProgram(Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME),
                runId, 400); // active run not found

    // cleanup
    HttpResponse response = doDelete(getVersionedAPIPath("apps/",
                                                         Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
    Assert.assertEquals(200, response.getResponseCode());
  }
    /**
     * Tests history of a flow.
     */
  @Test
  public void testFlowHistory() throws Exception {
    testHistory(WordCountApp.class,
                Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME));
  }

  /**
   * Tests history of a mapreduce.
   */
  @Category(XSlowTests.class)
  @Test
  public void testMapreduceHistory() throws Exception {
    testHistory(DummyAppWithTrackingTable.class,
                Id.Program.from(TEST_NAMESPACE2, DUMMY_APP_ID, ProgramType.MAPREDUCE, DUMMY_MR_NAME));
  }

  /**
   * Tests history of a non existing program
   */
  @Test
  public void testNonExistingProgramHistory() throws Exception {
    deploy(DummyAppWithTrackingTable.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);
    int historyStatus = doPost(getVersionedAPIPath("apps/" + DUMMY_APP_ID + ProgramType.MAPREDUCE + "/NonExisting",
                                                   Constants.Gateway.API_VERSION_3_TOKEN,
                                                   TEST_NAMESPACE2)).getResponseCode();
    int deleteStatus = doDelete(getVersionedAPIPath("apps/" + DUMMY_APP_ID, Constants.Gateway.API_VERSION_3_TOKEN,
                                                    TEST_NAMESPACE2)).getResponseCode();
    Assert.assertTrue("Unexpected history status " + historyStatus + " and/or deleteStatus " + deleteStatus,
                      historyStatus == 404 && deleteStatus == 200);
  }

  /**
   * Tests getting a non-existent namespace
   */
  @Test
  public void testNonExistentNamespace() throws Exception {
    String[] endpoints = {"flows", "spark", "services", "workers", "mapreduce", "workflows"};

    for (String endpoint : endpoints) {
      HttpResponse response = doGet("/v3/namespaces/default/" + endpoint);
      Assert.assertEquals(200, response.getResponseCode());
      response = doGet("/v3/namespaces/garbage/" + endpoint);
      Assert.assertEquals(404, response.getResponseCode());
    }
  }

  /**
   * Tests history of a workflow.
   */
  @Category(SlowTests.class)
  @Test
  public void testWorkflowHistory() throws Exception {
    try {
      deploy(SleepingWorkflowApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
      Id.Program sleepWorkflow1 =
        Id.Program.from(TEST_NAMESPACE1, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);

      // first run
      startProgram(sleepWorkflow1);
      int numWorkflowRunsStopped = getProgramRuns(sleepWorkflow1, ProgramRunStatus.COMPLETED).size();
      // workflow stops by itself after actions are done
      waitState(sleepWorkflow1, STOPPED);
      verifyProgramRuns(sleepWorkflow1, ProgramRunStatus.COMPLETED, numWorkflowRunsStopped);

      // second run
      startProgram(sleepWorkflow1);
      // workflow stops by itself after actions are done
      waitState(sleepWorkflow1, STOPPED);
      verifyProgramRuns(sleepWorkflow1, ProgramRunStatus.COMPLETED, numWorkflowRunsStopped + 1);

      historyStatusWithRetry(sleepWorkflow1.toEntityId(), ProgramRunStatus.COMPLETED, 2);
    } finally {
      Assert.assertEquals(200, doDelete(getVersionedAPIPath("apps/" + SLEEP_WORKFLOW_APP_ID, Constants.Gateway
        .API_VERSION_3_TOKEN, TEST_NAMESPACE1)).getResponseCode());
    }
  }

  @Test
  public void testProvisionerFailureStateAndMetrics() throws Exception {
    // test that metrics and program state are correct after a program run fails due to provisioning failures
    try {
      deploy(SleepingWorkflowApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
      Id.Program workflowId =
        Id.Program.from(TEST_NAMESPACE1, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);

      // get number of failed runs and metrics
      long failMetricCount = getProfileTotalMetric(Constants.Metrics.Program.PROGRAM_FAILED_RUNS);
      int numFailedRuns = getProgramRuns(workflowId, ProgramRunStatus.FAILED).size();

      // this tells the provisioner to fail the create call
      Map<String, String> args = new HashMap<>();
      args.put(SystemArguments.PROFILE_PROPERTIES_PREFIX + MockProvisioner.FAIL_CREATE, Boolean.TRUE.toString());
      startProgram(workflowId, args);

      Tasks.waitFor(numFailedRuns + 1, () -> getProgramRuns(workflowId, ProgramRunStatus.FAILED).size(),
                    5, TimeUnit.MINUTES);

      // check program state and cluster state
      RunRecord runRecord = getProgramRuns(workflowId, ProgramRunStatus.FAILED).iterator().next();
      Assert.assertEquals(ProgramRunClusterStatus.DEPROVISIONED, runRecord.getCluster().getStatus());

      // check profile metrics. Though not guaranteed to be set when the program is done, it should be set soon after.
      Tasks.waitFor(failMetricCount + 1, () -> getProfileTotalMetric(Constants.Metrics.Program.PROGRAM_FAILED_RUNS),
                    60, TimeUnit.SECONDS);
    } finally {
      HttpResponse deleteResponse = doDelete(
        getVersionedAPIPath("apps/" + SLEEP_WORKFLOW_APP_ID, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
      Assert.assertEquals(200, deleteResponse.getResponseCode());
    }
  }

  @Test
  public void testStopProgramWhilePending() throws Exception {
    try {
      deploy(SleepingWorkflowApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
      Id.Program workflowId =
        Id.Program.from(TEST_NAMESPACE1, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);

      int numKilledRuns = getProgramRuns(workflowId, ProgramRunStatus.KILLED).size();

      // this tells the provisioner to wait for 60s before trying to create the cluster for the run
      Map<String, String> args = new HashMap<>();
      args.put(SystemArguments.PROFILE_PROPERTIES_PREFIX + MockProvisioner.WAIT_CREATE_MS, Integer.toString(120000));
      startProgram(workflowId, args);

      // should be safe to wait for starting since the provisioner is configure to sleep while creating a cluster
      waitState(workflowId, co.cask.cdap.proto.ProgramStatus.STARTING.name());

      stopProgram(workflowId);
      waitState(workflowId, STOPPED);

      verifyProgramRuns(workflowId, ProgramRunStatus.KILLED, numKilledRuns);
    } finally {
      HttpResponse deleteResponse = doDelete(
        getVersionedAPIPath("apps/" + SLEEP_WORKFLOW_APP_ID, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
      Assert.assertEquals(200, deleteResponse.getResponseCode());
    }
  }

  @Test
  public void testStopProgramRunWhilePending() throws Exception {
    try {
      deploy(SleepingWorkflowApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
      Id.Program workflowId =
        Id.Program.from(TEST_NAMESPACE1, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW, SLEEP_WORKFLOW_NAME);

      int numKilledRuns = getProgramRuns(workflowId, ProgramRunStatus.KILLED).size();

      // this tells the provisioner to wait for 60s before trying to create the cluster for the run
      Map<String, String> args = new HashMap<>();
      args.put(SystemArguments.PROFILE_PROPERTIES_PREFIX + MockProvisioner.WAIT_CREATE_MS, Integer.toString(120000));
      startProgram(workflowId, args);

      // should be safe to wait for starting since the provisioner is configure to sleep while creating a cluster
      waitState(workflowId, co.cask.cdap.proto.ProgramStatus.STARTING.name());
      List<RunRecord> runRecords = getProgramRuns(workflowId, ProgramRunStatus.PENDING);
      Assert.assertEquals(1, runRecords.size());
      String runId = runRecords.iterator().next().getPid();

      stopProgram(workflowId, runId, 200);
      waitState(workflowId, STOPPED);

      verifyProgramRuns(workflowId, ProgramRunStatus.KILLED, numKilledRuns);
    } finally {
      HttpResponse deleteResponse = doDelete(
        getVersionedAPIPath("apps/" + SLEEP_WORKFLOW_APP_ID, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
      Assert.assertEquals(200, deleteResponse.getResponseCode());
    }
  }

  @Test
  public void testFlowRuntimeArgs() throws Exception {
    testRuntimeArgs(WordCountApp.class, TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW.getCategoryName(),
                    WORDCOUNT_FLOW_NAME);
  }

  @Test
  public void testWorkflowRuntimeArgs() throws Exception {
    testRuntimeArgs(SleepingWorkflowApp.class, TEST_NAMESPACE2, SLEEP_WORKFLOW_APP_ID, ProgramType.WORKFLOW
      .getCategoryName(), SLEEP_WORKFLOW_NAME);
  }

  @Test
  public void testMapreduceRuntimeArgs() throws Exception {
    testRuntimeArgs(DummyAppWithTrackingTable.class, TEST_NAMESPACE1, DUMMY_APP_ID, ProgramType.MAPREDUCE
      .getCategoryName(), DUMMY_MR_NAME);
  }

  @Test
  public void testBatchStatus() throws Exception {
    final String statusUrl1 = getVersionedAPIPath("status", Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    final String statusUrl2 = getVersionedAPIPath("status", Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // invalid json must return 400
    Assert.assertEquals(400, doPost(statusUrl1, "").getResponseCode());
    Assert.assertEquals(400, doPost(statusUrl2, "").getResponseCode());
    // empty array is valid args
    Assert.assertEquals(200, doPost(statusUrl1, EMPTY_ARRAY_JSON).getResponseCode());
    Assert.assertEquals(200, doPost(statusUrl2, EMPTY_ARRAY_JSON).getResponseCode());

    // deploy an app in namespace1
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    // deploy another app in namespace2
    deploy(AppWithServices.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // data requires appId, programId, and programType. Test missing fields/invalid programType
    Assert.assertEquals(400, doPost(statusUrl1, "[{'appId':'WordCountApp', 'programType':'Flow'}]")
      .getResponseCode());
    Assert.assertEquals(400, doPost(statusUrl1, "[{'appId':'WordCountApp', 'programId':'WordCountFlow'}]")
      .getResponseCode());
    Assert.assertEquals(400, doPost(statusUrl1, "[{'programType':'Flow', 'programId':'WordCountFlow'}, {'appId':" +
      "'AppWithServices', 'programType': 'service', 'programId': 'NoOpService'}]").getResponseCode());
    Assert.assertEquals(400,
                        doPost(statusUrl1, "[{'appId':'WordCountApp', 'programType':'Flow' " +
                          "'programId':'WordCountFlow'}]").getResponseCode());
    // Test missing app, programType, etc
    List<JsonObject> returnedBody = readResponse(doPost(statusUrl1, "[{'appId':'NotExist', 'programType':'Flow', " +
      "'programId':'WordCountFlow'}]"), LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(new NotFoundException(new ApplicationId("testnamespace1", "NotExist")).getMessage(),
                        returnedBody.get(0).get("error").getAsString());
    returnedBody = readResponse(
      doPost(statusUrl1, "[{'appId':'WordCountApp', 'programType':'flow', 'programId':'NotExist'}," +
        "{'appId':'WordCountApp', 'programType':'flow', 'programId':'WordCountFlow'}]"), LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(new NotFoundException(new ProgramId("testnamespace1", "WordCountApp", ProgramType.FLOW,
                                                            "NotExist")).getMessage(),
                        returnedBody.get(0).get("error").getAsString());
    Assert.assertEquals(
      new NotFoundException(
        new ProgramId("testnamespace1", "WordCountApp", ProgramType.FLOW, "NotExist")).getMessage(),
      returnedBody.get(0).get("error").getAsString());
    // The programType should be consistent. Second object should have proper status
    Assert.assertEquals("Flow", returnedBody.get(1).get("programType").getAsString());
    Assert.assertEquals(STOPPED, returnedBody.get(1).get("status").getAsString());


    // test valid cases for namespace1
    HttpResponse response = doPost(statusUrl1,
                                   "[{'appId':'WordCountApp', 'programType':'Flow', 'programId':'WordCountFlow'}," +
                                     "{'appId': 'WordCountApp', 'programType': 'Service', 'programId': " +
                                     "'WordFrequencyService'}]");
    verifyInitialBatchStatusOutput(response);

    // test valid cases for namespace2
    response = doPost(statusUrl2, "[{'appId': 'AppWithServices', 'programType': 'Service', 'programId': " +
      "'NoOpService'}]");
    verifyInitialBatchStatusOutput(response);


    // start the flow
    Id.Program wordcountFlow1 =
      Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME);
    Id.Program service2 = Id.Program.from(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID,
                                          ProgramType.SERVICE, APP_WITH_SERVICES_SERVICE_NAME);
    startProgram(wordcountFlow1);
    waitState(wordcountFlow1, RUNNING);

    // test status API after starting the flow
    response = doPost(statusUrl1, "[{'appId':'WordCountApp', 'programType':'Flow', 'programId':'WordCountFlow'}," +
      "{'appId': 'WordCountApp', 'programType': 'Mapreduce', 'programId': 'VoidMapReduceJob'}]");
    Assert.assertEquals(200, response.getResponseCode());
    returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(ProgramRunStatus.RUNNING.toString(), returnedBody.get(0).get("status").getAsString());
    Assert.assertEquals(STOPPED, returnedBody.get(1).get("status").getAsString());

    // start the service
    startProgram(service2);
    verifyProgramRuns(service2, ProgramRunStatus.RUNNING);
    // test status API after starting the service
    response = doPost(statusUrl2, "[{'appId': 'AppWithServices', 'programType': 'Service', 'programId': " +
      "'NoOpService'}]");
    Assert.assertEquals(200, response.getResponseCode());
    returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(ProgramRunStatus.RUNNING.toString(), returnedBody.get(0).get("status").getAsString());

    // stop the flow
    stopProgram(wordcountFlow1);
    waitState(wordcountFlow1, STOPPED);

    // stop the service
    stopProgram(service2);
    waitState(service2, STOPPED);

    // try posting a status request with namespace2 for apps in namespace1
    response = doPost(statusUrl2, "[{'appId':'WordCountApp', 'programType':'Flow', 'programId':'WordCountFlow'}," +
      "{'appId': 'WordCountApp', 'programType': 'Service', 'programId': 'WordFrequencyService'}," +
      "{'appId': 'WordCountApp', 'programType': 'Mapreduce', 'programId': 'VoidMapReduceJob'}]");
    returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(new NotFoundException(new ApplicationId("testnamespace2", "WordCountApp")).getMessage(),
                        returnedBody.get(0).get("error").getAsString());
    Assert.assertEquals(new NotFoundException(new ApplicationId("testnamespace2", "WordCountApp")).getMessage(),
                        returnedBody.get(1).get("error").getAsString());
    Assert.assertEquals(new NotFoundException(new ApplicationId("testnamespace2", "WordCountApp")).getMessage(),
                        returnedBody.get(2).get("error").getAsString());
  }

  @Test
  public void testBatchInstances() throws Exception {
    final String instancesUrl1 = getVersionedAPIPath("instances", Constants.Gateway.API_VERSION_3_TOKEN,
                                                     TEST_NAMESPACE1);
    final String instancesUrl2 = getVersionedAPIPath("instances", Constants.Gateway.API_VERSION_3_TOKEN,
                                                     TEST_NAMESPACE2);

    Assert.assertEquals(400, doPost(instancesUrl1, "").getResponseCode());
    Assert.assertEquals(400, doPost(instancesUrl2, "").getResponseCode());

    // empty array is valid args
    Assert.assertEquals(200, doPost(instancesUrl1, "[]").getResponseCode());
    Assert.assertEquals(200, doPost(instancesUrl2, "[]").getResponseCode());

    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    deploy(AppWithServices.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // data requires appId, programId, and programType. Test missing fields/invalid programType
    // TODO: These json strings should be replaced with JsonObjects so it becomes easier to refactor in future
    Assert.assertEquals(400, doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programType':'Flow'}]")
      .getResponseCode());
    Assert.assertEquals(400, doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programId':'WordCountFlow'}]")
      .getResponseCode());
    Assert.assertEquals(400, doPost(instancesUrl1, "[{'programType':'Flow', 'programId':'WordCountFlow'}," +
      "{'appId': 'WordCountApp', 'programType': 'Mapreduce', 'programId': 'WordFrequency'}]")
      .getResponseCode());
    Assert.assertEquals(400, doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programType':'NotExist', " +
      "'programId':'WordCountFlow'}]").getResponseCode());

    // Test malformed json
    Assert.assertEquals(400,
                        doPost(instancesUrl1,
                               "[{'appId':'WordCountApp', 'programType':'Flow' 'programId':'WordCountFlow'}]")
                          .getResponseCode());

    // Test missing app, programType, etc
    List<JsonObject> returnedBody = readResponse(
      doPost(instancesUrl1, "[{'appId':'NotExist', 'programType':'Flow', 'programId':'WordCountFlow'}]"),
      LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(404, returnedBody.get(0).get("statusCode").getAsInt());
    returnedBody = readResponse(
      doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programType':'flow', 'programId':'WordCountFlow', " +
        "'runnableId': " +
        "NotExist'}]"), LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(404, returnedBody.get(0).get("statusCode").getAsInt());


    // valid test in namespace1
    HttpResponse response = doPost(instancesUrl1,
                                   "[{'appId':'WordCountApp', 'programType':'Flow', 'programId':'WordCountFlow', " +
                                     "'runnableId': 'StreamSource'}," +
                                     "{'appId': 'WordCountApp', 'programType': 'Service', 'programId': " +
                                     "'WordFrequencyService', 'runnableId': 'WordFrequencyService'}]");

    verifyInitialBatchInstanceOutput(response);

    // valid test in namespace2
    response = doPost(instancesUrl2,
                      "[{'appId': 'AppWithServices', 'programType':'Service', 'programId':'NoOpService', " +
                        "'runnableId':'NoOpService'}]");
    verifyInitialBatchInstanceOutput(response);


    // start the flow
    Id.Program wordcountFlow1 =
      Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME);
    startProgram(wordcountFlow1);
    waitState(wordcountFlow1, RUNNING);

    response = doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programType':'Flow', 'programId':'WordCountFlow'," +
      "'runnableId': 'StreamSource'}]");
    returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(1, returnedBody.get(0).get("provisioned").getAsInt());

    // start the service
    Id.Program service2 = Id.Program.from(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID,
                                          ProgramType.SERVICE, APP_WITH_SERVICES_SERVICE_NAME);
    startProgram(service2);
    waitState(service2, RUNNING);

    response = doPost(instancesUrl2, "[{'appId':'AppWithServices', 'programType':'Service','programId':'NoOpService'," +
      " 'runnableId':'NoOpService'}]");
    Assert.assertEquals(200, response.getResponseCode());
    returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    Assert.assertEquals(1, returnedBody.get(0).get("provisioned").getAsInt());

    // request for 2 more instances of the flowlet
    Assert.assertEquals(200, requestFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME,
                                                     WORDCOUNT_FLOWLET_NAME, 2));
    returnedBody = readResponse(doPost(instancesUrl1, "[{'appId':'WordCountApp', 'programType':'Flow'," +
      "'programId':'WordCountFlow', 'runnableId': 'StreamSource'}]"), LIST_OF_JSONOBJECT_TYPE);
    // verify that 2 more instances were requested
    Assert.assertEquals(2, returnedBody.get(0).get("requested").getAsInt());


    stopProgram(wordcountFlow1);
    stopProgram(service2);
    waitState(wordcountFlow1, STOPPED);
    waitState(service2, STOPPED);
  }

  /**
   * Tests for program list calls
   */
  @Test
  public void testProgramList() throws Exception {
    // test initial state
    testListInitialState(TEST_NAMESPACE1, ProgramType.FLOW);
    testListInitialState(TEST_NAMESPACE2, ProgramType.MAPREDUCE);
    testListInitialState(TEST_NAMESPACE1, ProgramType.WORKFLOW);
    testListInitialState(TEST_NAMESPACE2, ProgramType.SPARK);
    testListInitialState(TEST_NAMESPACE1, ProgramType.SERVICE);

    // deploy WordCountApp in namespace1 and verify
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // deploy AppWithServices in namespace2 and verify
    deploy(AppWithServices.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // verify list by namespace
    verifyProgramList(TEST_NAMESPACE1, ProgramType.FLOW, 1);
    verifyProgramList(TEST_NAMESPACE1, ProgramType.MAPREDUCE, 1);
    verifyProgramList(TEST_NAMESPACE2, ProgramType.SERVICE, 1);

    // verify list by app
    verifyProgramList(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, 1);
    verifyProgramList(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.MAPREDUCE, 1);
    verifyProgramList(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.WORKFLOW, 0);
    verifyProgramList(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID, ProgramType.SERVICE, 1);

    // verify invalid namespace
    Assert.assertEquals(404, getAppFDetailResponseCode(TEST_NAMESPACE1, APP_WITH_SERVICES_APP_ID));
    // verify invalid app
    Assert.assertEquals(404, getAppFDetailResponseCode(TEST_NAMESPACE1, "random"));
  }

  /**
   * Worker Specification tests
   */
  @Test
  public void testWorkerSpecification() throws Exception {
    // deploy AppWithWorker in namespace1 and verify
    deploy(AppWithWorker.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    verifyProgramSpecification(TEST_NAMESPACE1, AppWithWorker.NAME, ProgramType.WORKER.getCategoryName(),
                               AppWithWorker.WORKER);
    Assert.assertEquals(404, getProgramSpecificationResponseCode(TEST_NAMESPACE2, AppWithWorker.NAME,
                                                                 ProgramType.WORKER.getCategoryName(),
                                                                 AppWithWorker.WORKER));
  }

  @Test
  public void testServiceSpecification() throws Exception {
    deploy(AppWithServices.class, 200);
    HttpResponse response = doGet("/v3/namespaces/default/apps/AppWithServices/services/NoOpService");
    Assert.assertEquals(200, response.getResponseCode());

    Set<ServiceHttpEndpoint> expectedEndpoints = ImmutableSet.of(new ServiceHttpEndpoint("GET", "/ping"),
                                                                 new ServiceHttpEndpoint("POST", "/multi"),
                                                                 new ServiceHttpEndpoint("GET", "/multi"),
                                                                 new ServiceHttpEndpoint("GET", "/multi/ping"));

    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(ServiceSpecification.class, new ServiceSpecificationCodec());
    Gson gson = gsonBuilder.create();
    ServiceSpecification specification = readResponse(response, ServiceSpecification.class, gson);

    Set<ServiceHttpEndpoint> returnedEndpoints = new HashSet<>();
    for (HttpServiceHandlerSpecification httpServiceHandlerSpecification : specification.getHandlers().values()) {
      returnedEndpoints.addAll(httpServiceHandlerSpecification.getEndpoints());
    }

    Assert.assertEquals("NoOpService", specification.getName());
    Assert.assertTrue(returnedEndpoints.equals(expectedEndpoints));
  }

  /**
   * Program specification tests through appfabric apis.
   */
  @Test
  public void testProgramSpecification() throws Exception {
    // deploy WordCountApp in namespace1 and verify
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // deploy AppWithServices in namespace2 and verify
    deploy(AppWithServices.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // deploy AppWithWorkflow in namespace2 and verify
    deploy(AppWithWorkflow.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    // deploy AppWithWorker in namespace1 and verify
    deploy(AppWithWorker.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // verify program specification
    verifyProgramSpecification(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW.getCategoryName(),
                               WORDCOUNT_FLOW_NAME);
    verifyProgramSpecification(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.MAPREDUCE.getCategoryName(),
                               WORDCOUNT_MAPREDUCE_NAME);
    verifyProgramSpecification(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID, ProgramType.SERVICE.getCategoryName(),
                               APP_WITH_SERVICES_SERVICE_NAME);
    verifyProgramSpecification(TEST_NAMESPACE2, APP_WITH_WORKFLOW_APP_ID, ProgramType.WORKFLOW.getCategoryName(),
                               APP_WITH_WORKFLOW_WORKFLOW_NAME);
    verifyProgramSpecification(TEST_NAMESPACE1, AppWithWorker.NAME, ProgramType.WORKER.getCategoryName(),
                               AppWithWorker.WORKER);

    // verify invalid namespace
    Assert.assertEquals(404, getProgramSpecificationResponseCode(TEST_NAMESPACE1, APP_WITH_SERVICES_APP_ID,
                                                                 ProgramType.SERVICE.getCategoryName(),
                                                                 APP_WITH_SERVICES_SERVICE_NAME));
    // verify invalid app
    Assert.assertEquals(404, getProgramSpecificationResponseCode(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID,
                                                                 ProgramType.WORKFLOW.getCategoryName(),
                                                                 APP_WITH_WORKFLOW_WORKFLOW_NAME));
    // verify invalid program type
    Assert.assertEquals(400, getProgramSpecificationResponseCode(TEST_NAMESPACE2, APP_WITH_SERVICES_APP_ID,
                                                                 "random", APP_WITH_WORKFLOW_WORKFLOW_NAME));

    // verify invalid program type
    Assert.assertEquals(404, getProgramSpecificationResponseCode(TEST_NAMESPACE2, AppWithWorker.NAME,
                                                                 ProgramType.WORKER.getCategoryName(),
                                                                 AppWithWorker.WORKER));
  }

  @Test
  public void testFlows() throws Exception {
    // deploy WordCountApp in namespace1 and verify
    deploy(WordCountApp.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // check initial flowlet instances
    int initial = getFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME, WORDCOUNT_FLOWLET_NAME);
    Assert.assertEquals(1, initial);

    // request two more instances
    Assert.assertEquals(200, requestFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME,
                                                     WORDCOUNT_FLOWLET_NAME, 3));
    // verify
    int after = getFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME, WORDCOUNT_FLOWLET_NAME);
    Assert.assertEquals(3, after);

    // invalid namespace
    Assert.assertEquals(404, requestFlowletInstances(TEST_NAMESPACE2, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME,
                                                     WORDCOUNT_FLOWLET_NAME, 3));
    // invalid app
    Assert.assertEquals(404, requestFlowletInstances(TEST_NAMESPACE1, APP_WITH_SERVICES_APP_ID, WORDCOUNT_FLOW_NAME,
                                                     WORDCOUNT_FLOWLET_NAME, 3));
    // invalid flow
    Assert.assertEquals(404, requestFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, "random",
                                                     WORDCOUNT_FLOWLET_NAME, 3));
    // invalid flowlet
    Assert.assertEquals(404, requestFlowletInstances(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME,
                                                     "random", 3));

    // test live info
    // send invalid program type to live info
    HttpResponse response = sendLiveInfoRequest(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, "random", WORDCOUNT_FLOW_NAME);
    Assert.assertEquals(400, response.getResponseCode());

    // test valid live info
    JsonObject liveInfo = getLiveInfo(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW.getCategoryName(),
                                      WORDCOUNT_FLOW_NAME);
    Assert.assertEquals(WORDCOUNT_APP_NAME, liveInfo.get("app").getAsString());
    Assert.assertEquals(ProgramType.FLOW.getPrettyName(), liveInfo.get("type").getAsString());
    Assert.assertEquals(WORDCOUNT_FLOW_NAME, liveInfo.get("name").getAsString());

    // start flow
    Id.Program wordcountFlow1 =
      Id.Program.from(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW, WORDCOUNT_FLOW_NAME);
    startProgram(wordcountFlow1);
    waitState(wordcountFlow1, RUNNING);

    liveInfo = getLiveInfo(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, ProgramType.FLOW.getCategoryName(),
                           WORDCOUNT_FLOW_NAME);
    Assert.assertEquals(WORDCOUNT_APP_NAME, liveInfo.get("app").getAsString());
    Assert.assertEquals(ProgramType.FLOW.getPrettyName(), liveInfo.get("type").getAsString());
    Assert.assertEquals(WORDCOUNT_FLOW_NAME, liveInfo.get("name").getAsString());
    Assert.assertEquals("in-memory", liveInfo.get("runtime").getAsString());

    // should not delete queues while running
    Assert.assertEquals(403, deleteQueues(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME));
    Assert.assertEquals(403, deleteQueues(TEST_NAMESPACE1));

    // stop
    stopProgram(wordcountFlow1);
    waitState(wordcountFlow1, STOPPED);

    // delete queues
    Assert.assertEquals(200, deleteQueues(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME));
  }

  @Test
  public void testMultipleWorkflowSchedules() throws Exception {
    // Deploy the app
    NamespaceId testNamespace2 = new NamespaceId(TEST_NAMESPACE2);
    Id.Namespace idTestNamespace2 = Id.Namespace.fromEntityId(testNamespace2);
    Id.Artifact artifactId = Id.Artifact.from(idTestNamespace2, "appwithmultiplescheduledworkflows", VERSION1);
    addAppArtifact(artifactId, AppWithMultipleSchedules.class);
    AppRequest<? extends Config> appRequest = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()));
    Id.Application appDefault = new Id.Application(idTestNamespace2, AppWithMultipleSchedules.NAME);
    ApplicationId app1 = testNamespace2.app(AppWithMultipleSchedules.NAME, VERSION1);
    ApplicationId app2 = testNamespace2.app(AppWithMultipleSchedules.NAME, VERSION2);
    Assert.assertEquals(200, deploy(appDefault, appRequest).getResponseCode());
    Assert.assertEquals(200, deploy(app1, appRequest).getResponseCode());
    Assert.assertEquals(200, deploy(app2, appRequest).getResponseCode());

    // Schedule details from non-versioned API
    List<ScheduleDetail> someSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME,
                                                      AppWithMultipleSchedules.SOME_WORKFLOW);
    Assert.assertEquals(2, someSchedules.size());
    Assert.assertEquals(AppWithMultipleSchedules.SOME_WORKFLOW, someSchedules.get(0).getProgram().getProgramName());
    Assert.assertEquals(AppWithMultipleSchedules.SOME_WORKFLOW, someSchedules.get(1).getProgram().getProgramName());

    // Schedule details from non-versioned API
    List<ScheduleDetail> anotherSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME,
                                                         AppWithMultipleSchedules.ANOTHER_WORKFLOW);
    Assert.assertEquals(3, anotherSchedules.size());
    Assert.assertEquals(AppWithMultipleSchedules.ANOTHER_WORKFLOW,
                        anotherSchedules.get(0).getProgram().getProgramName());
    Assert.assertEquals(AppWithMultipleSchedules.ANOTHER_WORKFLOW,
                        anotherSchedules.get(1).getProgram().getProgramName());
    Assert.assertEquals(AppWithMultipleSchedules.ANOTHER_WORKFLOW,
                        anotherSchedules.get(2).getProgram().getProgramName());

    // Schedule details from non-versioned API filtered by Trigger type
    List<ScheduleDetail> filteredTimeSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME,
                                                              AppWithMultipleSchedules.TRIGGERED_WORKFLOW,
                                                              ProtoTrigger.Type.TIME);
    Assert.assertEquals(1, filteredTimeSchedules.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, filteredTimeSchedules);

    // Schedule details from non-versioned API filtered by Trigger type
    List<ScheduleDetail> programStatusSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME,
                                                              AppWithMultipleSchedules.TRIGGERED_WORKFLOW,
                                                              ProtoTrigger.Type.PROGRAM_STATUS);
    Assert.assertEquals(4, programStatusSchedules.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, programStatusSchedules);

    deleteApp(appDefault, 200);

    // Schedule of app1 from versioned API
    List<ScheduleDetail> someSchedules1 = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME, VERSION1,
                                                       AppWithMultipleSchedules.SOME_WORKFLOW);
    Assert.assertEquals(2, someSchedules1.size());
    assertProgramInSchedules(AppWithMultipleSchedules.SOME_WORKFLOW, someSchedules1);

    // Schedule details from versioned API filtered by Trigger type
    filteredTimeSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME, VERSION1,
                                         AppWithMultipleSchedules.TRIGGERED_WORKFLOW,
                                         ProtoTrigger.Type.TIME);
    Assert.assertEquals(1, filteredTimeSchedules.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, filteredTimeSchedules);

    // Schedule details from versioned API filtered by Trigger type
    programStatusSchedules = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME, VERSION1,
                                          AppWithMultipleSchedules.TRIGGERED_WORKFLOW,
                                          ProtoTrigger.Type.PROGRAM_STATUS);
    Assert.assertEquals(4, programStatusSchedules.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, programStatusSchedules);

    // Schedules triggered by SOME_WORKFLOW's completed or failed or killed status
    ProgramId someWorkflow = app1.workflow(AppWithMultipleSchedules.SOME_WORKFLOW);
    List<ScheduleDetail> triggeredSchedules1 = listSchedulesByTriggerProgram(TEST_NAMESPACE2, someWorkflow,
                                                                             ProgramStatus.COMPLETED,
                                                                             ProgramStatus.FAILED,
                                                                             ProgramStatus.KILLED);
    Assert.assertEquals(3, triggeredSchedules1.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, triggeredSchedules1);

    List<ScheduleDetail> filteredSchedules =
      listSchedulesByTriggerProgram(TEST_NAMESPACE2, someWorkflow, ProgramScheduleStatus.SCHEDULED,
                                    ProgramStatus.COMPLETED, ProgramStatus.FAILED, ProgramStatus.KILLED);
    // No schedule is enabled yet
    Assert.assertEquals(0, filteredSchedules.size());
    filteredSchedules = listSchedulesByTriggerProgram(TEST_NAMESPACE2, someWorkflow, ProgramScheduleStatus.SUSPENDED,
                                                      ProgramStatus.COMPLETED,
                                                      ProgramStatus.FAILED,
                                                      ProgramStatus.KILLED);
    // All schedules are suspended
    Assert.assertEquals(3, filteredSchedules.size());

    // Schedules triggered by SOME_WORKFLOW's completed status
    List<ScheduleDetail> triggeredByCompletedSchedules = listSchedulesByTriggerProgram(TEST_NAMESPACE2, someWorkflow,
                                                                                       ProgramStatus.COMPLETED);
    Assert.assertEquals(2, triggeredByCompletedSchedules.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, triggeredByCompletedSchedules);
    // Schedules triggered by ANOTHER_WORKFLOW regardless of program status
    ProgramId anotherWorkflow = app1.workflow(AppWithMultipleSchedules.ANOTHER_WORKFLOW);
    List<ScheduleDetail> triggeredSchedules2 = listSchedulesByTriggerProgram(TEST_NAMESPACE2, anotherWorkflow);
    Assert.assertEquals(1, triggeredSchedules2.size());
    assertProgramInSchedules(AppWithMultipleSchedules.TRIGGERED_WORKFLOW, triggeredSchedules2);

    deleteApp(app1, 200);

    // Schedule detail of app2 from versioned API
    List<ScheduleDetail> anotherSchedules2 = getSchedules(TEST_NAMESPACE2, AppWithMultipleSchedules.NAME,
                                                          VERSION2, AppWithMultipleSchedules.ANOTHER_WORKFLOW);
    Assert.assertEquals(3, anotherSchedules2.size());
    assertProgramInSchedules(AppWithMultipleSchedules.ANOTHER_WORKFLOW, anotherSchedules2);

    deleteApp(app2, 200);
  }

  private void assertProgramInSchedules(String programName, List<ScheduleDetail> schedules) {
    for (ScheduleDetail scheduleDetail : schedules) {
      Assert.assertEquals(programName, scheduleDetail.getProgram().getProgramName());
    }
  }

  @Test
  public void testServices() throws Exception {
    deploy(AppWithServices.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2);

    Id.Service service1 = Id.Service.from(Id.Namespace.from(TEST_NAMESPACE1), APP_WITH_SERVICES_APP_ID,
                                          APP_WITH_SERVICES_SERVICE_NAME);
    final Id.Service service2 = Id.Service.from(Id.Namespace.from(TEST_NAMESPACE2), APP_WITH_SERVICES_APP_ID,
                                                APP_WITH_SERVICES_SERVICE_NAME);
    HttpResponse activeResponse = getServiceAvailability(service1);
    // Service is not valid, so it should return 404
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), activeResponse.getResponseCode());

    activeResponse = getServiceAvailability(service2);
    // Service has not been started, so it should return 503
    Assert.assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                        activeResponse.getResponseCode());

    // start service in wrong namespace
    startProgram(service1, 404);
    startProgram(service2);

    waitState(service2, RUNNING);

    Tasks.waitFor(200, () -> getServiceAvailability(service2).getResponseCode(),
                  2, TimeUnit.SECONDS, 10, TimeUnit.MILLISECONDS);

    // verify instances
    try {
      getServiceInstances(service1);
      Assert.fail("Should not find service in " + TEST_NAMESPACE1);
    } catch (AssertionError expected) {
      // expected
    }
    ServiceInstances instances = getServiceInstances(service2);
    Assert.assertEquals(1, instances.getRequested());
    Assert.assertEquals(1, instances.getProvisioned());

    // request 2 additional instances
    int code = setServiceInstances(service1, 3);
    Assert.assertEquals(404, code);
    code = setServiceInstances(service2, 3);
    Assert.assertEquals(200, code);

    // verify that additional instances were provisioned
    instances = getServiceInstances(service2);
    Assert.assertEquals(3, instances.getRequested());
    Assert.assertEquals(3, instances.getProvisioned());

    // verify that endpoints are not available in the wrong namespace
    HttpResponse response = callService(service1, HttpMethod.POST, "multi");
    code = response.getResponseCode();
    Assert.assertEquals(404, code);

    response = callService(service1, HttpMethod.GET, "multi/ping");
    code = response.getResponseCode();
    Assert.assertEquals(404, code);

    // stop service
    stopProgram(service1, 404);
    stopProgram(service2);
    waitState(service2, STOPPED);

    activeResponse = getServiceAvailability(service2);
    // Service has been stopped, so it should return 503
    Assert.assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                        activeResponse.getResponseCode());
  }

  @Test
  public void testDeleteQueues() throws Exception {
    QueueName queueName = QueueName.fromFlowlet(TEST_NAMESPACE1, WORDCOUNT_APP_NAME, WORDCOUNT_FLOW_NAME,
                                                WORDCOUNT_FLOWLET_NAME, "out");

    // enqueue some data
    enqueue(queueName, new QueueEntry("x".getBytes(Charsets.UTF_8)));

    // verify it exists
    Assert.assertTrue(dequeueOne(queueName));

    // clear queue in wrong namespace
    Assert.assertEquals(200, doDelete("/v3/namespaces/" + TEST_NAMESPACE2 + "/queues").getResponseCode());
    // verify queue is still here
    Assert.assertTrue(dequeueOne(queueName));

    // clear queue in the right namespace
    Assert.assertEquals(200, doDelete("/v3/namespaces/" + TEST_NAMESPACE1 + "/queues").getResponseCode());

    // verify queue is gone
    Assert.assertFalse(dequeueOne(queueName));
  }

  @Test
  public void testSchedules() throws Exception {
    // deploy an app with schedule
    Id.Artifact artifactId = Id.Artifact.from(Id.Namespace.fromEntityId(TEST_NAMESPACE_META1.getNamespaceId()),
                                              AppWithSchedule.NAME, VERSION1);
    addAppArtifact(artifactId, AppWithSchedule.class);
    AppRequest<? extends Config> request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()));
    ApplicationId defaultAppId = TEST_NAMESPACE_META1.getNamespaceId().app(AppWithSchedule.NAME);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    // deploy another version of the app
    ApplicationId appV2Id = TEST_NAMESPACE_META1.getNamespaceId().app(AppWithSchedule.NAME, VERSION2);
    Assert.assertEquals(200, deploy(appV2Id, request).getResponseCode());

    // list schedules for default version app, for the workflow and for the app, they should be same
    List<ScheduleDetail> schedules =
      getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(1, schedules.size());
    ScheduleDetail schedule = schedules.get(0);
    Assert.assertEquals(SchedulableProgramType.WORKFLOW, schedule.getProgram().getProgramType());
    Assert.assertEquals(AppWithSchedule.WORKFLOW_NAME, schedule.getProgram().getProgramName());
    Assert.assertEquals(new TimeTrigger("0/15 * * * * ?"), schedule.getTrigger());

    // there should be two schedules now
    List<ScheduleDetail> schedulesForApp = listSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, null);
    Assert.assertEquals(1, schedulesForApp.size());
    Assert.assertEquals(schedules, schedulesForApp);

    List<ScheduleDetail> schedules2 =
      getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(1, schedules2.size());
    ScheduleDetail schedule2 = schedules2.get(0);
    Assert.assertEquals(SchedulableProgramType.WORKFLOW, schedule2.getProgram().getProgramType());
    Assert.assertEquals(AppWithSchedule.WORKFLOW_NAME, schedule2.getProgram().getProgramName());
    Assert.assertEquals(new TimeTrigger("0/15 * * * * ?"), schedule2.getTrigger());

    String newSchedule = "newTimeSchedule";
    testAddSchedule(newSchedule);
    testDeleteSchedule(appV2Id, newSchedule);
    testUpdateSchedule(appV2Id);
  }

  @Test
  public void testUpdateSchedulesFlag() throws Exception {
    // deploy an app with schedule
    AppWithSchedule.AppConfig config = new AppWithSchedule.AppConfig(true, true, true);

    Id.Artifact artifactId = Id.Artifact.from(Id.Namespace.fromEntityId(TEST_NAMESPACE_META2.getNamespaceId()),
                                              AppWithSchedule.NAME, VERSION1);
    addAppArtifact(artifactId, AppWithSchedule.class);
    AppRequest<? extends Config> request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, false);

    ApplicationId defaultAppId = TEST_NAMESPACE_META2.getNamespaceId().app(AppWithSchedule.NAME);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    List<ScheduleDetail> actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                                         defaultAppId.getApplication(), defaultAppId.getVersion());

    // none of the schedules will be added as we have set update schedules to be false
    Assert.assertEquals(0, actualSchedules.size());

    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, true);

    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                    defaultAppId.getApplication(), defaultAppId.getVersion());
    Assert.assertEquals(2, actualSchedules.size());

    // with workflow, without schedule
    config = new AppWithSchedule.AppConfig(true, false, false);
    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, false);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    // schedule should not be updated
    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                    defaultAppId.getApplication(),
                                    defaultAppId.getVersion());
    Assert.assertEquals(2, actualSchedules.size());

    // without workflow and schedule, schedule should be deleted
    config = new AppWithSchedule.AppConfig(false, false, false);
    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, false);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                   defaultAppId.getApplication(),
                                   defaultAppId.getVersion());
    Assert.assertEquals(0, actualSchedules.size());

    // with workflow and  one schedule, schedule should be added
    config = new AppWithSchedule.AppConfig(true, true, false);
    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, true);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                    defaultAppId.getApplication(),
                                    defaultAppId.getVersion());
    Assert.assertEquals(1, actualSchedules.size());
    Assert.assertEquals("SampleSchedule", actualSchedules.get(0).getName());

    // with workflow and two schedules, but update-schedules is false, so 2nd schedule should not get added
    config = new AppWithSchedule.AppConfig(true, true, true);
    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, false);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                    defaultAppId.getApplication(),
                                    defaultAppId.getVersion());
    Assert.assertEquals(1, actualSchedules.size());
    Assert.assertEquals("SampleSchedule", actualSchedules.get(0).getName());

    // same config, but update-schedule flag is true now, so 2 schedules should be available now
    request = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), config, null, null, true);
    Assert.assertEquals(200, deploy(defaultAppId, request).getResponseCode());

    actualSchedules = listSchedules(TEST_NAMESPACE_META2.getNamespaceId().getNamespace(),
                                   defaultAppId.getApplication(),
                                   defaultAppId.getVersion());
    Assert.assertEquals(2, actualSchedules.size());
  }

  @Test
  public void testStartProgramWithDisabledProfile() throws Exception {
    // put my profile and disable it, using this profile to start program should fail
    ProfileId profileId = new NamespaceId(TEST_NAMESPACE1).profile("MyProfile");
    Profile profile = new Profile("MyProfile", Profile.NATIVE.getLabel(), Profile.NATIVE.getDescription(),
                                  Profile.NATIVE.getScope(), Profile.NATIVE.getProvisioner());
    putProfile(profileId, profile, 200);
    disableProfile(profileId, 200);

    // deploy, check the status
    deploy(AppWithWorkflow.class, 200, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    ProgramId programId =
      new NamespaceId(TEST_NAMESPACE1).app(APP_WITH_WORKFLOW_APP_ID).workflow(APP_WITH_WORKFLOW_WORKFLOW_NAME);

    // workflow is stopped initially
    Assert.assertEquals(STOPPED, getProgramStatus(programId));

    // start workflow should give a 409 since we have a runtime argument associated with a disabled profile
    startProgram(programId, Collections.singletonMap(SystemArguments.PROFILE_NAME, profileId.getScopedName()), 409);
    Assert.assertEquals(STOPPED, getProgramStatus(programId));

    // use native profile to start workflow should work since it is always enabled.
    // the workflow should start but fail because we are not passing in required runtime args.
    int runs = getProgramRuns(programId, ProgramRunStatus.FAILED).size();

    startProgram(programId, Collections.singletonMap(SystemArguments.PROFILE_NAME, ProfileId.NATIVE.getScopedName()),
                 200);

    // wait for the workflow to stop and check the status
    Tasks.waitFor(runs + 1, () -> getProgramRuns(programId, ProgramRunStatus.FAILED).size(), 60, TimeUnit.SECONDS);
  }

  private void testAddSchedule(String scheduleName) throws Exception {
    String partitionScheduleName = scheduleName + "Partition";
    String orScheduleName = scheduleName + "Or";
    ProtoTrigger.TimeTrigger protoTime = new ProtoTrigger.TimeTrigger("0 * * * ?");
    ProtoTrigger.PartitionTrigger protoPartition =
      new ProtoTrigger.PartitionTrigger(NamespaceId.DEFAULT.dataset("data"), 5);
    ProtoTrigger.OrTrigger protoOr = ProtoTrigger.or(protoTime, protoPartition);
    String description = "Something";
    ScheduleProgramInfo programInfo = new ScheduleProgramInfo(SchedulableProgramType.WORKFLOW,
                                                              AppWithSchedule.WORKFLOW_NAME);
    ImmutableMap<String, String> properties = ImmutableMap.of("a", "b", "c", "d");
    TimeTrigger timeTrigger = new TimeTrigger("0 * * * ?");
    ScheduleDetail timeDetail = new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                                                   scheduleName, description, programInfo, properties,
                                                   timeTrigger, Collections.emptyList(),
                                                   Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);
    PartitionTrigger partitionTrigger =
      new PartitionTrigger(protoPartition.getDataset(), protoPartition.getNumPartitions());
    ScheduleDetail expectedPartitionDetail =
      new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                         partitionScheduleName, description, programInfo, properties, partitionTrigger,
                         Collections.emptyList(), Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);

    ScheduleDetail requestPartitionDetail =
      new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                         partitionScheduleName, description, programInfo, properties, protoPartition,
                         Collections.emptyList(), Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);

    ScheduleDetail expectedOrDetail =
      new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                         orScheduleName, description, programInfo, properties,
                         new OrTrigger(timeTrigger, partitionTrigger),
                         Collections.emptyList(), Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);

    ScheduleDetail requestOrDetail =
      new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                         orScheduleName, description, programInfo, properties, protoOr,
                         Collections.emptyList(), Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);

    // trying to add the schedule with different name in path param than schedule spec should fail
    HttpResponse response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, "differentName", timeDetail);
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.getResponseCode());

    // adding a schedule to a non-existing app should fail
    response = addSchedule(TEST_NAMESPACE1, "nonExistingApp", null, scheduleName, timeDetail);
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // adding a schedule to invalid type of program type should fail
    ScheduleDetail invalidScheduleDetail = new ScheduleDetail(
      scheduleName, "Something", new ScheduleProgramInfo(SchedulableProgramType.MAPREDUCE, AppWithSchedule.MAPREDUCE),
      properties, protoTime, ImmutableList.<Constraint>of(), TimeUnit.MINUTES.toMillis(1));
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, scheduleName, invalidScheduleDetail);
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.getResponseCode());

    // adding a schedule for a program that does not exist
    ScheduleDetail nonExistingDetail =
      new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, ApplicationId.DEFAULT_VERSION,
                         scheduleName, description, new ScheduleProgramInfo(SchedulableProgramType.MAPREDUCE, "nope"),
                         properties, timeTrigger, Collections.emptyList(),
                         Schedulers.JOB_QUEUE_TIMEOUT_MILLIS, null);
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, scheduleName, nonExistingDetail);
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // test adding a schedule
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, scheduleName, timeDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, partitionScheduleName, requestPartitionDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, orScheduleName, requestOrDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    List<ScheduleDetail> schedules = getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(4, schedules.size());
    Assert.assertEquals(timeDetail, schedules.get(1));
    Assert.assertEquals(expectedOrDetail, schedules.get(2));
    Assert.assertEquals(expectedPartitionDetail, schedules.get(3));

    List<ScheduleDetail> schedulesForApp = listSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, null);
    Assert.assertEquals(schedules, schedulesForApp);

    // trying to add ScheduleDetail of the same schedule again should fail with AlreadyExistsException
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, scheduleName, timeDetail);
    Assert.assertEquals(HttpResponseStatus.CONFLICT.code(), response.getResponseCode());

    // although we should be able to add schedule to a different version of the app
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2, scheduleName, timeDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    // this should not have affected the schedules of the default version
    List<ScheduleDetail> scheds = getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(schedules, scheds);

    // there should be two schedules now for version 2
    List<ScheduleDetail> schedules2 =
      getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(2, schedules2.size());
    Assert.assertEquals(timeDetail, schedules2.get(1));

    List<ScheduleDetail> schedulesForApp2 = listSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2);
    Assert.assertEquals(schedules2, schedulesForApp2);

    // Add a schedule with no schedule name in spec
    ScheduleDetail detail2 = new ScheduleDetail(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2,
                                                null, "Something 2", programInfo, properties,
                                                new TimeTrigger("0 * * * ?"),
                                                Collections.emptyList(), TimeUnit.HOURS.toMillis(6), null);
    response = addSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2, "schedule-100", detail2);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());
    ScheduleDetail detail100 = getSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, VERSION2, "schedule-100");
    Assert.assertEquals("schedule-100", detail100.getName());
    Assert.assertEquals(detail2.getTimeoutMillis(), detail100.getTimeoutMillis());
  }

  private void testDeleteSchedule(ApplicationId appV2Id, String scheduleName) throws Exception {
    // trying to delete a schedule from a non-existing app should fail
    HttpResponse response = deleteSchedule(TEST_NAMESPACE1, "nonExistingApp", null, scheduleName);
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // trying to delete a non-existing schedule should fail
    response = deleteSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, "nonExistingSchedule");
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // trying to delete a valid existing schedule should pass
    response = deleteSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, scheduleName);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());
    List<ScheduleDetail> schedules = getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(3, schedules.size());

    // the above schedule delete should not have affected the schedule with same name in another version of the app
    schedules = getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, appV2Id.getVersion(),
                             AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(3, schedules.size());

    // should have a schedule with the given name
    boolean foundSchedule = false;
    for (ScheduleDetail schedule : schedules) {
      if (schedule.getName().equals(scheduleName)) {
        foundSchedule = true;
      }
    }
    Assert.assertTrue(String.format("Expected to find a schedule named %s but didn't", scheduleName), foundSchedule);

    // delete the schedule from the other version of the app too as a cleanup
    response = deleteSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, appV2Id.getVersion(), scheduleName);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());
    schedules = getSchedules(TEST_NAMESPACE1, AppWithSchedule.NAME, appV2Id.getVersion(),
                             AppWithSchedule.WORKFLOW_NAME);
    Assert.assertEquals(2, schedules.size());
  }

  private void testUpdateSchedule(ApplicationId appV2Id) throws Exception {
    ScheduleDetail updateDetail = new ScheduleDetail(AppWithSchedule.SCHEDULE, "updatedDescription", null,
                                                     ImmutableMap.of("twoKey", "twoValue", "someKey", "newValue"),
                                                     new TimeTrigger("0 4 * * *"),
                                                     ImmutableList.of(new ConcurrencyConstraint(5)), null);

    // trying to update schedule for a non-existing app should fail
    HttpResponse response = updateSchedule(TEST_NAMESPACE1, "nonExistingApp", null, AppWithSchedule.SCHEDULE,
                                           updateDetail);
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // trying to update a non-existing schedule should fail
    ScheduleDetail nonExistingSchedule = new ScheduleDetail("NonExistingSchedule", "updatedDescription", null,
                                                            ImmutableMap.of("twoKey", "twoValue"),
                                                            new TimeTrigger("0 4 * * *"),
                                                            ImmutableList.of(new ConcurrencyConstraint(5)), null);
    response = updateSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null,
                              "NonExistingSchedule", nonExistingSchedule);
    Assert.assertEquals(HttpResponseStatus.NOT_FOUND.code(), response.getResponseCode());

    // should be able to update an existing schedule with a valid new time schedule
    response = updateSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, AppWithSchedule.SCHEDULE,
                              updateDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    // verify that the schedule information for updated
    ScheduleDetail schedule = getSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, AppWithSchedule.SCHEDULE);
    Assert.assertEquals("updatedDescription", schedule.getDescription());
    Assert.assertEquals("0 4 * * *", ((TimeTrigger) schedule.getTrigger()).getCronExpression());
    Assert.assertEquals(new ProtoConstraint.ConcurrencyConstraint(5), schedule.getConstraints().get(0));
    // the properties should have been replaced
    Assert.assertEquals(2, schedule.getProperties().size());
    Assert.assertEquals("newValue", schedule.getProperties().get("someKey"));
    Assert.assertEquals("twoValue", schedule.getProperties().get("twoKey"));
    // the old property should not exist
    Assert.assertNull(schedule.getProperties().get("oneKey"));

    // the above update should not have affected the schedule for the other version of the app
    schedule = getSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, appV2Id.getVersion(), AppWithSchedule.SCHEDULE);
    Assert.assertNotEquals("updatedDescription", schedule.getDescription());
    Assert.assertEquals("0/15 * * * * ?", ((TimeTrigger) schedule.getTrigger()).getCronExpression());

    // try to update the schedule again but this time with property as null. It should retain the old properties
    ScheduleDetail scheduleDetail = new ScheduleDetail(AppWithSchedule.SCHEDULE, "updatedDescription", null, null,
                                                       new ProtoTrigger.TimeTrigger("0 4 * * *"), null, null);
    response = updateSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, AppWithSchedule.SCHEDULE, scheduleDetail);
    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());
    schedule = getSchedule(TEST_NAMESPACE1, AppWithSchedule.NAME, null, AppWithSchedule.SCHEDULE);
    Assert.assertEquals(2, schedule.getProperties().size());
    Assert.assertEquals("newValue", schedule.getProperties().get("someKey"));
    Assert.assertEquals("twoValue", schedule.getProperties().get("twoKey"));
    Assert.assertEquals(new ProtoConstraint.ConcurrencyConstraint(5), schedule.getConstraints().get(0));
  }

  @After
  public void cleanup() throws Exception {
    doDelete(getVersionedAPIPath("apps/", Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1));
    doDelete(getVersionedAPIPath("apps/", Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE2));
  }

  // TODO: Duplicate from AppFabricHttpHandlerTest, remove the AppFabricHttpHandlerTest method after deprecating v2 APIs
  private  void enqueue(QueueName queueName, final QueueEntry queueEntry) throws Exception {
    QueueClientFactory queueClientFactory = AppFabricTestBase.getInjector().getInstance(QueueClientFactory.class);
    final QueueProducer producer = queueClientFactory.createProducer(queueName);
    // doing inside tx
    TransactionExecutorFactory txExecutorFactory =
      AppFabricTestBase.getInjector().getInstance(TransactionExecutorFactory.class);
    txExecutorFactory.createExecutor(ImmutableList.of((TransactionAware) producer))
      .execute(() -> {
        // write more than one so that we can dequeue multiple times for multiple checks
        // we only dequeue twice, but ensure that the drop queues call drops the rest of the entries as well
        int numEntries = 0;
        while (numEntries++ < 5) {
          producer.enqueue(queueEntry);
        }
      });
  }

  private boolean dequeueOne(QueueName queueName) throws Exception {
    QueueClientFactory queueClientFactory = AppFabricTestBase.getInjector().getInstance(QueueClientFactory.class);
    final QueueConsumer consumer = queueClientFactory.createConsumer(queueName,
                                                                     new ConsumerConfig(1L, 0, 1,
                                                                                        DequeueStrategy.ROUND_ROBIN,
                                                                                        null),
                                                                     1);
    // doing inside tx
    TransactionExecutorFactory txExecutorFactory =
      AppFabricTestBase.getInjector().getInstance(TransactionExecutorFactory.class);
    return txExecutorFactory.createExecutor(ImmutableList.of((TransactionAware) consumer))
      .execute(() -> !consumer.dequeue(1).isEmpty());
  }

  private HttpResponse getServiceAvailability(Id.Service serviceId) throws Exception {
    String activeUrl = String.format("apps/%s/services/%s/available", serviceId.getApplicationId(), serviceId.getId());
    String versionedActiveUrl = getVersionedAPIPath(activeUrl, Constants.Gateway.API_VERSION_3_TOKEN,
                                                    serviceId.getNamespaceId());
    return doGet(versionedActiveUrl);
  }

  private ServiceInstances getServiceInstances(Id.Service serviceId) throws Exception {
    String instanceUrl = String.format("apps/%s/services/%s/instances", serviceId.getApplicationId(),
                                       serviceId.getId());
    String versionedInstanceUrl = getVersionedAPIPath(instanceUrl, Constants.Gateway.API_VERSION_3_TOKEN,
                                                      serviceId.getNamespaceId());
    HttpResponse response = doGet(versionedInstanceUrl);
    Assert.assertEquals(200, response.getResponseCode());
    return readResponse(response, ServiceInstances.class);
  }

  private int setServiceInstances(Id.Service serviceId, int instances) throws Exception {
    String instanceUrl = String.format("apps/%s/services/%s/instances", serviceId.getApplicationId(),
                                       serviceId.getId());
    String versionedInstanceUrl = getVersionedAPIPath(instanceUrl, Constants.Gateway.API_VERSION_3_TOKEN,
                                                      serviceId.getNamespaceId());
    String instancesBody = GSON.toJson(new Instances(instances));
    return doPut(versionedInstanceUrl, instancesBody).getResponseCode();
  }

  private HttpResponse callService(Id.Service serviceId, HttpMethod method, String endpoint) throws Exception {
    String serviceUrl = String.format("apps/%s/service/%s/methods/%s",
                                      serviceId.getApplicationId(), serviceId.getId(), endpoint);
    String versionedServiceUrl = getVersionedAPIPath(serviceUrl, Constants.Gateway.API_VERSION_3_TOKEN,
                                                     serviceId.getNamespaceId());
    if (HttpMethod.GET.equals(method)) {
      return doGet(versionedServiceUrl);
    } else if (HttpMethod.POST.equals(method)) {
      return doPost(versionedServiceUrl);
    }
    throw new IllegalArgumentException("Only GET and POST supported right now.");
  }

  private int deleteQueues(String namespace) throws Exception {
    String versionedDeleteUrl = getVersionedAPIPath("queues", Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    HttpResponse response = doDelete(versionedDeleteUrl);
    return response.getResponseCode();
  }

  private int deleteQueues(String namespace, String appId, String flow) throws Exception {
    String deleteQueuesUrl = String.format("apps/%s/flows/%s/queues", appId, flow);
    String versionedDeleteUrl = getVersionedAPIPath(deleteQueuesUrl, Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    HttpResponse response = doDelete(versionedDeleteUrl);
    return response.getResponseCode();
  }

  private JsonObject getLiveInfo(String namespace, String appId, String programType, String programId)
    throws Exception {
    HttpResponse response = sendLiveInfoRequest(namespace, appId, programType, programId);
    Assert.assertEquals(200, response.getResponseCode());
    return readResponse(response, JsonObject.class);
  }

  private HttpResponse sendLiveInfoRequest(String namespace, String appId, String programType, String programId)
    throws Exception {
    String liveInfoUrl = String.format("apps/%s/%s/%s/live-info", appId, programType, programId);
    String versionedUrl = getVersionedAPIPath(liveInfoUrl, Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    return doGet(versionedUrl);
  }

  private int requestFlowletInstances(String namespace, String appId, String flow, String flowlet, int noRequested)
    throws Exception {
    String flowletInstancesVersionedUrl = getFlowletInstancesVersionedUrl(namespace, appId, flow, flowlet);
    JsonObject instances = new JsonObject();
    instances.addProperty("instances", noRequested);
    String body = GSON.toJson(instances);
    return doPut(flowletInstancesVersionedUrl, body).getResponseCode();
  }

  private int getFlowletInstances(String namespace, String appId, String flow, String flowlet) throws Exception {
    String flowletInstancesUrl = getFlowletInstancesVersionedUrl(namespace, appId, flow, flowlet);
    String response = doGet(flowletInstancesUrl).getResponseBodyAsString();
    JsonObject instances = GSON.fromJson(response, JsonObject.class);
    Assert.assertTrue(instances.has("instances"));
    return instances.get("instances").getAsInt();
  }

  private String getFlowletInstancesVersionedUrl(String namespace, String appId, String flow, String flowlet) {
    String flowletInstancesUrl = String.format("apps/%s/%s/%s/flowlets/%s/instances", appId,
                                               ProgramType.FLOW.getCategoryName(), flow, flowlet);
    return getVersionedAPIPath(flowletInstancesUrl, Constants.Gateway.API_VERSION_3_TOKEN, namespace);
  }

  private void verifyProgramSpecification(String namespace, String appId, String programType, String programId)
    throws Exception {
    JsonObject programSpec = getProgramSpecification(namespace, appId, programType, programId);
    Assert.assertTrue(programSpec.has("className") && programSpec.has("name") && programSpec.has("description"));
    Assert.assertEquals(programId, programSpec.get("name").getAsString());
  }

  private JsonObject getProgramSpecification(String namespace, String appId, String programType,
                                             String programId) throws Exception {
    HttpResponse response = requestProgramSpecification(namespace, appId, programType, programId);
    Assert.assertEquals(200, response.getResponseCode());
    return GSON.fromJson(response.getResponseBodyAsString(), JsonObject.class);
  }

  private int getProgramSpecificationResponseCode(String namespace, String appId, String programType, String programId)
    throws Exception {
    HttpResponse response = requestProgramSpecification(namespace, appId, programType, programId);
    return response.getResponseCode();
  }

  private HttpResponse requestProgramSpecification(String namespace, String appId, String programType,
                                                   String programId) throws Exception {
    String uri = getVersionedAPIPath(String.format("apps/%s/%s/%s", appId, programType, programId),
                                     Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    return doGet(uri);
  }

  private void testListInitialState(String namespace, ProgramType programType) throws Exception {
    HttpResponse response = doGet(getVersionedAPIPath(programType.getCategoryName(),
                                                      Constants.Gateway.API_VERSION_3_TOKEN, namespace));
    Assert.assertEquals(200, response.getResponseCode());
    Assert.assertEquals(EMPTY_ARRAY_JSON, response.getResponseBodyAsString());
  }

  private void verifyProgramList(String namespace, ProgramType programType, int expected) throws Exception {
    HttpResponse response = requestProgramList(namespace, programType.getCategoryName());
    Assert.assertEquals(200, response.getResponseCode());
    List<Map<String, String>> programs = GSON.fromJson(response.getResponseBodyAsString(), LIST_MAP_STRING_STRING_TYPE);
    Assert.assertEquals(expected, programs.size());
  }

  private void verifyProgramList(String namespace, String appName,
                                 final ProgramType programType, int expected) throws Exception {
    HttpResponse response = requestAppDetail(namespace, appName);
    Assert.assertEquals(200, response.getResponseCode());
    ApplicationDetail appDetail = GSON.fromJson(response.getResponseBodyAsString(), ApplicationDetail.class);
    Collection<ProgramRecord> programs = Collections2.filter(
      appDetail.getPrograms(), record -> programType.getCategoryName().equals(record.getType().getCategoryName()));
    Assert.assertEquals(expected, programs.size());
  }

  private int getAppFDetailResponseCode(String namespace, @Nullable String appName) throws Exception {
    HttpResponse response = requestAppDetail(namespace, appName);
    return response.getResponseCode();
  }

  private HttpResponse requestProgramList(String namespace, String programType)
    throws Exception {
    return doGet(getVersionedAPIPath(programType, Constants.Gateway.API_VERSION_3_TOKEN, namespace));
  }

  private HttpResponse requestAppDetail(String namespace, String appName)
    throws Exception {
    String uri = getVersionedAPIPath(String.format("apps/%s", appName),
                                     Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    return doGet(uri);
  }

  private void verifyInitialBatchStatusOutput(HttpResponse response) throws IOException {
    Assert.assertEquals(200, response.getResponseCode());
    List<JsonObject> returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    for (JsonObject obj : returnedBody) {
      Assert.assertEquals(200, obj.get("statusCode").getAsInt());
      Assert.assertEquals(STOPPED, obj.get("status").getAsString());
    }
  }

  private void verifyInitialBatchInstanceOutput(HttpResponse response) throws IOException {
    Assert.assertEquals(200, response.getResponseCode());
    List<JsonObject> returnedBody = readResponse(response, LIST_OF_JSONOBJECT_TYPE);
    for (JsonObject obj : returnedBody) {
      Assert.assertEquals(200, obj.get("statusCode").getAsInt());
      Assert.assertEquals(1, obj.get("requested").getAsInt());
      Assert.assertEquals(0, obj.get("provisioned").getAsInt());
    }
  }

  private void testHistory(Class<?> app, Id.Program program) throws Exception {
    String namespace = program.getNamespaceId();
    try {
      deploy(app, 200, Constants.Gateway.API_VERSION_3_TOKEN, namespace);
      verifyProgramHistory(program.toEntityId());
    } catch (Exception e) {
      LOG.error("Got exception: ", e);
    } finally {
      deleteApp(program.getApplication(), 200);
    }
    ApplicationId appId = new ApplicationId(namespace, program.getApplicationId(), VERSION1);
    ProgramId programId = appId.program(program.getType(), program.getId());
    try {
      Id.Artifact artifactId = Id.Artifact.from(program.getNamespace(), app.getSimpleName(), "1.0.0");
      addAppArtifact(artifactId, app);
      AppRequest<Config> request = new AppRequest<>(
        new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()), null);
      Assert.assertEquals(200, deploy(appId, request).getResponseCode());
      verifyProgramHistory(programId);
    } catch (Exception e) {
      LOG.error("Got exception: ", e);
    } finally {
      deleteApp(appId, 200);
    }
  }

  private void verifyProgramHistory(ProgramId program) throws Exception {
    // first run
    startProgram(program, 200);
    waitState(program, RUNNING);

    stopProgram(program, null, 200, null);
    waitState(program, STOPPED);

    // second run
    startProgram(program, 200);
    waitState(program, RUNNING);

    // one run should be active
    historyStatusWithRetry(program, ProgramRunStatus.RUNNING, 1);

    historyStatusWithRetry(program, ProgramRunStatus.KILLED, 1);

    // stop the second run
    stopProgram(program, null, 200, null);
    waitState(program, STOPPED);

    historyStatusWithRetry(program, ProgramRunStatus.KILLED, 2);
  }

  private void historyStatusWithRetry(ProgramId program, ProgramRunStatus status, int size) throws Exception {
    String urlAppVersionPart = ApplicationId.DEFAULT_VERSION.equals(program.getVersion()) ?
      "" : "/versions/" + program.getVersion();
    String basePath = String.format("apps/%s%s/%s/%s/runs", program.getApplication(), urlAppVersionPart,
                                    program.getType().getCategoryName(), program.getProgram());
    String runsUrl = getVersionedAPIPath(basePath + "?status=" + status.name(),
                                         Constants.Gateway.API_VERSION_3_TOKEN, program.getNamespace());
    int trials = 0;
    while (trials++ < 5) {
      HttpResponse response = doGet(runsUrl);
      List<RunRecord> result = GSON.fromJson(response.getResponseBodyAsString(), LIST_OF_RUN_RECORD);
      if (result != null && result.size() >= size) {
        for (RunRecord m : result) {
          String runUrl = getVersionedAPIPath(basePath + "/" + m.getPid(), Constants.Gateway.API_VERSION_3_TOKEN,
                                              program.getNamespace());
          response = doGet(runUrl);
          RunRecord actualRunRecord = GSON.fromJson(response.getResponseBodyAsString(), RunRecord.class);
          Assert.assertEquals(m.getStatus(), actualRunRecord.getStatus());
        }
        break;
      }
      TimeUnit.SECONDS.sleep(1);
    }
    Assert.assertTrue(trials < 5);
  }

  private void testVersionedProgramRuntimeArgs(ProgramId programId) throws Exception {
    String versionedRuntimeArgsUrl = getVersionedAPIPath("apps/" + programId.getApplication()
                                                           + "/versions/" + programId.getVersion()
                                                           + "/" + programId.getType().getCategoryName()
                                                           + "/" + programId.getProgram() + "/runtimeargs",
                                                         Constants.Gateway.API_VERSION_3_TOKEN,
                                                         programId.getNamespace());
    verifyRuntimeArgs(versionedRuntimeArgsUrl);
  }

  private void testRuntimeArgs(Class<?> app, String namespace, String appId, String programType, String programId)
    throws Exception {
    deploy(app, 200, Constants.Gateway.API_VERSION_3_TOKEN, namespace);

    String versionedRuntimeArgsUrl = getVersionedAPIPath("apps/" + appId + "/" + programType + "/" + programId +
                                                           "/runtimeargs", Constants.Gateway.API_VERSION_3_TOKEN,
                                                         namespace);
    verifyRuntimeArgs(versionedRuntimeArgsUrl);

    String versionedRuntimeArgsAppVersionUrl = getVersionedAPIPath("apps/" + appId
                                                                     + "/versions/" + ApplicationId.DEFAULT_VERSION
                                                                     + "/" + programType
                                                                     + "/" + programId + "/runtimeargs",
                                                                   Constants.Gateway.API_VERSION_3_TOKEN, namespace);
    verifyRuntimeArgs(versionedRuntimeArgsAppVersionUrl);
  }

  private void verifyRuntimeArgs(String url) throws Exception {
    Map<String, String> args = Maps.newHashMap();
    args.put("Key1", "Val1");
    args.put("Key2", "Val1");
    args.put("Key2", "Val1");

    HttpResponse response;
    Type mapStringStringType = new TypeToken<Map<String, String>>() { }.getType();

    String argString = GSON.toJson(args, mapStringStringType);

    response = doPut(url, argString);

    Assert.assertEquals(200, response.getResponseCode());
    response = doGet(url);

    Assert.assertEquals(200, response.getResponseCode());
    Map<String, String> argsRead = GSON.fromJson(response.getResponseBodyAsString(), mapStringStringType);

    Assert.assertEquals(args.size(), argsRead.size());

    for (Map.Entry<String, String> entry : args.entrySet()) {
      Assert.assertEquals(entry.getValue(), argsRead.get(entry.getKey()));
    }

    //test empty runtime args
    response = doPut(url, "");
    Assert.assertEquals(200, response.getResponseCode());

    response = doGet(url);
    Assert.assertEquals(200, response.getResponseCode());
    argsRead = GSON.fromJson(response.getResponseBodyAsString(), mapStringStringType);
    Assert.assertEquals(0, argsRead.size());

    //test null runtime args
    response = doPut(url, null);
    Assert.assertEquals(200, response.getResponseCode());

    response = doGet(url);
    Assert.assertEquals(200, response.getResponseCode());
    argsRead = GSON.fromJson(response.getResponseBodyAsString(), mapStringStringType);
    Assert.assertEquals(0, argsRead.size());
  }
}
