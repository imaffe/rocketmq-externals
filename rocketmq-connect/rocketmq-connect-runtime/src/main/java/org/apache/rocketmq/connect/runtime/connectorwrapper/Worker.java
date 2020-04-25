/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.rocketmq.connect.runtime.connectorwrapper;

import io.netty.util.internal.ConcurrentSet;
import io.openmessaging.connector.api.Connector;
import io.openmessaging.connector.api.Task;
import io.openmessaging.connector.api.data.Converter;
import io.openmessaging.connector.api.sink.SinkTask;
import io.openmessaging.connector.api.source.SourceTask;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.connect.runtime.ConnectController;
import org.apache.rocketmq.connect.runtime.common.ConnectKeyValue;
import org.apache.rocketmq.connect.runtime.common.LoggerName;
import org.apache.rocketmq.connect.runtime.config.ConnectConfig;
import org.apache.rocketmq.connect.runtime.config.RuntimeConfigDefine;
import org.apache.rocketmq.connect.runtime.service.DefaultConnectorContext;
import org.apache.rocketmq.connect.runtime.service.PositionManagementService;
import org.apache.rocketmq.connect.runtime.service.TaskPositionCommitService;
import org.apache.rocketmq.connect.runtime.store.PositionStorageReaderImpl;
import org.apache.rocketmq.connect.runtime.utils.ConnectUtil;
import org.apache.rocketmq.connect.runtime.utils.Plugin;
import org.apache.rocketmq.connect.runtime.utils.PluginClassLoader;
import org.apache.rocketmq.remoting.protocol.LanguageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker to schedule all connectors and tasks in a process.
 */
