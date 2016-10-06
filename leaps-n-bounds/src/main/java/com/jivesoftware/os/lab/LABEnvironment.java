package com.jivesoftware.os.lab;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jivesoftware.os.jive.utils.collections.bah.LRUConcurrentBAHLinkedHash;
import com.jivesoftware.os.lab.api.FormatTransformerProvider;
import com.jivesoftware.os.lab.api.MemoryRawEntryFormat;
import com.jivesoftware.os.lab.api.NoOpFormatTransformerProvider;
import com.jivesoftware.os.lab.api.RawEntryFormat;
import com.jivesoftware.os.lab.api.ValueIndex;
import com.jivesoftware.os.lab.api.ValueIndexConfig;
import com.jivesoftware.os.lab.api.rawhide.KeyValueRawhide;
import com.jivesoftware.os.lab.api.rawhide.LABRawhide;
import com.jivesoftware.os.lab.api.rawhide.Rawhide;
import com.jivesoftware.os.lab.guts.LABCSLMIndex;
import com.jivesoftware.os.lab.guts.LABIndexProvider;
import com.jivesoftware.os.lab.guts.Leaps;
import com.jivesoftware.os.lab.guts.StripingBolBufferLocks;
import com.jivesoftware.os.lab.guts.allocators.LABAppendOnlyAllocator;
import com.jivesoftware.os.lab.guts.allocators.LABConcurrentSkipListMap;
import com.jivesoftware.os.lab.guts.allocators.LABConcurrentSkipListMemory;
import com.jivesoftware.os.lab.guts.allocators.LABIndexableMemory;
import com.jivesoftware.os.lab.io.BolBuffer;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;

/**
 * @author jonathan.colt
 */
public class LABEnvironment {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final File labRoot;
    private final LABStats stats;
    private final ExecutorService scheduler;
    private final ExecutorService compact;
    private final ExecutorService destroy;
    private final LabHeapPressure labHeapPressure;
    private final int minMergeDebt;
    private final int maxMergeDebt;
    private final LRUConcurrentBAHLinkedHash<Leaps> leapsCache;
    private final boolean useIndexableMemory;
    private final boolean fsyncFileRenames;

    private final String metaName;
    private final LabMeta meta;

    private final String walName;
    private final LabWAL wal;
    private final Map<String, FormatTransformerProvider> formatTransformerProviderRegistry = Maps.newConcurrentMap();
    private final Map<String, Rawhide> rawhideRegistry = Maps.newConcurrentMap();
    private final Map<String, RawEntryFormat> rawEntryFormatRegistry = Maps.newConcurrentMap();

    private final StripingBolBufferLocks stripingBolBufferLocks;

    public static ExecutorService buildLABHeapSchedulerThreadPool(int count) {
        return Executors.newFixedThreadPool(count,
            new ThreadFactoryBuilder().setNameFormat("lab-heap-%d").build());
    }

    public static ExecutorService buildLABSchedulerThreadPool(int count) {
        return Executors.newFixedThreadPool(count,
            new ThreadFactoryBuilder().setNameFormat("lab-scheduler-%d").build());
    }

    public static ExecutorService buildLABCompactorThreadPool(int count) {
        return Executors.newFixedThreadPool(count,
            new ThreadFactoryBuilder().setNameFormat("lab-compact-%d").build());
    }

    public static ExecutorService buildLABDestroyThreadPool(int count) {
        return Executors.newFixedThreadPool(count,
            new ThreadFactoryBuilder().setNameFormat("lab-destroy-%d").build());
    }

    public static LRUConcurrentBAHLinkedHash<Leaps> buildLeapsCache(int maxCapacity, int concurrency) {
        return new LRUConcurrentBAHLinkedHash<>(10, maxCapacity, 0.5f, true, concurrency);
    }

