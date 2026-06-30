package com.deltaproto.deltagerber.model.netlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Net adjacency data ({@code 379} / {@code 079} continuation): the list of nets that could
 * possibly be shorted to the initial net (typically those within a minimum feature separation).
 *
 * <p>Adjacency is one-directional in the file — if {@code NET1}'s list contains {@code POWER}, then
 * {@code POWER}'s list need not repeat {@code NET1}. Net names here are resolved through any
 * {@code NNAME} aliases, like everywhere else.
 */
public class Adjacency {

    private final String netName;
    private final String rawNetName;
    private final List<String> adjacentNets = new ArrayList<>();

    public Adjacency(String netName, String rawNetName) {
        this.netName = netName;
        this.rawNetName = rawNetName;
    }

    public void addAdjacentNet(String net) {
        if (net != null && !net.isEmpty()) adjacentNets.add(net);
    }

    public String getNetName() { return netName; }
    public String getRawNetName() { return rawNetName; }

    public List<String> getAdjacentNets() {
        return Collections.unmodifiableList(adjacentNets);
    }

    @Override
    public String toString() {
        return String.format("Adjacency[%s ~ %s]", netName, adjacentNets);
    }
}
