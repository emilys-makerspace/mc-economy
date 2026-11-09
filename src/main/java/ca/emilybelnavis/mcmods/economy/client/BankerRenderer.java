package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.banker.entity.BankerCatalog;
import ca.emilybelnavis.mcmods.economy.banker.entity.BankerEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Textured humanoid clothing, close-fitting hair and a compact nose. */
public final class BankerRenderer extends MobRenderer<BankerEntity, HumanoidModel<BankerEntity>> {
  private static final ModelLayerLocation LAYER =
      new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("economy", "banker"), "main");
  private static final ResourceLocation TEXTURE =
      ResourceLocation.fromNamespaceAndPath("economy", "textures/entity/banker.png");

  public BankerRenderer(EntityRendererProvider.Context context) {
    super(context, new HumanoidModel<>(context.bakeLayer(LAYER)), .4F);
  }

  @Override
  public ResourceLocation getTextureLocation(BankerEntity entity) {
    return TEXTURE;
  }

  private static LayerDefinition layer() {
    var mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0);
    var root = mesh.getRoot();
    root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
    root.getChild("head")
        .addOrReplaceChild(
            "hair",
            CubeListBuilder.create()
                .texOffs(32, 0)
                .addBox(-4, -8.1F, -4, 8, 1.1F, 8, new CubeDeformation(.1F)),
            PartPose.ZERO);

    root.getChild("head")
        .addOrReplaceChild(
            "nose",
            CubeListBuilder.create().texOffs(0, 48).addBox(-.75F, -4, -4.75F, 1.5F, 1.5F, .75F),
            PartPose.ZERO);

    // Native skin resolution matches the standard humanoid UV grid.
    return LayerDefinition.create(mesh, 64, 64);
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(
        (EntityRenderersEvent.RegisterLayerDefinitions event) ->
            event.registerLayerDefinition(LAYER, BankerRenderer::layer));
    bus.addListener(
        (EntityRenderersEvent.RegisterRenderers event) ->
            event.registerEntityRenderer(BankerCatalog.BANKER.get(), BankerRenderer::new));
  }
}
