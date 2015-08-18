/*
 * Copyright © 2014-2015 Cask Data, Inc.
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

package co.cask.cdap.internal.app.runtime.batch;

import co.cask.cdap.api.Resources;
import co.cask.cdap.api.artifact.Plugin;
import co.cask.cdap.api.data.batch.BatchReadable;
import co.cask.cdap.api.data.batch.InputFormatProvider;
import co.cask.cdap.api.data.batch.OutputFormatProvider;
import co.cask.cdap.api.data.batch.Split;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.dataset.Dataset;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import co.cask.cdap.api.mapreduce.MapReduceSpecification;
import co.cask.cdap.api.metrics.Metrics;
import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.api.metrics.MetricsContext;
import co.cask.cdap.api.workflow.WorkflowToken;
import co.cask.cdap.app.metrics.MapReduceMetrics;
import co.cask.cdap.app.metrics.ProgramUserMetrics;
import co.cask.cdap.app.program.Program;
import co.cask.cdap.app.runtime.Arguments;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.logging.LoggingContext;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.app.runtime.AbstractContext;
import co.cask.cdap.internal.app.runtime.adapter.PluginInstantiator;
import co.cask.cdap.logging.context.MapReduceLoggingContext;
import co.cask.cdap.proto.Id;
import co.cask.cdap.templates.AdapterDefinition;
import co.cask.tephra.TransactionAware;
import com.google.common.collect.Maps;
import org.apache.hadoop.mapreduce.Job;
import org.apache.twill.api.RunId;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.LocationFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Mapreduce job runtime context
 */
public class BasicMapReduceContext extends AbstractContext implements MapReduceContext {

  private final MapReduceSpecification spec;
  private final LoggingContext loggingContext;
  private final long logicalStartTime;
  private final String programNameInWorkflow;
  private final WorkflowToken workflowToken;
  private final Metrics userMetrics;
  private final MetricsCollectionService metricsCollectionService;

  private String inputDatasetName;
  private List<Split> inputDataSelection;
  private String outputDatasetName;
  private Job job;
  private Dataset outputDataset;
  private Dataset inputDataset;
  private Resources mapperResources;
  private Resources reducerResources;

  public BasicMapReduceContext(Program program,
                               MapReduceMetrics.TaskType type,
                               RunId runId, String taskId,
                               Arguments runtimeArguments,
                               Set<String> datasets,
                               MapReduceSpecification spec,
                               long logicalStartTime,
                               @Nullable String programNameInWorkflow,
                               @Nullable WorkflowToken workflowToken,
                               DiscoveryServiceClient discoveryServiceClient,
                               MetricsCollectionService metricsCollectionService,
                               DatasetFramework dsFramework,
                               LocationFactory locationFactory,
                               @Nullable AdapterDefinition adapterSpec,
                               @Nullable PluginInstantiator pluginInstantiator,
                               @Nullable PluginInstantiator artifactPluginInstantiator) {
    super(program, runId, runtimeArguments, datasets,
          getMetricCollector(program, runId.getId(), taskId, metricsCollectionService, type, adapterSpec),
          dsFramework, discoveryServiceClient, locationFactory, adapterSpec, pluginInstantiator,
          artifactPluginInstantiator);
    this.logicalStartTime = logicalStartTime;
    this.programNameInWorkflow = programNameInWorkflow;
    this.workflowToken = workflowToken;
    this.metricsCollectionService = metricsCollectionService;

    if (metricsCollectionService != null) {
      this.userMetrics = new ProgramUserMetrics(getProgramMetrics());
    } else {
      this.userMetrics = null;
    }
    this.loggingContext = createLoggingContext(program.getId(), runId, adapterSpec);
    this.spec = spec;
    this.mapperResources = spec.getMapperResources();
    this.reducerResources = spec.getReducerResources();
    // initialize input/output to what the spec says. These can be overwritten at runtime.
    this.inputDatasetName = spec.getInputDataSet();
    this.outputDatasetName = spec.getOutputDataSet();
  }

  private LoggingContext createLoggingContext(Id.Program programId, RunId runId,
                                              @Nullable AdapterDefinition adapterSpec) {
    String adapterName = adapterSpec == null ? null : adapterSpec.getName();
    return new MapReduceLoggingContext(programId.getNamespaceId(), programId.getApplicationId(),
                                       programId.getId(), runId.getId(), adapterName);
  }

  @Override
  public String toString() {
    return String.format("job=%s,=%s",
                         spec.getName(), super.toString());
  }

  @Override
  public Map<String, Plugin> getPlugins() {
    return spec.getPlugins();
  }

  @Override
  public MapReduceSpecification getSpecification() {
    return spec;
  }

  @Override
  public long getLogicalStartTime() {
    return logicalStartTime;
  }

  /**
   * Returns the name of the Batch job when running inside workflow. Otherwise, return null.
   */
  @Nullable
  public String getProgramNameInWorkflow() {
    return programNameInWorkflow;
  }

