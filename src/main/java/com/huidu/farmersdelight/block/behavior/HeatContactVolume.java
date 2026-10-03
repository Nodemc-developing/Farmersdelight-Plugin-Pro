package com.huidu.farmersdelight.block.behavior;

import java.util.List;
import org.bukkit.util.BoundingBox;

public record HeatContactVolume(double x0, double y0, double z0, double x1, double y1, double z1) {
    public static HeatContactVolume parse(List<?> values) {
        if (values == null || values.isEmpty()) return new HeatContactVolume(0, 0, 0, 1, 1.0 / 16, 1);
        if (values.size() != 4 && values.size() != 6) throw new IllegalArgumentException("burn_area requires four or six pixel coordinates");
        double[] bounds = new double[]{0, 0, 0, 16, 1, 16};
        int[] index = values.size() == 4 ? new int[]{0, 2, 3, 5} : new int[]{0, 1, 2, 3, 4, 5};
        for (int i = 0; i < values.size(); ++i) {
            if (!(values.get(i) instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("burn_area must contain finite numbers");
            bounds[index[i]] = number.doubleValue();
        }
        for (int axis = 0; axis < 3; ++axis) if (bounds[axis] < 0 || bounds[axis + 3] > 16 || bounds[axis] >= bounds[axis + 3]) throw new IllegalArgumentException("burn_area coordinates must have 0 <= min < max <= 16");
        return new HeatContactVolume(bounds[0] / 16, bounds[1] / 16, bounds[2] / 16, bounds[3] / 16, bounds[4] / 16, bounds[5] / 16);
    }
    public boolean touches(BoundingBox entity, int x, int y, int z) {
        return entity.overlaps(new BoundingBox(x + x0, y + 1 + y0, z + z0, x + x1, y + 1 + y1, z + z1));
    }
}
