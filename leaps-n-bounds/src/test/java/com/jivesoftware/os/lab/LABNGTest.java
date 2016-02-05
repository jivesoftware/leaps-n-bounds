package com.jivesoftware.os.lab;

import com.google.common.io.Files;
import com.jivesoftware.os.lab.api.ValueIndex;
import com.jivesoftware.os.lab.io.api.UIO;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class LABNGTest {

    @Test
    public void testEnv() throws Exception {

        File root = Files.createTempDir();
        LABEnvironment env = new LABEnvironment(root, new LABValueMerger(), false, 4, 8);

        ValueIndex index = env.open("foo", 1000);

        index.append((stream) -> {
            stream.stream(UIO.longBytes(1), System.currentTimeMillis(), false, 0, UIO.longBytes(1));
            stream.stream(UIO.longBytes(2), System.currentTimeMillis(), false, 0, UIO.longBytes(2));
            stream.stream(UIO.longBytes(3), System.currentTimeMillis(), false, 0, UIO.longBytes(3));
            stream.stream(UIO.longBytes(7), System.currentTimeMillis(), false, 0, UIO.longBytes(7));
            stream.stream(UIO.longBytes(8), System.currentTimeMillis(), false, 0, UIO.longBytes(8));
            stream.stream(UIO.longBytes(9), System.currentTimeMillis(), false, 0, UIO.longBytes(9));
            return true;
        });

        Assert.assertFalse(index.isEmpty());

        long[] expected = new long[]{1, 2, 3, 7, 8, 9};
        testExpected(index, expected);
        testNotExpected(index, new long[]{0, 4, 5, 6, 10});
        testScanExpected(index, expected);
        testRangeScanExpected(index, UIO.longBytes(2), null, new long[]{2, 3, 7, 8, 9});
        testRangeScanExpected(index, UIO.longBytes(2), UIO.longBytes(7), new long[]{2, 3});
        testRangeScanExpected(index, UIO.longBytes(4), UIO.longBytes(7), new long[]{});

        index.commit();

        index.append((stream) -> {
            stream.stream(UIO.longBytes(1), System.currentTimeMillis(), true, 0, UIO.longBytes(1));
            stream.stream(UIO.longBytes(2), System.currentTimeMillis(), true, 0, UIO.longBytes(2));
            stream.stream(UIO.longBytes(3), System.currentTimeMillis(), true, 0, UIO.longBytes(3));
            return true;
        });

        expected = new long[]{7, 8, 9};
        testExpected(index, expected);
        testNotExpected(index, new long[]{0, 4, 5, 6, 10});
        testScanExpected(index, expected);
        testRangeScanExpected(index, UIO.longBytes(1), UIO.longBytes(9), new long[]{7, 8});

        env.shutdown();

    }

    private void testExpected(ValueIndex index, long[] expected) throws Exception {
        for (long i : expected) {
            long exected = i;
            index.get(UIO.longBytes(i), (nextValue) -> {
                return nextValue.next((byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                    Assert.assertEquals(UIO.bytesLong(payload), exected);
                    return true;
                });
            });
        }
    }

    private void testNotExpected(ValueIndex index, long[] notExpected) throws Exception {
        for (long i : notExpected) {
            index.get(UIO.longBytes(i), (nextValue) -> {
                return nextValue.next((byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                    if (key != null || payload != null) {
                        Assert.fail(Arrays.toString(key) + " " + timestamp + " " + tombstoned + " " + version + " " + Arrays.toString(payload));
                    }
                    return true;
                });
            });
        }
    }

    private void testScanExpected(ValueIndex index, long[] expected) throws Exception {
        List<Long> scanned = new ArrayList<>();
        index.rowScan((nextValue) -> {
            return nextValue.next((byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                System.out.println("scan:" + Arrays.toString(key) + " " + timestamp + " " + tombstoned + " " + version + " " + Arrays.toString(payload));
                if (payload != null) {
                    scanned.add(UIO.bytesLong(payload));
                }
                return true;
            });
        });
        Assert.assertEquals(scanned.size(), expected.length);
        for (int i = 0; i < expected.length; i++) {
            Assert.assertEquals((long) scanned.get(i), expected[i]);
        }
    }

    private void testRangeScanExpected(ValueIndex index, byte[] from, byte[] to, long[] expected) throws Exception {
        List<Long> scanned = new ArrayList<>();
        index.rangeScan(from, to, (nextValue) -> {
            return nextValue.next((byte[] key, long timestamp, boolean tombstoned, long version, byte[] payload) -> {
                System.out.println("scan:" + Arrays.toString(key) + " " + timestamp + " " + tombstoned + " " + version + " " + Arrays.toString(payload));
                if (payload != null) {
                    scanned.add(UIO.bytesLong(payload));
                }
                return true;
            });
        });
        Assert.assertEquals(scanned.size(), expected.length);
        for (int i = 0; i < expected.length; i++) {
            Assert.assertEquals((long) scanned.get(i), expected[i]);
        }
    }

}
