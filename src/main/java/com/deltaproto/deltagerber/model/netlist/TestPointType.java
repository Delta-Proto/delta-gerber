package com.deltaproto.deltagerber.model.netlist;

/**
 * The kind of feature an IPC-D-356A test record describes, derived from the operation code
 * (columns 1-3) of the record.
 */
public enum TestPointType {
    /** {@code 317} — through-hole feature / point. */
    THROUGH_HOLE,
    /** {@code 327} — surface-mount (SMD) feature. */
    SMD,
    /** {@code 367} — non-plated tooling hole. */
    TOOLING_HOLE,
    /** {@code 307} — blind or buried via. */
    VIA,
    /** Any other {@code 3x7} operation code. */
    OTHER;

    /** Map an operation code (e.g. {@code "317"}) to its test-point type. */
    public static TestPointType fromOpCode(String opCode) {
        switch (opCode) {
            case "317": return THROUGH_HOLE;
            case "327": return SMD;
            case "367": return TOOLING_HOLE;
            case "307": return VIA;
            default:    return OTHER;
        }
    }
}
