package com.deltaproto.deltagerber.edit;

import com.deltaproto.deltagerber.renderer.step.StrokeFont;

import java.util.ArrayList;
import java.util.List;

/**
 * Text as single pen strokes, ready to be drawn as Gerber D01 draws with a round aperture — a
 * stencil's legend, say. It is the same font the STEP export labels its faces with
 * ({@link StrokeFont}): A–Z, 0–9 and {@code - _ . /}, lower case drawn as upper case, anything else
 * (a space included) leaving a gap of one character.
 */
public final class StrokeText {

    private StrokeText() {
    }

    /**
     * The text as line segments {@code {x1, y1, x2, y2}} in mm, left-aligned at {@code x} with its
     * baseline on {@code y}, capitals {@code heightMm} tall. The strokes are centrelines: the
     * aperture they are drawn with adds half its width all round.
     */
    public static List<double[]> segments(String text, double x, double y, double heightMm) {
        List<double[]> out = new ArrayList<>();
        for (double[][] stroke : StrokeFont.strokes(text, x, y, heightMm, false)) {
            for (int i = 0; i + 1 < stroke.length; i++) {
                out.add(new double[]{stroke[i][0], stroke[i][1], stroke[i + 1][0], stroke[i + 1][1]});
            }
        }
        return out;
    }

    /** The advance width of the text at that cap height, mm (centrelines, without the pen). */
    public static double width(String text, double heightMm) {
        return StrokeFont.width(text, heightMm);
    }
}
