package com.deltaproto.deltagerber.classify;

/**
 * The {@code .GenerationSoftware} file attribute: the tool that wrote a Gerber file.
 *
 * @param vendor      e.g. "Ucamco", "Altium Limited"
 * @param application e.g. "UcamX", "Altium Designer"
 * @param version     e.g. "2017.04", "25.1.2 (22)"
 */
public record GenerationSoftware(String vendor, String application, String version) {
}
