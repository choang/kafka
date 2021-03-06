/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.smoketest;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.TumblingWindows;
import org.apache.kafka.streams.kstream.UnlimitedWindows;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.Windowed;

import java.io.File;
import java.util.Properties;

public class SmokeTestClient extends SmokeTestUtil {

    private final String kafka;
    private final String zookeeper;
    private final File stateDir;
    private KafkaStreams streams;
    private Thread thread;

    public SmokeTestClient(File stateDir, String kafka, String zookeeper) {
        super();
        this.stateDir = stateDir;
        this.kafka = kafka;
        this.zookeeper = zookeeper;
    }

    public void start() {
        streams = createKafkaStreams(stateDir, kafka, zookeeper);
        streams.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();
            }
        });

        thread = new Thread() {
            public void run() {
                streams.start();
            }
        };
        thread.start();
    }

    public void close() {
        streams.close();
        try {
            thread.join();
        } catch (Exception ex) {
            // ignore
        }
    }

    private static KafkaStreams createKafkaStreams(File stateDir, String kafka, String zookeeper) {
        Properties props = new Properties();
        props.put(StreamsConfig.JOB_ID_CONFIG, "SmokeTest");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafka);
        props.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, zookeeper);
        props.put(StreamsConfig.TIMESTAMP_EXTRACTOR_CLASS_CONFIG, TestTimestampExtractor.class);
        props.put(StreamsConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(StreamsConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(StreamsConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(StreamsConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 2);
        props.put(StreamsConfig.BUFFERED_RECORDS_PER_PARTITION_CONFIG, 100);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KStreamBuilder builder = new KStreamBuilder();

        KStream<String, Integer> source = builder.stream(stringDeserializer, integerDeserializer, "data");

        source.to("echo", stringSerializer, integerSerializer);

        KStream<String, Integer> data = source.filter(new Predicate<String, Integer>() {
            @Override
            public boolean test(String key, Integer value) {
                return value == null || value != END;
            }
        });

        data.process(SmokeTestUtil.<Integer>printProcessorSupplier("data"));

        // min
        data.aggregateByKey(
                new Initializer<Integer>() {
                    public Integer apply() {
                        return Integer.MAX_VALUE;
                    }
                },
                new Aggregator<String, Integer, Integer>() {
                    @Override
                    public Integer apply(String aggKey, Integer value, Integer aggregate) {
                        return (value < aggregate) ? value : aggregate;
                    }
                },
                UnlimitedWindows.of("uwin-min"),
                stringSerializer,
                integerSerializer,
                stringDeserializer,
                integerDeserializer
        ).toStream().map(
                new Unwindow<String, Integer>()
        ).to("min", stringSerializer, integerSerializer);

        KTable<String, Integer> minTable = builder.table(stringSerializer, integerSerializer, stringDeserializer, integerDeserializer, "min");
        minTable.toStream().process(SmokeTestUtil.<Integer>printProcessorSupplier("min"));

        // max
        data.aggregateByKey(
                new Initializer<Integer>() {
                    public Integer apply() {
                        return Integer.MIN_VALUE;
                    }
                },
                new Aggregator<String, Integer, Integer>() {
                    @Override
                    public Integer apply(String aggKey, Integer value, Integer aggregate) {
                        return (value > aggregate) ? value : aggregate;
                    }
                },
                UnlimitedWindows.of("uwin-max"),
                stringSerializer,
                integerSerializer,
                stringDeserializer,
                integerDeserializer
        ).toStream().map(
                new Unwindow<String, Integer>()
        ).to("max", stringSerializer, integerSerializer);

        KTable<String, Integer> maxTable = builder.table(stringSerializer, integerSerializer, stringDeserializer, integerDeserializer, "max");
        maxTable.toStream().process(SmokeTestUtil.<Integer>printProcessorSupplier("max"));

        // sum
        data.aggregateByKey(
                new Initializer<Long>() {
                    public Long apply() {
                        return 0L;
                    }
                },
                new Aggregator<String, Integer, Long>() {
                    @Override
                    public Long apply(String aggKey, Integer value, Long aggregate) {
                        return (long) value + aggregate;
                    }
                },
                UnlimitedWindows.of("win-sum"),
                stringSerializer,
                longSerializer,
                stringDeserializer,
                longDeserializer
        ).toStream().map(
                new Unwindow<String, Long>()
        ).to("sum", stringSerializer, longSerializer);


        KTable<String, Long> sumTable = builder.table(stringSerializer, longSerializer, stringDeserializer, longDeserializer, "sum");
        sumTable.toStream().process(SmokeTestUtil.<Long>printProcessorSupplier("sum"));

        // cnt
        data.countByKey(
                UnlimitedWindows.of("uwin-cnt"),
                stringSerializer,
                longSerializer,
                stringDeserializer,
                longDeserializer
        ).toStream().map(
                new Unwindow<String, Long>()
        ).to("cnt", stringSerializer, longSerializer);

        KTable<String, Long> cntTable = builder.table(stringSerializer, longSerializer, stringDeserializer, longDeserializer, "cnt");
        cntTable.toStream().process(SmokeTestUtil.<Long>printProcessorSupplier("cnt"));

        // dif
        maxTable.join(minTable,
                new ValueJoiner<Integer, Integer, Integer>() {
                    public Integer apply(Integer value1, Integer value2) {
                        return value1 - value2;
                    }
                }
        ).to("dif", stringSerializer, integerSerializer);

        // avg
        sumTable.join(
                cntTable,
                new ValueJoiner<Long, Long, Double>() {
                    public Double apply(Long value1, Long value2) {
                        return (double) value1 / (double) value2;
                    }
                }
        ).to("avg", stringSerializer, doubleSerializer);

        // windowed count
        data.countByKey(
                TumblingWindows.of("tumbling-win-cnt").with(WINDOW_SIZE),
                stringSerializer,
                longSerializer,
                stringDeserializer,
                longDeserializer
        ).toStream().map(
                new KeyValueMapper<Windowed<String>, Long, KeyValue<String, Long>>() {
                    @Override
                    public KeyValue<String, Long> apply(Windowed<String> key, Long value) {
                        return new KeyValue<>(key.value() + "@" + key.window().start(), value);
                    }
                }
        ).to("wcnt", stringSerializer, longSerializer);

        return new KafkaStreams(builder, props);
    }

}
