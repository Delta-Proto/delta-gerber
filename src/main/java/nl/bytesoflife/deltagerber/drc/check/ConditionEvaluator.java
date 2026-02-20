package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import nl.bytesoflife.deltagerber.model.gerber.operation.Flash;
import nl.bytesoflife.deltagerber.model.gerber.operation.GraphicsObject;
import nl.bytesoflife.deltagerber.model.gerber.operation.Region;

public class ConditionEvaluator {

    public enum Result {
        APPLICABLE,
        NOT_APPLICABLE,
        UNSUPPORTED
    }

    public Result evaluate(String condition) {
        if (condition == null || condition.isEmpty()) {
            return Result.APPLICABLE;
        }

        // Net-based conditions are unsupported (no netlist in Gerber)
        if (condition.contains("A.Net") || condition.contains("B.Net") ||
            condition.contains("A.NetClass") || condition.contains("B.NetClass")) {
            return Result.UNSUPPORTED;
        }

        // Plated/unplated distinction unsupported
        if (condition.contains("isPlated()")) {
            return Result.UNSUPPORTED;
        }

        // Via type unsupported (can't distinguish from pad in Gerber)
        if (condition.contains("'via'") || condition.contains("'Via'") ||
            condition.contains("== 'via'") || condition.contains("!= 'Via'")) {
            return Result.UNSUPPORTED;
        }

        // Pad type, fabrication property unsupported
        if (condition.contains("A.Pad_Type") || condition.contains("A.Fabrication_Property") ||
            condition.contains("A.Hole_Size_X") || condition.contains("A.Hole_Size_Y")) {
            return Result.UNSUPPORTED;
        }

        // B.Type references (pair-based conditions) - partially supported
        if (condition.contains("B.Type")) {
            return Result.UNSUPPORTED;
        }

        // Simple type conditions we can evaluate
        if (condition.contains("A.Type == 'track'")) {
            return Result.APPLICABLE;
        }
        if (condition.contains("A.Type == 'pad'") || condition.contains("A.Type == 'Pad'")) {
            return Result.APPLICABLE;
        }

        // Any other condition we don't understand
        return Result.UNSUPPORTED;
    }

    public Result evaluateForObject(String condition, GraphicsObject obj) {
        Result baseResult = evaluate(condition);
        if (baseResult != Result.APPLICABLE) {
            return baseResult;
        }

        if (condition == null || condition.isEmpty()) {
            return Result.APPLICABLE;
        }

        String objectType = getObjectType(obj);

        if (condition.contains("A.Type == 'track'")) {
            return "track".equals(objectType) ? Result.APPLICABLE : Result.NOT_APPLICABLE;
        }
        if (condition.contains("A.Type == 'pad'") || condition.contains("A.Type == 'Pad'")) {
            return "pad".equals(objectType) ? Result.APPLICABLE : Result.NOT_APPLICABLE;
        }

        return Result.APPLICABLE;
    }

    public static String getObjectType(GraphicsObject obj) {
        if (obj instanceof Draw) return "track";
        if (obj instanceof Flash) return "pad";
        if (obj instanceof Region) return "zone";
        return "unknown";
    }
}
