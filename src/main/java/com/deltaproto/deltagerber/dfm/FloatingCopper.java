package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * One piece of copper on a layer that nothing connects to: no pad, no plated hole, no solder-mask
 * opening anywhere on it. Copper text and logos are the usual source; a pour fragment left behind by
 * a clearance, or a trace stub whose pad was deleted, are the ones worth fixing.
 *
 * @param xMm         the centre of the piece's bounding box, in the Gerber frame — the point
 *                    HQDFM reports as the position of the same finding
 * @param yMm         the centre, y
 * @param widthMm     the piece's extent in x
 * @param heightMm    the piece's extent in y
 * @param objectCount how many drawn objects make up the piece — one for a lone stub or pour
 *                    fragment, dozens for a word of copper text
 * @param net         the piece's net: its {@code .N} name when the file has one, else {@code net#k}
 * @param feature     the first object of the piece, to find it in the file
 */
@Beta("validated against HQDFM on two boards; see issue #11")
public record FloatingCopper(double xMm, double yMm, double widthMm, double heightMm,
                             int objectCount, String net, String feature) {

    @Override
    public String toString() {
        return String.format(Locale.US, "floating %s at (%.3f, %.3f), %.2f x %.2f mm, %d object%s",
                net, xMm, yMm, widthMm, heightMm, objectCount, objectCount == 1 ? "" : "s");
    }
}
