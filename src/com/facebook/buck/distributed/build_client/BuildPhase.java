/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_client;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.PERFORM_DISTRIBUTED_BUILD;

import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** The build phase. */
public class BuildPhase {

  /** The result from a build. */
  public class BuildResult {
    private final BuildJob finalBuildJob;
    private final List<BuildSlaveStatus> buildSlaveStatusList;

    public BuildResult(BuildJob finalBuildJob, List<BuildSlaveStatus> buildSlaveStatusList) {
      this.finalBuildJob = finalBuildJob;
      this.buildSlaveStatusList = buildSlaveStatusList;
    }

    public BuildJob getFinalBuildJob() {
      return finalBuildJob;
    }

    public List<BuildSlaveStatus> getBuildSlaveStatusList() {
      return buildSlaveStatusList;
    }
  }

  private static final Logger LOG = Logger.get(BuildPhase.class);

  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final LogStateTracker distBuildLogStateTracker;
  private final ScheduledExecutorService scheduler;
  private final int statusPollIntervalMillis;
  private final Set<String> seenSlaveRunIds;
  private final Map<BuildSlaveRunId, Integer> nextEventIdBySlaveRunId;

  public BuildPhase(
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      LogStateTracker distBuildLogStateTracker,
      ScheduledExecutorService scheduler,
      int statusPollIntervalMillis) {
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.distBuildLogStateTracker = distBuildLogStateTracker;
    this.scheduler = scheduler;
    this.statusPollIntervalMillis = statusPollIntervalMillis;
    this.seenSlaveRunIds = new HashSet<>();
    this.nextEventIdBySlaveRunId = new HashMap<>();
  }

  /** Run the build while updating the console messages. */
  public BuildResult runDistBuildAndUpdateConsoleStatus(
      ListeningExecutorService networkExecutorService,
      EventSender eventSender,
      StampedeId stampedeId)
      throws IOException, InterruptedException {
    distBuildClientStats.startTimer(PERFORM_DISTRIBUTED_BUILD);
    final BuildJob job = distBuildService.startBuild(stampedeId);
    LOG.info("Started job. Build status: " + job.getStatus().toString());
    DistBuildUtil.logDebugInfo(job);

    nextEventIdBySlaveRunId.clear();
    ScheduledFuture<?> distBuildStatusUpdatingFuture =
        scheduler.scheduleWithFixedDelay(
            () -> fetchBuildInformationFromServer(job, eventSender, networkExecutorService),
            0,
            statusPollIntervalMillis,
            TimeUnit.MILLISECONDS);

    BuildJob finalJob;
    List<BuildSlaveStatus> buildSlaveStatusList = null;
    try {
      distBuildStatusUpdatingFuture.get();
      throw new RuntimeException("Unreachable State.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof JobCompletedException) {
        // Everything is awesome.
        JobCompletedException jobCompletedException = (JobCompletedException) e.getCause();
        finalJob = jobCompletedException.getDistBuildJob();
        buildSlaveStatusList =
            jobCompletedException.getBuildSlaveStatuses().orElse(ImmutableList.of());
      } else {
        throw new HumanReadableException(e, "Failed to fetch build information from server.");
      }
    } finally {
      distBuildClientStats.stopTimer(PERFORM_DISTRIBUTED_BUILD);
    }

    return new BuildResult(finalJob, buildSlaveStatusList);
  }