  /**
   * Returns the WorkflowToken if the MapReduce program is executed as a part of the Workflow.
   */
  @Override
  @Nullable
  public WorkflowToken getWorkflowToken() {
    return workflowToken;
  }

  public void setJob(Job job) {
    this.job = job;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getHadoopJob() {
    return (T) job;
  }

  @Override
  public void setInput(StreamBatchReadable stream) {
    setInput(stream.toURI().toString());
  }

  @Override
  public void setInput(String datasetName) {
    this.inputDatasetName = datasetName;
  }

  @Override
  public void setInput(String datasetName, List<Split> splits) {
    this.inputDatasetName = datasetName;
    this.inputDataSelection = splits;
  }

  @Override
  public void setOutput(String datasetName) {
    this.outputDatasetName = datasetName;
  }

  @Override
  public void setInput(String inputDatasetName, Dataset dataset) {
    if (!(dataset instanceof BatchReadable) && !(dataset instanceof InputFormatProvider)) {
      throw new IllegalArgumentException("Input dataset must be a BatchReadable or InputFormatProvider.");
    }
    // splits will be set by the MapReduceRuntimeService if they are not directly set by the program.
    this.inputDatasetName = inputDatasetName;
    this.inputDataset = dataset;
  }

  //TODO: update this to allow a BatchWritable once the DatasetOutputFormat can support taking an instance
  //      and not just the name
  @Override
  public void setOutput(String outputDatasetName, Dataset dataset) {
    if (!(dataset instanceof OutputFormatProvider)) {
      throw new IllegalArgumentException("Output dataset must be an OutputFormatProvider.");
    }
    this.outputDatasetName = outputDatasetName;
    this.outputDataset = dataset;
  }

  /**
   * Retrieves the output dataset for this MapReduce job. If the dataset instance was set at runtime,
   * that instance is returned.
   *
   * <p>
   * If the dataset name was set at runtime, an instance with that name is returned.
   * If nothing was set at runtime, the output dataset from the program specification is used.
   * If an output dataset was never specified, {@code null} is returned.
   * </p>
   *
   * @return the output dataset for the MapReduce job.
   */
  public Dataset getOutputDataset() {
    // use the dataset instance if it is set.
    if (outputDataset != null) {
      return outputDataset;
    }

    // otherwise, use the output dataset name to create one.
    if (outputDatasetName != null) {
      return getDataset(outputDatasetName);
    }

    // if we got here, a dataset is not the output.
    return null;
  }

  /**
   * Get the input dataset for the job. If the dataset instance was set at runtime, that instance is returned.
   * If the dataset name was set at runtime, an instance for that name is returned. If nothing was set at runtime, the
   * input dataset from the program spec is used. If no input dataset was specified anywhere, a null is returned.
   *
   * @return Input dataset for the MapReduce job.
   */
  public Dataset getInputDataset() {
    // use the dataset instance if it is set.
    if (inputDataset != null) {
      return inputDataset;
    }

    // otherwise, use the input dataset name to create one.
    if (inputDatasetName != null) {
      return getDataset(inputDatasetName);
    }

    // if we got here, a dataset is not the input.
    return null;
  }

  @Override
  public void setMapperResources(Resources resources) {
    this.mapperResources = resources;
  }

  @Override
  public void setReducerResources(Resources resources) {
    this.reducerResources = resources;
  }

  @Nullable
  private static MetricsContext getMetricCollector(Program program, String runId, String taskId,
                                                     @Nullable MetricsCollectionService service,
                                                     @Nullable MapReduceMetrics.TaskType type,
                                                     @Nullable AdapterDefinition adapterSpec) {
    if (service == null) {
      return null;
    }

    Map<String, String> tags = Maps.newHashMap();
    tags.putAll(getMetricsContext(program, runId));
    if (type != null) {
      tags.put(Constants.Metrics.Tag.MR_TASK_TYPE, type.getId());
      tags.put(Constants.Metrics.Tag.INSTANCE_ID, taskId);
    }

    if (adapterSpec != null) {
      tags.put(Constants.Metrics.Tag.ADAPTER, adapterSpec.getName());
    }

    return service.getContext(tags);
  }

  @Override
  public Metrics getMetrics() {
    return userMetrics;
  }

  public MetricsCollectionService getMetricsCollectionService() {
    return metricsCollectionService;
  }

  public LoggingContext getLoggingContext() {
    return loggingContext;
  }

  @Nullable
  public String getInputDatasetName() {
    return inputDatasetName;
  }

  public List<Split> getInputDataSelection() {
    return inputDataSelection;
  }

  @Nullable
  public String getOutputDatasetName() {
    return outputDatasetName;
  }

  public Resources getMapperResources() {
    return mapperResources;
  }

  public Resources getReducerResources() {
    return reducerResources;
  }

  public void flushOperations() throws Exception {
    for (TransactionAware txAware : getDatasetInstantiator().getTransactionAware()) {
      txAware.commitTx();
    }
  }
}
