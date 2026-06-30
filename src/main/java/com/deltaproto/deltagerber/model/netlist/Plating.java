package com.deltaproto.deltagerber.model.netlist;

/**
 * Plating state of a hole in an IPC-D-356A test record (column 38 of the hole-definition field).
 */
public enum Plating {
    /** {@code P} — plated through-hole. */
    PLATED,
    /** {@code U} — unplated through-hole. */
    UNPLATED,
    /** Column left blank / no hole defined. */
    UNSPECIFIED
}
