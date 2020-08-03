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

import com.alibaba.fastjson.JSON;
import org.apache.rocketmq.connect.runtime.common.ConnectKeyValue;
import org.apache.rocketmq.connect.runtime.common.LoggerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * TODO should we put
 */
public abstract class AbstractWorkerTask implements WorkerTask {
    /**
     * The configuration key that provides the list of topicNames that are inputs for this SinkTask.
     * TODO need to change this, why do we hard code this name ?
     */
    public static final String QUEUENAMES_CONFIG = "topicNames";

    protected static final Logger log = LoggerFactory.getLogger(LoggerName.ROCKETMQ_RUNTIME);
    /**
     * Connector name of current task.
     */
    protected String connectorName;


    /**
     * The configs of current sink task.
     */
    protected ConnectKeyValue taskConfig;


    /**
     * Atomic state variable
     */
    protected AtomicReference<WorkerTaskState> state;


    protected WorkerTaskErrorSnapshot errorSnapshot;


    /**
     * Further we cant try to log what caused the error
     */
    @Override
    public void timeout() {
        this.state.set(WorkerTaskState.ERROR);
    }


    /**
     * Define state migration restriction here.
     * @param from
     * @param to
     */
    @Override
    public boolean migrateState(WorkerTaskState from, WorkerTaskState to) {
        boolean result = false;
        switch (from) {
            case NEW:
                if (WorkerTaskState.PENDING.equals(to) ||
                    WorkerTaskState.ERROR.equals(to)) {
                    result = state.compareAndSet(from, to);
                }
                break;
            case PENDING:
                if (WorkerTaskState.RUNNING.equals(to) ||
                        WorkerTaskState.ERROR.equals(to)) {
                    result = state.compareAndSet(from, to);
                }
                break;
            case RUNNING:
                if (WorkerTaskState.STOPPING.equals(to) ||
                        WorkerTaskState.ERROR.equals(to)) {
                    result = state.compareAndSet(from, to);
                }
                break;
            case STOPPING:
                if (WorkerTaskState.STOPPED.equals(to) ||
                        WorkerTaskState.ERROR.equals(to)) {
                    result = state.compareAndSet(from, to);
                }
                break;
            case STOPPED:
                if (WorkerTaskState.ERROR.equals(to)) {
                    result = state.compareAndSet(from, to);
                }
                break;
            case ERROR:
                break;
            default:
                result = false;
        }

        if (!result) {
            log.error("Illegal state migration request in task {}, from state {} to state {}", toString(), from.toString(), toString());
            // TODO should throws illegal state exception
        }

        return result;
    }

    @Override
    public void migrateToErrorState(Throwable t) {
        state.set(WorkerTaskState.ERROR);
        WorkerTaskErrorSnapshot workerTaskErrorSnapshot = new WorkerTaskErrorSnapshot();
        workerTaskErrorSnapshot.setPrev(state.get());
        workerTaskErrorSnapshot.setThrowable(t);
        errorSnapshot = workerTaskErrorSnapshot;
    }

    @Override
    public WorkerTaskState getState() {
        return state.get();
    }

    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();
        sb.append("connectorName:" + connectorName)
                .append("\nConfigs:" + JSON.toJSONString(taskConfig))
                .append("\nState:" + state.get().toString())
                .append("\nPrevStateBeforeError:" + (null == errorSnapshot ? " " : errorSnapshot.toString()));
        return sb.toString();
    }

    @Override
    public Object getJsonObject() {
        HashMap obj = new HashMap<String, Object>();
        obj.put("connectorName", connectorName);
        obj.put("configs", JSON.toJSONString(taskConfig));
        obj.put("state", state.get().toString());
        obj.put("prevStateBeforeError", null == errorSnapshot ? "NO ERROR" : errorSnapshot.getPrev());
        obj.put("error", null == errorSnapshot ? "NO ERROR" : errorSnapshot.getThrowable());
        return obj;
    }
}
