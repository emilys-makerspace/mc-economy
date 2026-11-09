package ca.emilybelnavis.mcmods.economy.client;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/** Original Waystone civilian mesh. A6-11 owns its renderer, entity and all behavior. */
public final class CollectionAgentGeometry {
  public static final int TEXTURE_WIDTH = 64, TEXTURE_HEIGHT = 64;

  private CollectionAgentGeometry() {}

  public static LayerDefinition create() {
    var mesh = new MeshDefinition();
    PartDefinition root = mesh.getRoot();
    root.addOrReplaceChild(
        "head",
        CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
        PartPose.offset(0.0F, 0.0F, 0.0F));
    root.addOrReplaceChild(
        "hat",
        CubeListBuilder.create()
            .texOffs(32, 0)
            .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.25F)),
        PartPose.offset(0.0F, 0.0F, 0.0F));
    PartDefinition body =
        root.addOrReplaceChild(
            "body",
            CubeListBuilder.create().texOffs(16, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F),
            PartPose.offset(0.0F, 0.0F, 0.0F));
    root.addOrReplaceChild(
        "right_arm",
        CubeListBuilder.create().texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
        PartPose.offset(-5.0F, 2.0F, 0.0F));
    root.addOrReplaceChild(
        "left_arm",
        CubeListBuilder.create().texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
        PartPose.offset(5.0F, 2.0F, 0.0F));
    root.addOrReplaceChild(
        "right_leg",
        CubeListBuilder.create().texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
        PartPose.offset(-1.9F, 12.0F, 0.0F));
    root.addOrReplaceChild(
        "left_leg",
        CubeListBuilder.create().texOffs(16, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
        PartPose.offset(1.9F, 12.0F, 0.0F));
    body.addOrReplaceChild(
        "satchel",
        CubeListBuilder.create().texOffs(48, 34).addBox(3.0F, 7.0F, 1.0F, 4.0F, 5.0F, 2.0F),
        PartPose.offset(0.0F, 0.0F, 0.0F));
    body.addOrReplaceChild(
        "folio",
        CubeListBuilder.create().texOffs(32, 32).addBox(-3.0F, 4.0F, -3.0F, 6.0F, 5.0F, 1.0F),
        PartPose.offset(0.0F, 0.0F, 0.0F));
    return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
  }
}
