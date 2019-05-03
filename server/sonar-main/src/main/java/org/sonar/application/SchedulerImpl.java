/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.application;

import java.util.EnumMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.application.command.AbstractCommand;
import org.sonar.application.command.CommandFactory;
import org.sonar.application.config.AppSettings;
import org.sonar.application.config.ClusterSettings;
import org.sonar.application.process.ManagedProcessLifecycle;
import org.sonar.application.process.ManagedProcessEventListener;
import org.sonar.application.process.ProcessLifecycleListener;
import org.sonar.application.process.ManagedProcess;
import org.sonar.application.process.ManagedProcessHandler;
import org.sonar.process.ProcessId;

import static org.sonar.application.process.ManagedProcessHandler.Timeout.newTimeout;

public class SchedulerImpl implements Scheduler, ManagedProcessEventListener, ProcessLifecycleListener, AppStateListener {

  private static final Logger LOG = LoggerFactory.getLogger(SchedulerImpl.class);

  private final AppSettings settings;
  private final AppReloader appReloader;
  private final CommandFactory commandFactory;
  private final ProcessLauncher processLauncher;
  private final AppState appState;
  private final NodeLifecycle nodeLifecycle = new NodeLifecycle();

  private final CountDownLatch awaitTermination = new CountDownLatch(1);
  private final AtomicBoolean firstWaitingEsLog = new AtomicBoolean(true);
  private final AtomicBoolean restartRequested = new AtomicBoolean(false);
  private final AtomicBoolean restarting = new AtomicBoolean(false);
  private final EnumMap<ProcessId, ManagedProcessHandler> processesById = new EnumMap<>(ProcessId.class);
  private final AtomicInteger operationalCountDown = new AtomicInteger();
  private final AtomicInteger stopCountDown = new AtomicInteger(0);
  private HardStopperThread hardStopperThread;
  private RestarterThread restarterThread;
  private long processWatcherDelayMs = ManagedProcessHandler.DEFAULT_WATCHER_DELAY_MS;

  public SchedulerImpl(AppSettings settings, AppReloader appReloader, CommandFactory commandFactory,
    ProcessLauncher processLauncher, AppState appState) {
    this.settings = settings;
    this.appReloader = appReloader;
    this.commandFactory = commandFactory;
    this.processLauncher = processLauncher;
    this.appState = appState;
    this.appState.addListener(this);
  }

  SchedulerImpl setProcessWatcherDelayMs(long l) {
    this.processWatcherDelayMs = l;
    return this;
  }

  @Override
  public void schedule() {
    if (!nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STARTING)) {
      return;
    }
    processesById.clear();

    for (ProcessId processId : ClusterSettings.getEnabledProcesses(settings)) {
      ManagedProcessHandler process = ManagedProcessHandler.builder(processId)
        .addProcessLifecycleListener(this)
        .addEventListener(this)
        .setWatcherDelayMs(processWatcherDelayMs)
        // FIXME MMF-1673 timeout here must be changed to sonar.ce.task.timeout + 5 minutes if CE
        .setHardStopTimeout(newTimeout(1, TimeUnit.MINUTES))
        .build();
      processesById.put(process.getProcessId(), process);
    }
    operationalCountDown.set(processesById.size());