    public LABEnvironment(LABStats stats,
        ExecutorService scheduler,
        ExecutorService compact,
        final ExecutorService destroy,
        String walName,
        String metaName,
        long maxWALSizeInBytes,
        long maxEntriesPerWAL,
        long maxEntrySizeInBytes,
        long maxValueIndexHeapPressureOverride,
        File labRoot,
        LabHeapPressure labHeapPressure,
        int minMergeDebt,
        int maxMergeDebt,
        LRUConcurrentBAHLinkedHash<Leaps> leapsCache,
        StripingBolBufferLocks bolBufferLocks,
        boolean useIndexableMemory,
        boolean fsyncFileRenames) throws Exception {

        register(NoOpFormatTransformerProvider.NAME, NoOpFormatTransformerProvider.NO_OP);
        register(KeyValueRawhide.NAME, KeyValueRawhide.SINGLETON);
        register(LABRawhide.NAME, LABRawhide.SINGLETON);
        register(MemoryRawEntryFormat.NAME, MemoryRawEntryFormat.SINGLETON);

        this.stats = stats;
        this.scheduler = scheduler;
        this.compact = compact;
        this.destroy = destroy;
        this.labRoot = labRoot;
        this.labHeapPressure = labHeapPressure;
        this.minMergeDebt = minMergeDebt;
        this.maxMergeDebt = maxMergeDebt;
        this.leapsCache = leapsCache;
        this.metaName = metaName;
        this.meta = new LabMeta(new File(labRoot, metaName));
        this.walName = walName;
        this.wal = new LabWAL(stats, new File(labRoot, walName), maxWALSizeInBytes, maxEntriesPerWAL, maxEntrySizeInBytes, maxValueIndexHeapPressureOverride);
        this.useIndexableMemory = useIndexableMemory;
        this.fsyncFileRenames = fsyncFileRenames;
        this.stripingBolBufferLocks = bolBufferLocks;
    }

    LabWAL getLabWAL() {
        return wal;
    }

    public void register(String name, FormatTransformerProvider formatTransformerProvider) {
        FormatTransformerProvider had = formatTransformerProviderRegistry.putIfAbsent(name, formatTransformerProvider);
        if (had != null) {
            throw new IllegalArgumentException("FormatTransformerProvider:" + had + " is already register under the name:" + name);
        }
    }

    FormatTransformerProvider formatTransformerProvider(String name) {
        return formatTransformerProviderRegistry.get(name);
    }

    public void register(String name, Rawhide rawhide) {
        Rawhide had = rawhideRegistry.putIfAbsent(name, rawhide);
        if (had != null) {
            throw new IllegalArgumentException("Rawhide:" + had + " is already register under the name:" + name);
        }
    }

    Rawhide rawhide(String name) {
        return rawhideRegistry.get(name);
    }

    public void register(String name, RawEntryFormat rawEntryFormat) {
        RawEntryFormat had = rawEntryFormatRegistry.putIfAbsent(name, rawEntryFormat);
        if (had != null) {
            throw new IllegalArgumentException("RawEntryFormat:" + had + " is already register under the name:" + name);
        }
    }

    RawEntryFormat rawEntryFormat(String name) {
        return rawEntryFormatRegistry.get(name);
    }

    public void open() throws Exception {
        this.wal.open(this);
    }

    public void close() throws Exception {
        this.wal.close(this);
        this.meta.close();
    }

    public List<String> list() throws Exception {
        List<String> indexes = Lists.newArrayList();
        meta.metaKeys((metaKey) -> {
            indexes.add(new String(metaKey, StandardCharsets.UTF_8));
            return true;
        });
        return indexes;
    }

    public boolean exists(String name) {
        if (name.equals(walName)) {
            return false;
        }
        if (name.equals(metaName)) {
            return false;
        }
        File labDir = new File(labRoot, name);
        return labDir.exists();
    }

    ValueIndexConfig valueIndexConfig(byte[] valueIndexId) throws Exception {
        ValueIndexConfig config = meta.get(valueIndexId, (metaValue) -> {
            if (metaValue != null) {
                return MAPPER.readValue(metaValue.copy(), ValueIndexConfig.class);
            } else {
                return null;
            }
        });
        if (config == null) {
            // Fallback to the old way :(
            String primaryName = new String(valueIndexId, StandardCharsets.UTF_8);
            File configFile = new File(labRoot, primaryName + ".json");
            if (configFile.exists()) {
                config = MAPPER.readValue(configFile, ValueIndexConfig.class);
                meta.append(valueIndexId, MAPPER.writeValueAsBytes(config));
                FileUtils.deleteQuietly(configFile);
            } else {
                throw new IllegalStateException("There is no config for lab value index:" + new String(valueIndexId, StandardCharsets.UTF_8));
            }
        }
        return config;
    }

