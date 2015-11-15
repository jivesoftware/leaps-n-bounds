package com.jivesoftware.os.lab.api;

/**
 *
 * @author jonathan.colt
 */
public interface ScanFromFp {

    boolean next(long fp, RawEntryStream stream) throws Exception;

    boolean result();

    void reset();
}
