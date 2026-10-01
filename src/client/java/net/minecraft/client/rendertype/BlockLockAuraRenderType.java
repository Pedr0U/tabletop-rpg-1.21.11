package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.resources.Identifier;

/**
 * RenderType de linhas sem teste de profundidade.
 *
 * <p>Existe por um motivo especifico: em 1.21.11 o estado de profundidade passou a
 * morar no {@link RenderPipeline}, e nao no RenderSystem. {@code
 * RenderSystem.disableDepthTest()} nao sobrevive ao bind do pipeline no momento do
 * draw, entao nenhum evento do Fabric entrega um RenderType que atravesse parede.
 *
 * <p>Este arquivo vive no pacote do Mojang de proposito. O factory
 * {@link RenderType#create(String, RenderSetup)} e package-private, e essa e a
 * unica forma de alcancar um RenderType proprio sem mixin.
 *
 * <p>Instead of writing shader names by hand, every field is copied from the
 * LINES_TRANSLUCENT pipeline and only the depth test is changed. Shader identifiers
 * are string literals: guessing one wrong kills the game when the pipeline is
 * created, which a build cannot catch.
 */
public final class BlockLockAuraRenderType {
    private static RenderType linesThroughWalls;

    private BlockLockAuraRenderType() {
    }

    public static RenderType linesThroughWalls() {
        if (linesThroughWalls != null) {
            return linesThroughWalls;
        }

        RenderPipeline source = RenderTypes.LINES_TRANSLUCENT.pipeline();
        RenderPipeline.Builder builder = RenderPipeline.builder();

        builder.withLocation(Identifier.fromNamespaceAndPath("tabletop-rpg", "block_lock_aura_lines"));
        builder.withVertexShader(source.getVertexShader());
        builder.withFragmentShader(source.getFragmentShader());
        builder.withVertexFormat(source.getVertexFormat(), source.getVertexFormatMode());

        for (String sampler : source.getSamplers()) {
            builder.withSampler(sampler);
        }

        for (RenderPipeline.UniformDescription uniform : source.getUniforms()) {
            // Texture-backed uniforms would need the TextureFormat overload; a line
            // pipeline has no sampled textures, so a null type means there is
            // nothing to reproduce here.
            if (uniform.type() != null) {
                builder.withUniform(uniform.name(), uniform.type());
            }
        }

        if (source.getBlendFunction().isPresent()) {
            builder.withBlend(source.getBlendFunction().get());
        }

        // The only deliberate difference: draw through walls.
        builder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        builder.withDepthWrite(false);

        builder.withCull(source.isCull());
        builder.withColorLogic(source.getColorLogic());
        builder.withPolygonMode(source.getPolygonMode());
        builder.withDepthBias(source.getDepthBiasScaleFactor(), source.getDepthBiasConstant());
        builder.withColorWrite(source.isWriteColor(), source.isWriteAlpha());

        RenderPipeline pipeline = builder.build();
        RenderSetup setup = RenderSetup.builder(pipeline).createRenderSetup();
        linesThroughWalls = RenderType.create("tabletop-rpg:block_lock_aura_lines", setup);
        return linesThroughWalls;
    }
}