    tryToStartAll();
  }

  private void tryToStartAll() {
    tryToStartEs();
    tryToStartWeb();
    tryToStartCe();
  }

  private void tryToStartEs() {
    ManagedProcessHandler process = processesById.get(ProcessId.ELASTICSEARCH);
    if (process != null) {
      tryToStartProcess(process, commandFactory::createEsCommand);
    }
  }

  private void tryToStartWeb() {
    ManagedProcessHandler process = processesById.get(ProcessId.WEB_SERVER);
    if (process == null) {
      return;
    }
    if (!isEsClientStartable()) {
      if (firstWaitingEsLog.getAndSet(false)) {
        LOG.info("Waiting for Elasticsearch to be up and running");
      }
      return;
    }
    if (appState.isOperational(ProcessId.WEB_SERVER, false)) {
      tryToStartProcess(process, () -> commandFactory.createWebCommand(false));
    } else if (appState.tryToLockWebLeader()) {
      tryToStartProcess(process, () -> commandFactory.createWebCommand(true));
    } else {
      Optional<String> leader = appState.getLeaderHostName();
      if (leader.isPresent()) {
        LOG.info("Waiting for initialization from {}", leader.get());
      } else {
        LOG.error("Initialization failed. All nodes must be restarted");
      }
    }
  }

  private void tryToStartCe() {
    ManagedProcessHandler process = processesById.get(ProcessId.COMPUTE_ENGINE);
    if (process != null && appState.isOperational(ProcessId.WEB_SERVER, true) && isEsClientStartable()) {
      tryToStartProcess(process, commandFactory::createCeCommand);
    }
  }

  private boolean isEsClientStartable() {
    boolean requireLocalEs = ClusterSettings.isLocalElasticsearchEnabled(settings);
    return appState.isOperational(ProcessId.ELASTICSEARCH, requireLocalEs);
  }

  private void tryToStartProcess(ManagedProcessHandler process, Supplier<AbstractCommand> commandSupplier) {
    tryToStart(process, () -> {
      AbstractCommand command = commandSupplier.get();
      return processLauncher.launch(command);
    });
  }

  private void tryToStart(ManagedProcessHandler process, Supplier<ManagedProcess> processMonitorSupplier) {
    try {
      process.start(processMonitorSupplier);
    } catch (RuntimeException e) {
      // failed to start command -> stop everything
      hardStop();
      throw e;
    }
  }

  private void hardStopAll() {
    // order is important for non-cluster mode
    hardStopProcess(ProcessId.COMPUTE_ENGINE);
    hardStopProcess(ProcessId.WEB_SERVER);
    hardStopProcess(ProcessId.ELASTICSEARCH);
  }

  /**
   * Request for quick stop then blocks until process is stopped.
   * Returns immediately if the process is disabled in configuration.
   */
  private void hardStopProcess(ProcessId processId) {
    ManagedProcessHandler process = processesById.get(processId);
    if (process != null) {
      process.hardStop();
    }
  }

  /**
   * Blocks until all processes are quickly stopped. Pending restart, if any, is disabled.
   */
  @Override
  public void hardStop() {
    restartRequested.set(false);
    restarting.set(false);

    if (nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPING)) {
      LOG.info("Stopping SonarQube");
    }
    hardStopAll();
    if (hardStopperThread != null) {
      hardStopperThread.interrupt();
    }
    if (restarterThread != null) {
      restarterThread.interrupt();
    }
    awaitTermination.countDown();
  }

  @Override
  public void awaitTermination() {
    try {
      awaitTermination.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void onManagedProcessEvent(ProcessId processId, Type type) {
    if (type == Type.OPERATIONAL) {
      onProcessOperational(processId);
    } else if (type == Type.ASK_FOR_RESTART && restartRequested.compareAndSet(false, true)) {
      restarting.set(true);
      hardStopAsync();
    }
  }

  private void onProcessOperational(ProcessId processId) {
    LOG.info("Process[{}] is up", processId.getKey());
    appState.setOperational(processId);
    if (operationalCountDown.decrementAndGet() == 0 && nodeLifecycle.tryToMoveTo(NodeLifecycle.State.OPERATIONAL)) {
      LOG.info("SonarQube is up");
    }
  }

  @Override
  public void onAppStateOperational(ProcessId processId) {
    if (nodeLifecycle.getState() == NodeLifecycle.State.STARTING) {
      tryToStartAll();
    }
  }

  @Override
  public void onProcessState(ProcessId processId, ManagedProcessLifecycle.State to) {
    switch (to) {
      case STOPPED:
        onProcessStop(processId);
        break;
      case STARTING:
        stopCountDown.incrementAndGet();
        break;
      default:
        // Nothing to do
        break;
    }
  }

  private void onProcessStop(ProcessId processId) {
    LOG.info("Process [{}] is stopped", processId.getKey());
    if (stopCountDown.decrementAndGet() == 0 && nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPED)) {
      if (restarting.get() && restartRequested.compareAndSet(true, false)) {
        LOG.info("SonarQube is restarting");
        restartAsync();
      } else {
        LOG.info("SonarQube is stopped");
        // all processes are stopped, no restart requested
        // Let's clean-up resources
        hardStop();
      }

    } else if (nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPING)) {
      // this is the first process stopping
      hardStopAsync();
    }
  }

  private void hardStopAsync() {
    hardStopperThread = new HardStopperThread();
    hardStopperThread.start();
  }

  private void restartAsync() {
    restarterThread = new RestarterThread();
    restarterThread.start();
  }

  private class RestarterThread extends Thread {
    public RestarterThread() {
      super("Restarter");
    }

    @Override
    public void run() {
      try {
        appReloader.reload(settings);
        schedule();
      } catch (Exception e) {
        LOG.error("Fail to restart", e);
        hardStop();
      }
    }
  }

  private class HardStopperThread extends Thread {
    public HardStopperThread() {
      super("Hard stopper");
    }

    @Override
    public void run() {
      hardStopAll();
    }
  }
}
