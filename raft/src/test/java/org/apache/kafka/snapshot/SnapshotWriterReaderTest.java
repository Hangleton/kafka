/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.raft.Batch;
import org.apache.kafka.raft.OffsetAndEpoch;
import org.apache.kafka.raft.RaftClientTestContext;
import org.apache.kafka.raft.internals.StringSerde;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final public class SnapshotWriterReaderTest {
    private final int localId = 0;
    private final Set<Integer> voters = Collections.singleton(localId);

    @Test
    public void testWritingSnapshot() throws Exception {
        int recordsPerBatch = 3;
        int batches = 3;
        OffsetAndEpoch id = new OffsetAndEpoch(recordsPerBatch * batches, 3);
        List<List<String>> expected = buildRecords(recordsPerBatch, batches);

        RaftClientTestContext.Builder contextBuilder = new RaftClientTestContext.Builder(localId, voters);
        for (List<String> batch : expected) {
            contextBuilder.appendToLog(id.epoch, batch);
        }
        RaftClientTestContext context = contextBuilder.build();

        context.pollUntil(() -> context.currentLeader().equals(OptionalInt.of(localId)));
        int epoch = context.currentEpoch();

        context.advanceLocalLeaderHighWatermarkToLogEndOffset();

        try (SnapshotWriter<String> snapshot = context.client.createSnapshot(id.offset - 1, id.epoch).get()) {
            assertEquals(id, snapshot.snapshotId());
            expected.forEach(batch -> assertDoesNotThrow(() -> snapshot.append(batch)));
            snapshot.freeze();
        }

        try (SnapshotReader<String> reader = readSnapshot(context, id, Integer.MAX_VALUE)) {
            assertSnapshot(expected, reader);
        }
    }

    @Test
    public void testAbortedSnapshot() throws Exception {
        int recordsPerBatch = 3;
        int batches = 3;
        OffsetAndEpoch id = new OffsetAndEpoch(recordsPerBatch * batches, 3);
        List<List<String>> expected = buildRecords(recordsPerBatch, batches);

        RaftClientTestContext.Builder contextBuilder = new RaftClientTestContext.Builder(localId, voters);
        for (List<String> batch : expected) {
            contextBuilder.appendToLog(id.epoch, batch);
        }
        RaftClientTestContext context = contextBuilder.build();

        context.pollUntil(() -> context.currentLeader().equals(OptionalInt.of(localId)));
        int epoch = context.currentEpoch();

        context.advanceLocalLeaderHighWatermarkToLogEndOffset();

        try (SnapshotWriter<String> snapshot = context.client.createSnapshot(id.offset - 1, id.epoch).get()) {
            assertEquals(id, snapshot.snapshotId());
            expected.forEach(batch -> {
                assertDoesNotThrow(() -> snapshot.append(batch));
            });
        }

        assertEquals(Optional.empty(), context.log.readSnapshot(id));
    }

    @Test
    public void testAppendToFrozenSnapshot() throws Exception {
        int recordsPerBatch = 3;
        int batches = 3;
        OffsetAndEpoch id = new OffsetAndEpoch(recordsPerBatch * batches, 3);
        List<List<String>> expected = buildRecords(recordsPerBatch, batches);

        RaftClientTestContext.Builder contextBuilder = new RaftClientTestContext.Builder(localId, voters);
        for (List<String> batch : expected) {
            contextBuilder.appendToLog(id.epoch, batch);
        }
        RaftClientTestContext context = contextBuilder.build();

        context.pollUntil(() -> context.currentLeader().equals(OptionalInt.of(localId)));
        int epoch = context.currentEpoch();

        context.advanceLocalLeaderHighWatermarkToLogEndOffset();

        try (SnapshotWriter<String> snapshot = context.client.createSnapshot(id.offset - 1, id.epoch).get()) {
            assertEquals(id, snapshot.snapshotId());
            expected.forEach(batch -> {
                assertDoesNotThrow(() -> snapshot.append(batch));
            });

            snapshot.freeze();

            assertThrows(RuntimeException.class, () -> snapshot.append(expected.get(0)));
        }
    }

    private List<List<String>> buildRecords(int recordsPerBatch, int batches) {
        Random random = new Random(0);
        List<List<String>> result = new ArrayList<>(batches);
        for (int i = 0; i < batches; i++) {
            List<String> batch = new ArrayList<>(recordsPerBatch);
            for (int j = 0; j < recordsPerBatch; j++) {
                batch.add(String.valueOf(random.nextInt()));
            }
            result.add(batch);
        }

        return result;
    }

    private SnapshotReader<String> readSnapshot(
        RaftClientTestContext context,
        OffsetAndEpoch snapshotId,
        int maxBatchSize
    ) {
        return SnapshotReader.of(
            context.log.readSnapshot(snapshotId).get(),
            context.serde,
            BufferSupplier.create(),
            maxBatchSize
        );
    }

    public static void assertSnapshot(List<List<String>> batches, RawSnapshotReader reader) {
        assertSnapshot(
            batches,
            SnapshotReader.of(reader, new StringSerde(), BufferSupplier.create(), Integer.MAX_VALUE)
        );
    }

    public static void assertSnapshot(List<List<String>> batches, SnapshotReader<String> reader) {
        List<String> expected = new ArrayList<>();
        batches.forEach(expected::addAll);

        List<String> actual = new ArrayList<>(expected.size());
        while (reader.hasNext()) {
            Batch<String> batch = reader.next();
            for (String value : batch) {
                actual.add(value);
            }
        }

        assertEquals(expected, actual);
    }
}