  private BuildJob fetchBuildInformationFromServer(
      BuildJob job, EventSender eventSender, ListeningExecutorService networkExecutorService) {
    final StampedeId stampedeId = job.getStampedeId();

    try {
      job = distBuildService.getCurrentBuildJobState(stampedeId);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    LOG.info("Got build status: " + job.getStatus().toString());

    if (!job.isSetSlaveInfoByRunId()) {
      eventSender.postDistBuildStatusEvent(job, ImmutableList.of());
      checkTerminateScheduledUpdates(job, Optional.empty());
      return job;
    }

    for (Map.Entry<String, BuildSlaveInfo> slave : job.getSlaveInfoByRunId().entrySet()) {
      if (!seenSlaveRunIds.contains(slave.getKey())) {
        seenSlaveRunIds.add(slave.getKey());
        LOG.info(
            "New slave server attached to build. (RunId: [%s], Hostname: [%s])",
            slave.getKey(), slave.getValue().getHostname());
      }
    }

    ListenableFuture<?> slaveEventsFuture =
        fetchAndPostBuildSlaveEventsAsync(job, eventSender, networkExecutorService);
    ListenableFuture<List<BuildSlaveStatus>> slaveStatusesFuture =
        fetchBuildSlaveStatusesAsync(job, networkExecutorService);
    ListenableFuture<?> logStreamingFuture =
        fetchAndProcessRealTimeSlaveLogsAsync(job, networkExecutorService);

    List<BuildSlaveStatus> slaveStatuses = ImmutableList.of();
    try {
      slaveStatuses = slaveStatusesFuture.get();
      eventSender.postDistBuildStatusEvent(job, slaveStatuses);
      slaveEventsFuture.get();
      logStreamingFuture.get();
    } catch (ExecutionException e) {
      LOG.error(e, "Failed to get slave statuses, events or logs.");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    checkTerminateScheduledUpdates(job, Optional.of(slaveStatuses));
    return job;
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndPostBuildSlaveEventsAsync(
      BuildJob job, EventSender eventSender, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    StampedeId stampedeId = job.getStampedeId();
    List<BuildSlaveEventsQuery> fetchEventQueries = new LinkedList<>();

    for (String id : job.getSlaveInfoByRunId().keySet()) {
      BuildSlaveRunId runId = new BuildSlaveRunId();
      runId.setId(id);
      fetchEventQueries.add(
          distBuildService.createBuildSlaveEventsQuery(
              stampedeId, runId, nextEventIdBySlaveRunId.getOrDefault(runId, 0)));
    }
    ListenableFuture<List<Pair<Integer, BuildSlaveEvent>>> fetchEventsFuture =
        networkExecutorService.submit(
            () -> distBuildService.multiGetBuildSlaveEvents(fetchEventQueries));

    ListenableFuture<?> postEventsFuture =
        Futures.transform(
            fetchEventsFuture,
            sequenceIdAndEvents -> {

              // Sort such that all events from the same RunId come together, and in increasing
              // order
              // of their sequence IDs. Also, we cannot directly sort sequenceIdAndEvents as it
              // might
              // be an ImmutableList, hence we make it a stream.
              sequenceIdAndEvents =
                  sequenceIdAndEvents
                      .stream()
                      .sorted(
                          (event1, event2) -> {
                            BuildSlaveRunId runId1 = event1.getSecond().getBuildSlaveRunId();
                            BuildSlaveRunId runId2 = event2.getSecond().getBuildSlaveRunId();

                            int result = runId1.compareTo(runId2);
                            if (result == 0) {
                              result = event1.getFirst().compareTo(event2.getFirst());
                            }

                            return result;
                          })
                      .collect(Collectors.toList());

              for (Pair<Integer, BuildSlaveEvent> sequenceIdAndEvent : sequenceIdAndEvents) {
                BuildSlaveEvent slaveEvent = sequenceIdAndEvent.getSecond();
                nextEventIdBySlaveRunId.put(
                    slaveEvent.getBuildSlaveRunId(), sequenceIdAndEvent.getFirst() + 1);
                switch (slaveEvent.getEventType()) {
                  case CONSOLE_EVENT:
                    ConsoleEvent consoleEvent =
                        DistBuildUtil.createConsoleEvent(slaveEvent.getConsoleEvent());
                    eventSender.postConsoleEvent(consoleEvent);
                    break;
                  case UNKNOWN:
                  default:
                    LOG.error(
                        String.format(
                            "Unknown type of BuildSlaveEvent received: [%d]",
                            slaveEvent.getEventType().getValue()));
                    break;
                }
              }
              return null;
            });

    return postEventsFuture;
  }

  @VisibleForTesting
  ListenableFuture<?> fetchAndProcessRealTimeSlaveLogsAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(null);
    }

    List<LogLineBatchRequest> newLogLineRequests =
        distBuildLogStateTracker.createRealtimeLogRequests(job.getSlaveInfoByRunId().values());
    if (newLogLineRequests.size() == 0) {
      return Futures.immediateFuture(null);
    }

    return networkExecutorService.submit(
        () -> {
          try {
            MultiGetBuildSlaveRealTimeLogsResponse slaveLogsResponse =
                distBuildService.fetchSlaveLogLines(job.getStampedeId(), newLogLineRequests);
            Preconditions.checkState(slaveLogsResponse.isSetMultiStreamLogs());

            distBuildLogStateTracker.processStreamLogs(slaveLogsResponse.getMultiStreamLogs());
          } catch (IOException e) {
            LOG.error(e, "Encountered error while streaming logs from BuildSlave(s).");
          }
        });
  }

  private void checkTerminateScheduledUpdates(
      BuildJob job, Optional<List<BuildSlaveStatus>> slaveStatuses) {
    if (job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY)
        || job.getStatus().equals(BuildStatus.FAILED)) {
      // Terminate scheduled tasks with a custom exception to indicate success.
      throw new JobCompletedException(job, slaveStatuses);
    }
  }

  /** Exception thrown when the build job is complete. */
  public static final class JobCompletedException extends RuntimeException {

    private final BuildJob job;
    private final Optional<List<BuildSlaveStatus>> buildSlaveStatuses;

    private JobCompletedException(
        BuildJob job, Optional<List<BuildSlaveStatus>> buildSlaveStatuses) {
      super(String.format("DistBuild job completed with status: [%s]", job.getStatus().toString()));
      this.job = job;
      this.buildSlaveStatuses = buildSlaveStatuses;
    }

    public BuildJob getDistBuildJob() {
      return job;
    }

    public Optional<List<BuildSlaveStatus>> getBuildSlaveStatuses() {
      return buildSlaveStatuses;
    }
  }

  @VisibleForTesting
  ListenableFuture<List<BuildSlaveStatus>> fetchBuildSlaveStatusesAsync(
      BuildJob job, ListeningExecutorService networkExecutorService) {
    if (!job.isSetSlaveInfoByRunId()) {
      return Futures.immediateFuture(ImmutableList.of());
    }

    StampedeId stampedeId = job.getStampedeId();
    List<ListenableFuture<Optional<BuildSlaveStatus>>> slaveStatusFutures = new LinkedList<>();

    // TODO(shivanker, alisdair): Replace this with a multiFetch request.
    for (String id : job.getSlaveInfoByRunId().keySet()) {
      BuildSlaveRunId runId = new BuildSlaveRunId();
      runId.setId(id);
      slaveStatusFutures.add(
          networkExecutorService.submit(
              () -> distBuildService.fetchBuildSlaveStatus(stampedeId, runId)));
    }

    return Futures.transform(
        Futures.allAsList(slaveStatusFutures),
        slaveStatusList ->
            slaveStatusList
                .stream()
                .filter(Optional::isPresent)
                .map(x -> x.get())
                .collect(Collectors.toList()));
  }
}
