package com.rtest.client;

/** Contract test for fixed placeholder topology and material stride. */
public final class DynamicPlaceholderGeometryTest {
    public static void main(String[] args) {
        var player = DynamicPlaceholderGeometry.box(0.25F, 0.6F, 1.0F, false);
        var item = DynamicPlaceholderGeometry.box(1.0F, 0.35F, 0.05F, true);
        if (player.triangleCount() != 12 || item.triangleCount() != 12) {
            throw new AssertionError("placeholder topology must remain a 12-triangle box");
        }
        if (player.materialData().length != 12 * 28 || item.materialData().length != 12 * 28) {
            throw new AssertionError("placeholder material stride is not 28 floats per triangle");
        }
        if (player.materialData()[15] != 0.0F || item.materialData()[15] != 0.0F) {
            throw new AssertionError("placeholder must use its fixed tint without atlas sampling");
        }
    }
}
