package com.xlrw.gui;

//? if >=1.21.11 {
/*import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;

/^*
 * 1.21.11: the deck's 3D schematic preview. GUIs there can not draw custom 3D directly, so it is rendered into its own
 * texture by a picture-in-picture renderer (registered in XlrwClient) and blitted into the panel.
 ^/
public final class HoloPip {
    private HoloPip() {}

    record State(HoloPreview holo, int layerY, long now, int x0, int y0, int x1, int y1,
                 ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
        State(HoloPreview holo, int layerY, long now, int x0, int y0, int x1, int y1) {
            this(holo, layerY, now, x0, y0, x1, y1, null, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, null));
        }

        @Override
        public float scale() {
            return 1f;
        }
    }

    public static final class Renderer extends PictureInPictureRenderer<State> {
        public Renderer(MultiBufferSource.BufferSource buffers) {
            super(buffers);
        }

        @Override
        public Class<State> getRenderStateClass() {
            return State.class;
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2f; // origin in the middle of the box
        }

        @Override
        protected void renderToTexture(State s, com.mojang.blaze3d.vertex.PoseStack ps) {
            s.holo().draw3D(ps, bufferSource, s.x1() - s.x0(), s.y1() - s.y0(), s.layerY(), s.now());
        }

        @Override
        protected String getTextureLabel() {
            return "xlrw holo preview";
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry.register(ctx -> new Renderer(ctx.vertexConsumers()));
    }
}
*///?}
