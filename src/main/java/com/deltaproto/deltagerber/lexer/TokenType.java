package com.deltaproto.deltagerber.lexer;

/**
 * Token types for Gerber lexer.
 */
public enum TokenType {
    // Format specification
    FORMAT_SPEC,        // %FSLAX...%
    UNIT,               // %MOMM*% or %MOIN*%

    // Aperture commands
    APERTURE_DEFINE,    // %ADDnn...%
    APERTURE_MACRO,     // %AM...%
    APERTURE_SELECT,    // Dnn*

    // Operation codes
    D01,                // Interpolate (draw)
    D02,                // Move
    D03,                // Flash

    // Interpolation modes
    G01,                // Linear interpolation
    G02,                // Clockwise circular
    G03,                // Counter-clockwise circular
    G74,                // Single quadrant mode
    G75,                // Multi quadrant mode

    // Region mode
    G36,                // Region start
    G37,                // Region end

    // Coordinate
    COORDINATE,         // X...Y...I...J...

    // Polarity
    POLARITY,           // %LPD*% or %LPC*%

    // Aperture transforms
    LOAD_ROTATION,      // %LR<angle>*%
    LOAD_SCALING,       // %LS<factor>*%
    LOAD_MIRRORING,     // %LM<mode>*% (N/X/Y/XY)

    // Step and repeat
    STEP_REPEAT,        // %SR...%

    // Block aperture
    BLOCK_APERTURE,     // %AB...%

    // Attributes
    FILE_ATTRIBUTE,     // %TF...%
    APERTURE_ATTRIBUTE, // %TA...%
    OBJECT_ATTRIBUTE,   // %TO...%
    DELETE_ATTRIBUTE,   // %TD...%

    // Miscellaneous
    COMMENT,            // G04 ...
    END_OF_FILE,        // M02* or M00*

    // Other
    UNKNOWN
}
