/*
 * Copyright (c) 2015-2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 */
package org.openbmp;

import kafka.consumer.ConsumerIterator;
import kafka.message.MessageAndMetadata;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.openbmp.api.parsed.message.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbmp.mysqlquery.BaseAttributeQuery;
import org.openbmp.mysqlquery.BmpStatQuery;
import org.openbmp.mysqlquery.CollectorQuery;
import org.openbmp.mysqlquery.LsLinkQuery;
import org.openbmp.mysqlquery.LsNodeQuery;
import org.openbmp.mysqlquery.LsPrefixQuery;
import org.openbmp.mysqlquery.PeerQuery;
import org.openbmp.mysqlquery.Query;
import org.openbmp.mysqlquery.RouterQuery;
import org.openbmp.mysqlquery.UnicastPrefixQuery;
import sun.security.util.BigInt;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * MySQL Consumer class
 *
 *   A thread to process a topic partition.  Supports all openbmp.parsed.* topics.
 */
public class MySQLConsumerRunnable implements Runnable {
    private final Integer FIFO_QUEUE_SIZE = 20000;          // Size of the FIFO queue

    private Boolean running;

    private ExecutorService executor;
    private MySQLWriterRunnable writerThread;
    private Long last_collector_msg_time;


    private KafkaConsumer<String, String> consumer;
    private Properties props;
    private List<String> topics;
    private Config cfg;
    private Map<String,Map<String, Integer>> routerConMap;

    private BigInteger messageCount;
    private long collector_msg_count;
    private long router_msg_count;
    private long peer_msg_count;
    private long base_attribute_msg_count;
    private long unicast_prefix_msg_count;
    private long ls_node_msg_count;
    private long ls_link_msg_count;
    private long ls_prefix_msg_count;
    private long stat_msg_count;

    /*
     * FIFO queue for SQL messages to be written/inserted
     *      Queue message:
     *          Object is a hash map where the key is:
     *              prefix:     Insert statement including the VALUES keyword
     *              suffix:     ON DUPLICATE KEY UPDATE suffix, can be empty if not used
     *              value:      Comma delimited set of VALUES
     */
    private BlockingQueue<Map<String, String>> writerQueue;


    private static final Logger logger = LogManager.getFormatterLogger(MySQLConsumerRunnable.class.getName());

    /**
     * Constructor
     *
     * @param props                Kafka properties/configuration
     * @param topics               Topics to subscribe to
     * @param cfg                  Configuration from cli/config file
     * @param routerConMap         Persistent router state tracking
     */
    public MySQLConsumerRunnable(Properties props, List<String> topics, Config cfg,
                                 Map<String,Map<String, Integer>> routerConMap) {


        messageCount = BigInteger.valueOf(0);
        this.topics = topics;
        this.props = props;
        this.cfg = cfg;
        this.routerConMap = routerConMap;

        /*
         * Start MySQL Writer thread - only one thread is needed
         */
        executor = Executors.newFixedThreadPool(1);
        writerQueue = new ArrayBlockingQueue(FIFO_QUEUE_SIZE);
        writerThread = new MySQLWriterRunnable(cfg, writerQueue);
        executor.submit(writerThread);
    }

