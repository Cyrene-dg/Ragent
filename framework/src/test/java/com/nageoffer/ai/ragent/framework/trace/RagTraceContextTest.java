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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagTraceContextTest {

    @AfterEach
    void clearContext() {
        RagTraceContext.clear();
    }

    @Test
    void shouldGiveParallelTasksIndependentNodeStackSnapshots() throws Exception {
        RagTraceContext.pushNode("retrieve");

        ExecutorService rawExecutor = Executors.newFixedThreadPool(2);
        ExecutorService ttlExecutor = TtlExecutors.getTtlExecutorService(rawExecutor);
        CountDownLatch bothBranchesPushed = new CountDownLatch(2);
        CountDownLatch releaseBranches = new CountDownLatch(1);
        try {
            Future<String> milvus = ttlExecutor.submit(() -> pushAndRead(
                    "milvus", bothBranchesPushed, releaseBranches));
            Future<String> elasticsearch = ttlExecutor.submit(() -> pushAndRead(
                    "elasticsearch", bothBranchesPushed, releaseBranches));

            assertTrue(bothBranchesPushed.await(3, TimeUnit.SECONDS));
            releaseBranches.countDown();

            assertEquals("milvus", milvus.get(3, TimeUnit.SECONDS));
            assertEquals("elasticsearch", elasticsearch.get(3, TimeUnit.SECONDS));
            assertEquals("retrieve", RagTraceContext.currentNodeId());
            assertEquals(1, RagTraceContext.depth());
        } finally {
            releaseBranches.countDown();
            ttlExecutor.shutdownNow();
        }
    }

    private String pushAndRead(String nodeId,
                               CountDownLatch bothBranchesPushed,
                               CountDownLatch releaseBranches) throws InterruptedException {
        assertEquals("retrieve", RagTraceContext.currentNodeId());
        RagTraceContext.pushNode(nodeId);
        bothBranchesPushed.countDown();
        assertTrue(releaseBranches.await(3, TimeUnit.SECONDS));
        String currentNodeId = RagTraceContext.currentNodeId();
        RagTraceContext.popNode();
        return currentNodeId;
    }
}
