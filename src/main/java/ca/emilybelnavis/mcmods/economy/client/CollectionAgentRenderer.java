package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.collection.*;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Binds the original A6-05 civilian geometry and skin to the collection presentation. */
public final class CollectionAgentRenderer
    extends MobRenderer<CollectionAgent, HumanoidModel<CollectionAgent>> {
  private static final ModelLayerLocation LAYER =
      new ModelLayerLocation(
          ResourceLocation.fromNamespaceAndPath("economy", "collection_agent"), "main");
  private static final ResourceLocation TEXTURE =
      ResourceLocation.fromNamespaceAndPath(
          "economy", "textures/entity/banking/collection_agent.png");

  public CollectionAgentRenderer(EntityRendererProvider.Context context) {
    super(context, new HumanoidModel<>(context.bakeLayer(LAYER)), 0.4F);
  }

  @Override
  public ResourceLocation getTextureLocation(CollectionAgent agent) {
    return TEXTURE;
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(
        (EntityRenderersEvent.RegisterLayerDefinitions event) ->
            event.registerLayerDefinition(LAYER, CollectionAgentGeometry::create));
    bus.addListener(
        (EntityRenderersEvent.RegisterRenderers event) ->
            event.registerEntityRenderer(
                CollectionCatalog.AGENT.get(), CollectionAgentRenderer::new));
  }
}