    public ValueIndex open(ValueIndexConfig config) throws Exception {

        if (config.primaryName.equals(walName)) {
            throw new IllegalStateException("primaryName:" + config.primaryName + " cannot collide with walName");
        }
        if (config.primaryName.equals(metaName)) {
            throw new IllegalStateException("primaryName:" + config.primaryName + " cannot collide with metaName");
        }

        FormatTransformerProvider formatTransformerProvider = formatTransformerProviderRegistry.get(config.formatTransformerProviderName);
        Preconditions.checkNotNull(formatTransformerProvider, "No FormatTransformerProvider registered for " + config.formatTransformerProviderName);
        Rawhide rawhide = rawhideRegistry.get(config.rawhideName);
        Preconditions.checkNotNull(formatTransformerProvider, "No Rawhide registered for " + config.rawhideName);
        RawEntryFormat rawEntryFormat = rawEntryFormatRegistry.get(config.rawEntryFormatName);
        Preconditions.checkNotNull(formatTransformerProvider, "No RawEntryFormat registered for " + config.rawEntryFormatName);

        byte[] valueIndexId = config.primaryName.getBytes(StandardCharsets.UTF_8);

        byte[] configAsBytes = MAPPER.writeValueAsBytes(config);
        boolean equal = meta.get(valueIndexId, (metaValue) -> {
            if (metaValue == null) {
                return false;
            } else {
                boolean equal1 = metaValue.length == configAsBytes.length;
                if (equal1) {
                    for (int i = 0; i < metaValue.length; i++) {
                        if (configAsBytes[i] != metaValue.get(i)) {
                            equal1 = false;
                            break;
                        }
                    }
                }
                return equal1;
            }
        });

        if (!equal) {
            meta.append(valueIndexId, configAsBytes);
        }

        LABIndexProvider indexProvider = (rawhide1, poweredUpToHint) -> {
            if (useIndexableMemory && config.entryLengthPower > 0) {
                LABAppendOnlyAllocator allocator = new LABAppendOnlyAllocator(config.primaryName,
                    Math.max(config.entryLengthPower, (poweredUpToHint - config.entryLengthPower) / 2)
                );
                LABIndexableMemory memory = new LABIndexableMemory(config.rawhideName, allocator);
                LABConcurrentSkipListMemory skipListMemory = new LABConcurrentSkipListMemory(rawhide1, memory);
                return new LABConcurrentSkipListMap(stats, skipListMemory, stripingBolBufferLocks);
            } else {
                return new LABCSLMIndex(rawhide1, stripingBolBufferLocks);
            }
        };

        return new LAB(stats,
            formatTransformerProvider,
            config.rawhideName,
            rawhide,
            rawEntryFormat,
            scheduler,
            compact,
            destroy,
            labRoot,
            wal,
            valueIndexId,
            config.primaryName,
            config.entriesBetweenLeaps,
            labHeapPressure,
            config.maxHeapPressureInBytes,
            minMergeDebt,
            maxMergeDebt,
            config.splitWhenKeysTotalExceedsNBytes,
            config.splitWhenValuesTotalExceedsNBytes,
            config.splitWhenValuesAndKeysTotalExceedsNBytes,
            leapsCache,
            indexProvider,
            fsyncFileRenames);

    }

    private static final byte[] EMPTY = new byte[0];

    public boolean rename(String oldName, String newName) throws Exception {
        File oldFileName = new File(labRoot, oldName);
        File newFileName = new File(labRoot, newName);
        if (oldFileName.exists()) {
            byte[] oldKey = oldName.getBytes(StandardCharsets.UTF_8);
            byte[] value = meta.get(oldKey, BolBuffer::copy);

            byte[] newKey = newName.getBytes(StandardCharsets.UTF_8);
            meta.append(newKey, value);

            Files.move(oldFileName.toPath(), newFileName.toPath(), StandardCopyOption.ATOMIC_MOVE);
            FileUtils.deleteDirectory(oldFileName);
            meta.append(oldKey, EMPTY);
            return true;
        } else {
            return false;
        }
    }

    public void remove(String primaryName) throws Exception {
        File fileName = new File(labRoot, primaryName);
        if (fileName.exists()) {
            FileUtils.deleteDirectory(fileName);
            byte[] metaKey = primaryName.getBytes(StandardCharsets.UTF_8);
            meta.append(metaKey, EMPTY);
        }
    }

    public void delete() throws IOException {
        FileUtils.deleteDirectory(labRoot);
    }

    public void shutdown() throws InterruptedException {
        compact.shutdown();
        destroy.shutdown();
        compact.awaitTermination(30, TimeUnit.SECONDS);
        destroy.awaitTermination(30, TimeUnit.SECONDS);
    }
}