    /**
     * Shutdown this thread and its threads
     */
    public void shutdown() {
        logger.debug("MySQL consumer thread shutting down");

        writerThread.shutdown();

        if (executor != null) executor.shutdown();

        try {
            if (!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                logger.warn("Timed out waiting for writer thread to shut down, exiting uncleanly");
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during shutdown, exiting uncleanly");
        }

        if (consumer != null)
            consumer.close();

        synchronized (running) {
            running = false;
        }
    }

    /**
     * Connect to Kafka
     *
     * @return  True if connected, false if not connected
     */
    private boolean connect() {
        boolean status = false;

        try {
            consumer = new KafkaConsumer<>(this.props);
            logger.info("Connected to kafka, subscribing to topics");

            org.apache.kafka.clients.consumer.ConsumerRebalanceListener rebalanceListener =
                    new ConsumerRebalanceListener(consumer);

            consumer.subscribe(topics,  rebalanceListener);
            for (String topic: topics) {
                logger.info("Subscribed to topic: %s", topic);
            }

            status = true;

        } catch (ConfigException ex) {
            logger.error("Config Exception: %s", ex.getMessage());

        } catch (KafkaException ex) {
            logger.error("Exception: %s", ex.getMessage(), ex);
        } finally {
            return status;
        }

    }

    /**
     * Run the thread
     */
    public void run() {
        logger.info("Consumer started");

        if (! writerThread.isDbConnected()) {
            logger.warn("Ignoring request to run thread since DB connection couldn't be established");
            return;
        }

        if (connect() == false) {
            logger.error("Failed to connect to Kafka, consumer exiting");
            running = false;
        } else {
            logger.debug("Conected and now consuming messages from kafka");
            running = true;
        }


        /*
         * Continuously read from Kafka stream and parse messages
         */
        Map<String, String> query;
        long prev_time = System.currentTimeMillis();

        while (true) {

            if (running == false) {
                try {
                    Thread.sleep(1000);

                } catch (InterruptedException e) {
                    break;
                }

                running = connect();
                continue;
            }

            try {
                ConsumerRecords<String, String> records = consumer.poll(500);

                logger.debug("feteched records: %d", records.count());

                for (ConsumerRecord<String, String> record : records) {
                    messageCount = messageCount.add(BigInteger.ONE);

                    //Extract the Headers and Content from the message.
                    Message message = new Message(record.value());

                    Base obj = null;
                    Query dbQuery = null;

                    /*
                     * Parse the data based on topic
                     */
                    query = new HashMap<String, String>();
                    if (record.topic().equals("openbmp.parsed.collector")) {
                        logger.trace("Parsing collector message");
                        collector_msg_count++;

                        Collector collector = new Collector(message.getContent());
                        CollectorQuery collectorQuery = new CollectorQuery(collector.getRowMap());
                        obj = collector;
                        dbQuery = collectorQuery;

                        last_collector_msg_time = System.currentTimeMillis();

                        // Disconnect the routers
                        String sql = collectorQuery.genRouterCollectorUpdate(routerConMap);

                        if (sql != null && !sql.isEmpty()) {
                            logger.debug("collectorUpdate: %s", sql);

                            Map<String, String> router_update = new HashMap<>();
                            router_update.put("query", sql);

                            // block if space is not available
                            try {
                                logger.debug("Added router disconnect correction to queue: size = %d", writerQueue.size());
                                writerQueue.put(router_update);

                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                    } else if (record.topic().equals("openbmp.parsed.router")) {
                        logger.trace("Parsing router message");
                        router_msg_count++;

                        Router router = new Router(message.getVersion(), message.getContent());
                        RouterQuery routerQuery = new RouterQuery(message, router.getRowMap());
                        obj = router;
                        dbQuery = routerQuery;

                        // Disconnect the peers
                        String sql = routerQuery.genPeerRouterUpdate(routerConMap);

                        if (sql != null && !sql.isEmpty()) {
                            logger.debug("RouterUpdate = %s", sql);

                            Map<String, String> peer_update = new HashMap<>();
                            peer_update.put("query", sql);

                            // block if space is not available
                            try {
                                logger.debug("Added peer disconnect correction to queue: size = %d", writerQueue.size());
                                writerQueue.put(peer_update);

                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                    } else if (record.topic().equals("openbmp.parsed.peer")) {
                        logger.trace("Parsing peer message");
                        peer_msg_count++;

                        Peer peer = new Peer(message.getContent());
                        PeerQuery peerQuery = new PeerQuery(peer.getRowMap());
                        obj = peer;
                        dbQuery = peerQuery;

                        // Add the withdrawn
                        Map<String, String> rib_update = new HashMap<String, String>();
                        rib_update.put("query", peerQuery.genRibPeerUpdate());

                        // block if space is not available
                        try {
                            logger.debug("Processed peer %s / %s", peerQuery.genValuesStatement(), peerQuery.genRibPeerUpdate());
                            logger.debug("Added peer rib update message to queue: size = %d", writerQueue.size());
                            writerQueue.put(rib_update);

                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                    } else if (record.topic().equals("openbmp.parsed.base_attribute")) {
                        logger.trace("Parsing base_attribute message");
                        base_attribute_msg_count++;

                        BaseAttribute attr_obj = new BaseAttribute(message.getContent());
                        BaseAttributeQuery baseAttrQuery = new BaseAttributeQuery(attr_obj.getRowMap());
                        obj = attr_obj;
                        dbQuery = baseAttrQuery;

                        // Add as_path_analysis entries
                        String values = baseAttrQuery.genAsPathAnalysisValuesStatement();

                        if (values.length() > 0) {
                            Map<String, String> analysis_query = new HashMap<String, String>();
                            String[] ins = baseAttrQuery.genAsPathAnalysisStatement();
                            analysis_query.put("prefix", ins[0]);
                            analysis_query.put("suffix", ins[1]);

                            analysis_query.put("value", values);

                            // block if space is not available
                            try {
                                logger.trace("Added as_path_analysis message to queue: size = %d", writerQueue.size());
                                writerQueue.put(analysis_query);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // add community_analysis
                        values = baseAttrQuery.genCommunityAnalysisValuesStatement();
                        if (values.length() > 0) {
                            Map<String, String> analysis_query = new HashMap<>();
                            String[] ins = baseAttrQuery.genCommunityAnalysisStatement();
                            analysis_query.put("prefix", ins[0]);
                            analysis_query.put("suffix", ins[1]);

                            analysis_query.put("value", values);
                            try {
                                logger.trace("Added community_analysis message to queue: size = %d", writerQueue.size());
                                writerQueue.put(analysis_query);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                    } else if (record.topic().equals("openbmp.parsed.unicast_prefix")) {
                        logger.trace("Parsing unicast_prefix message");
                        unicast_prefix_msg_count++;

                        obj = new UnicastPrefix(message.getVersion(), message.getContent());
                        dbQuery = new UnicastPrefixQuery(obj.getRowMap());

                    } else if (record.topic().equals("openbmp.parsed.bmp_stat")) {
                        logger.trace("Parsing bmp_stat message");
                        stat_msg_count++;

                        obj = new BmpStat(message.getContent());
                        dbQuery = new BmpStatQuery(obj.getRowMap());

                    } else if (record.topic().equals("openbmp.parsed.ls_node")) {
                        logger.trace("Parsing ls_node message");
                        ls_node_msg_count++;

                        obj = new LsNode(message.getVersion(), message.getContent());
                        dbQuery = new LsNodeQuery(obj.getRowMap());


                    } else if (record.topic().equals("openbmp.parsed.ls_link")) {
                        logger.trace("Parsing ls_link message");
                        ls_link_msg_count++;

                        obj = new LsLink(message.getVersion(), message.getContent());
                        dbQuery = new LsLinkQuery(obj.getRowMap());

                    } else if (record.topic().equals("openbmp.parsed.ls_prefix")) {
                        logger.trace("Parsing ls_prefix message");
                        ls_prefix_msg_count++;

                        obj = new LsPrefix(message.getVersion(), message.getContent());
                        dbQuery = new LsPrefixQuery(obj.getRowMap());

                    } else {
                        logger.debug("Topic %s not implemented, ignoring", record.topic());
                        return;
                    }

                    /*
                     * Add query to writer queue
                     */
                    if (obj != null) {
                        String values = dbQuery.genValuesStatement();

                        if (values.length() > 0) {
                            // Add statement and value to query map
                            String[] ins = dbQuery.genInsertStatement();
                            query.put("prefix", ins[0]);
                            query.put("suffix", ins[1]);
                            query.put("value", values);

                            // block if space is not available
                            try {
                                writerQueue.put(query);

                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }

                if (writerQueue.size() > 500 && System.currentTimeMillis() - prev_time > 10000) {
                    logger.info("Thread writer queue is %d", writerQueue.size());
                    prev_time = System.currentTimeMillis();
                }
            } catch (Exception ex) {
                logger.warn("kafka consumer exception: ", ex);
                consumer.close();
                running = false;
            }

        }

        shutdown();
        logger.debug("MySQL consumer thread finished");
    }

    public synchronized boolean isRunning() { return running; }
    public synchronized BigInteger getMessageCount() { return messageCount; }
    public synchronized Integer getQueueSize() { return writerQueue.size(); }
    public synchronized Long getLast_collector_msg_time() { return last_collector_msg_time; }

    public long getCollector_msg_count() {
        return collector_msg_count;
    }

    public long getRouter_msg_count() {
        return router_msg_count;
    }

    public long getPeer_msg_count() {
        return peer_msg_count;
    }

    public long getBase_attribute_msg_count() {
        return base_attribute_msg_count;
    }

    public long getUnicast_prefix_msg_count() {
        return unicast_prefix_msg_count;
    }

    public long getLs_node_msg_count() {
        return ls_node_msg_count;
    }

    public long getLs_link_msg_count() {
        return ls_link_msg_count;
    }

    public long getLs_prefix_msg_count() {
        return ls_prefix_msg_count;
    }

    public long getStat_msg_count() {
        return stat_msg_count;
    }
}