public class Worker {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.ROCKETMQ_RUNTIME);

    /**
     * Current running connectors.
     */
    private Set<WorkerConnector> workingConnectors = new ConcurrentSet<>();

    // TODO below are WIP
    /**
     * Current running tasks.
     */
    private Map<Runnable, Integer> pendingTasks = new ConcurrentHashMap<>();

    private Set<Runnable> runningTasks = new ConcurrentSet<>();

    private Set<Runnable> errorTasks = new ConcurrentSet<>();

    private Map<Runnable, Integer> stoppingTasks = new ConcurrentHashMap<>();


    private Set<Runnable> stoppedTasks = new ConcurrentSet<>();

    /**
     * Current running tasks to its Future map.
     */
    private Map<Runnable, Future> taskToFutureMap = new ConcurrentHashMap<>();



    // TODO alobe are WIP
    /**
     * Thread pool for connectors and tasks.
     */
    private final ExecutorService taskExecutor;

    /**
     * Position management for source tasks.
     */
    private final PositionManagementService positionManagementService;

    /**
     * Offset management for source tasks.
     */
    private final PositionManagementService offsetManagementService;

    /**
     * A scheduled task to commit source position of source tasks.
     */
    private final TaskPositionCommitService taskPositionCommitService;

    private final ConnectConfig connectConfig;

    private final Plugin plugin;

    private final DefaultMQProducer producer;

    private  static final int MAX_START_RETRY = 5;

    private  static final int MAX_STOP_RETRY = 5;
    // for MQProducer
    private volatile boolean producerStarted = false;

    public Worker(ConnectConfig connectConfig,
                  PositionManagementService positionManagementService, PositionManagementService offsetManagementService,
                  Plugin plugin) {
        this.connectConfig = connectConfig;
        this.taskExecutor = Executors.newCachedThreadPool();
        this.positionManagementService = positionManagementService;
        this.offsetManagementService = offsetManagementService;
        this.taskPositionCommitService = new TaskPositionCommitService(
            this,
            positionManagementService,
            offsetManagementService);
        this.plugin = plugin;

        this.producer = new DefaultMQProducer();
        this.producer.setNamesrvAddr(connectConfig.getNamesrvAddr());
        this.producer.setInstanceName(ConnectUtil.createInstance(connectConfig.getNamesrvAddr()));
        this.producer.setProducerGroup(connectConfig.getRmqProducerGroup());
        this.producer.setSendMsgTimeout(connectConfig.getOperationTimeout());
        this.producer.setMaxMessageSize(RuntimeConfigDefine.MAX_MESSAGE_SIZE);
        this.producer.setLanguage(LanguageCode.JAVA);
    }

    public void start() {
        taskPositionCommitService.start();
    }

    /**
     * Start a collection of connectors with the given configs. If a connector is already started with the same configs,
     * it will not start again. If a connector is already started but not contained in the new configs, it will stop.
     *
     * @param connectorConfigs
     * @param connectController
     * @throws Exception
     */
    public synchronized void startConnectors(Map<String, ConnectKeyValue> connectorConfigs,
                                             ConnectController connectController) throws Exception {

        Set<WorkerConnector> stoppedConnector = new HashSet<>();
        for (WorkerConnector workerConnector : workingConnectors) {
            String connectorName = workerConnector.getConnectorName();
            ConnectKeyValue keyValue = connectorConfigs.get(connectorName);
            if (null == keyValue || 0 != keyValue.getInt(RuntimeConfigDefine.CONFIG_DELETED)) {
                workerConnector.stop();
                log.info("Connector {} stop", workerConnector.getConnectorName());
                stoppedConnector.add(workerConnector);
            } else if (!keyValue.equals(workerConnector.getKeyValue())) {
                workerConnector.reconfigure(keyValue);
            }
        }
        workingConnectors.removeAll(stoppedConnector);

        if (null == connectorConfigs || 0 == connectorConfigs.size()) {
            return;
        }
        Map<String, ConnectKeyValue> newConnectors = new HashMap<>();
        for (String connectorName : connectorConfigs.keySet()) {
            boolean isNewConnector = true;
            for (WorkerConnector workerConnector : workingConnectors) {
                if (workerConnector.getConnectorName().equals(connectorName)) {
                    isNewConnector = false;
                    break;
                }
            }
            if (isNewConnector) {
                newConnectors.put(connectorName, connectorConfigs.get(connectorName));
            }
        }

        for (String connectorName : newConnectors.keySet()) {
            ConnectKeyValue keyValue = newConnectors.get(connectorName);
            String connectorClass = keyValue.getString(RuntimeConfigDefine.CONNECTOR_CLASS);
            ClassLoader loader = plugin.getPluginClassLoader(connectorClass);
            final ClassLoader currentThreadLoader = plugin.currentThreadLoader();
            Class clazz;
            boolean isolationFlag = false;
            if (loader instanceof PluginClassLoader) {
                clazz = ((PluginClassLoader) loader).loadClass(connectorClass, false);
                isolationFlag = true;
            } else {
                clazz = Class.forName(connectorClass);
            }
            final Connector connector = (Connector) clazz.getDeclaredConstructor().newInstance();
            WorkerConnector workerConnector = new WorkerConnector(connectorName, connector, connectorConfigs.get(connectorName), new DefaultConnectorContext(connectorName, connectController));
            if (isolationFlag) {
                Plugin.compareAndSwapLoaders(loader);
            }
            workerConnector.initialize();
            workerConnector.start();
            log.info("Connector {} start", workerConnector.getConnectorName());
            Plugin.compareAndSwapLoaders(currentThreadLoader);
            this.workingConnectors.add(workerConnector);
        }
    }

    /**
     * Start a collection of tasks with the given configs. If a task is already started with the same configs, it will
     * not start again. If a task is already started but not contained in the new configs, it will stop.
     *
     * @param taskConfigs
     * @throws Exception
     */
    public synchronized void startTasks(Map<String, List<ConnectKeyValue>> taskConfigs) throws Exception {

        // Let's make the workerSinkTask FutureTaskFirst
        // TODO should be named forceStoppedTasks
        Set<Runnable> newRunningTasks = new HashSet<>();



        for (Runnable runnable : runningTasks) {
            WorkerSourceTask workerSourceTask = null;
            WorkerSinkTask workerSinkTask = null;
            if (runnable instanceof WorkerSourceTask) {
                workerSourceTask = (WorkerSourceTask) runnable;
            } else {
                workerSinkTask = (WorkerSinkTask) runnable;
            }

            String connectorName = null != workerSourceTask ? workerSourceTask.getConnectorName() : workerSinkTask.getConnectorName();
            ConnectKeyValue taskConfig = null != workerSourceTask ? workerSourceTask.getTaskConfig() : workerSinkTask.getTaskConfig();
            List<ConnectKeyValue> keyValues = taskConfigs.get(connectorName);
            boolean needStop = true;
            if (null != keyValues && keyValues.size() > 0) {
                for (ConnectKeyValue keyValue : keyValues) {
                    if (keyValue.equals(taskConfig)) {
                        needStop = false;
                        break;
                    }
                }
            }

            // TODO move new stopping tasks
            if (needStop) {
                if (null != workerSourceTask) {
                    workerSourceTask.stop();
                    log.info("Source task stopping, connector name {}, config {}", workerSourceTask.getConnectorName(), workerSourceTask.getTaskConfig());
                    runningTasks.remove(workerSourceTask);
                    stoppingTasks.put(workerSourceTask, 0);
                } else {
                    workerSinkTask.stop();
                    log.info("Sink task try to stopping, connector name {}, config {}", workerSinkTask.getConnectorName(), workerSinkTask.getTaskConfig());
                    runningTasks.remove(workerSinkTask);
                    stoppingTasks.put(workerSinkTask, 0);
                }

            }
        }

        // TODO check running tasks
        for(Map.Entry<Runnable, Integer> entry : stoppingTasks.entrySet()) {
            Runnable runnable = entry.getKey();
            int stopRetry = entry.getValue();
            Future future = taskToFutureMap.get(runnable);
            // exited normally
            // TODO need to add explicit error handling here
            if(future.isDone()) {
                // concurrent modification Exception ? Will it pop that in the
                stoppingTasks.remove(runnable);
                stoppedTasks.add(runnable);
            } else {
                if(stopRetry> MAX_STOP_RETRY) {
                    // TODO force stop, need to add exception handling logic
                    future.cancel(true);
                    stoppingTasks.remove(runnable);
                    taskToFutureMap.remove(runnable);
                } else {
                    // TODO Will this update work ? and also it is not incrementing, should we use atomic integer?
                    stoppingTasks.put(runnable, stoppingTasks.get(runnable) + 1);
                }
            }
        }


        // TODO other logic remains the same
        if (null == taskConfigs || 0 == taskConfigs.size()) {
            // TODO should not return needs to finish all all lifecycle events
            // return;
        }
        Map<String, List<ConnectKeyValue>> newTasks = new HashMap<>();
        for (String connectorName : taskConfigs.keySet()) {
            for (ConnectKeyValue keyValue : taskConfigs.get(connectorName)) {
                boolean isNewTask = true;
                for (Runnable runnable : runningTasks) {
                    WorkerSourceTask workerSourceTask = null;
                    WorkerSinkTask workerSinkTask = null;
                    if (runnable instanceof WorkerSourceTask) {
                        workerSourceTask = (WorkerSourceTask) runnable;
                    } else {
                        workerSinkTask = (WorkerSinkTask) runnable;
                    }
                    ConnectKeyValue taskConfig = null != workerSourceTask ? workerSourceTask.getTaskConfig() : workerSinkTask.getTaskConfig();
                    if (keyValue.equals(taskConfig)) {
                        isNewTask = false;
                        break;
                    }
                }

                for (Runnable runnable : pendingTasks.keySet()) {
                    WorkerSourceTask workerSourceTask = null;
                    WorkerSinkTask workerSinkTask = null;
                    if (runnable instanceof WorkerSourceTask) {
                        workerSourceTask = (WorkerSourceTask) runnable;
                    } else {
                        workerSinkTask = (WorkerSinkTask) runnable;
                    }
                    ConnectKeyValue taskConfig = null != workerSourceTask ? workerSourceTask.getTaskConfig() : workerSinkTask.getTaskConfig();
                    if (keyValue.equals(taskConfig)) {
                        isNewTask = false;
                        break;
                    }
                }

                // TODO check error tasks, filter then out
                for(Runnable runnable : errorTasks) {
                    WorkerSourceTask workerSourceTask = null;
                    WorkerSinkTask workerSinkTask = null;
                    if (runnable instanceof WorkerSourceTask) {
                        workerSourceTask = (WorkerSourceTask) runnable;
                    } else {
                        workerSinkTask = (WorkerSinkTask) runnable;
                    }
                    ConnectKeyValue taskConfig = null != workerSourceTask ? workerSourceTask.getTaskConfig() : workerSinkTask.getTaskConfig();
                    if (keyValue.equals(taskConfig)) {
                        isNewTask = false;
                        break;
                    }
                }

                if (isNewTask) {
                    if (!newTasks.containsKey(connectorName)) {
                        newTasks.put(connectorName, new ArrayList<>());
                    }
                    log.info("Add new tasks,connector name {}, config {}", connectorName, keyValue);
                    newTasks.get(connectorName).add(keyValue);
                }
            }
        }

        // TODO try to create pending tasks
        for (String connectorName : newTasks.keySet()) {
            for (ConnectKeyValue keyValue : newTasks.get(connectorName)) {
                String taskClass = keyValue.getString(RuntimeConfigDefine.TASK_CLASS);
                ClassLoader loader = plugin.getPluginClassLoader(taskClass);
                final ClassLoader currentThreadLoader = plugin.currentThreadLoader();
                Class taskClazz;
                boolean isolationFlag = false;
                if (loader instanceof PluginClassLoader) {
                    taskClazz = ((PluginClassLoader) loader).loadClass(taskClass, false);
                    isolationFlag = true;
                } else {
                    taskClazz = Class.forName(taskClass);
                }
                final Task task = (Task) taskClazz.getDeclaredConstructor().newInstance();
                final String converterClazzName = keyValue.getString(RuntimeConfigDefine.SOURCE_RECORD_CONVERTER);
                Converter recordConverter = null;
                if (StringUtils.isNotEmpty(converterClazzName)) {
                    Class converterClazz = Class.forName(converterClazzName);
                    recordConverter = (Converter) converterClazz.newInstance();
                }
                if (isolationFlag) {
                    Plugin.compareAndSwapLoaders(loader);
                }
                if (task instanceof SourceTask) {
                    checkRmqProducerState();
                    WorkerSourceTask workerSourceTask = new WorkerSourceTask(connectorName,
                            (SourceTask) task, keyValue,
                            new PositionStorageReaderImpl(positionManagementService), recordConverter, producer);
                    Plugin.compareAndSwapLoaders(currentThreadLoader);
                    // TODO we might want to catch exceptions here
                    workerSourceTask.start();
                    this.pendingTasks.put(workerSourceTask, 0);
                } else if (task instanceof SinkTask) {
                    DefaultMQPullConsumer consumer = new DefaultMQPullConsumer();
                    consumer.setNamesrvAddr(connectConfig.getNamesrvAddr());
                    consumer.setInstanceName(ConnectUtil.createInstance(connectConfig.getNamesrvAddr()));
                    consumer.setConsumerGroup(ConnectUtil.createGroupName(connectConfig.getRmqConsumerGroup()));
                    consumer.setMaxReconsumeTimes(connectConfig.getRmqMaxRedeliveryTimes());
                    consumer.setConsumerPullTimeoutMillis((long) connectConfig.getRmqMessageConsumeTimeout());
                    consumer.start();

                    WorkerSinkTask workerSinkTask = new WorkerSinkTask(connectorName,
                            (SinkTask) task, keyValue,
                            new PositionStorageReaderImpl(offsetManagementService),
                            recordConverter, consumer);
                    Plugin.compareAndSwapLoaders(currentThreadLoader);
                    workerSinkTask.start();
                    this.pendingTasks.put(workerSinkTask, 0);
                }
            }
        }


        // TODO check all pending state
        for (Map.Entry<Runnable, Integer> entry : pendingTasks.entrySet()) {
            Runnable runnable = entry.getKey();
            int startRetry = entry.getValue();
            WorkerSourceTask workerSourceTask = null;
            WorkerSinkTask workerSinkTask = null;
            if (runnable instanceof WorkerSourceTask) {
                workerSourceTask = (WorkerSourceTask) runnable;
            } else {
                workerSinkTask = (WorkerSinkTask) runnable;
            }
            boolean startSuccess =  null != workerSourceTask ? workerSourceTask.isStarted() : workerSinkTask.isStarted();
            if(startSuccess) {
                Future future = taskExecutor.submit(runnable);
                taskToFutureMap.put(runnable, future);
            } else {
                if (startRetry > MAX_START_RETRY) {
                    // TODO need to put to error tasks, and record the reason why it start failed
                    pendingTasks.remove(runnable);
                    errorTasks.add(runnable);
                } else {
                    pendingTasks.put(runnable, pendingTasks.get(runnable) + 1);
                }
            }
        }
    }

    /**
     * Commit the position of all working tasks to PositionManagementService.
     */


    private void checkRmqProducerState() {
        if (!this.producerStarted) {
            try {
                this.producer.start();
                this.producerStarted = true;
            } catch (MQClientException e) {
                log.error("Start producer failed!", e);
            }
        }
    }

    public void stop() {
        if (this.producerStarted && this.producer != null) {
            this.producer.shutdown();
            this.producerStarted = false;
        }
    }

    public Set<WorkerConnector> getWorkingConnectors() {
        return workingConnectors;
    }

    public void setWorkingConnectors(
            Set<WorkerConnector> workingConnectors) {
        this.workingConnectors = workingConnectors;
    }


    // TODO need to change the names of these two method
    public Set<Runnable> getWorkingTasks() {
        return runningTasks;
    }

    public void setWorkingTasks(Set<Runnable> workingTasks) {
        this.runningTasks = workingTasks;
    }
}
