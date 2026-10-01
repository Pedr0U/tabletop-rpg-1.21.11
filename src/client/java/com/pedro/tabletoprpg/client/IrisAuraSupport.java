package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.rendertype.BlockLockAuraRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Integracao opcional com o Iris (shaders) para a aura do Mestre.
 *
 * <p>Por que isto e necessario: a aura usa um {@link RenderPipeline} proprio para
 * atravessar parede (profundidade desligada vive no pipeline, nao no RenderSystem, nesta
 * versao). Sem shader esse pipeline funciona. Com o Iris ligado, porem, o Iris intercepta a
 * ligacao do shader e so tem programa para pipelines que ele conhece; o nosso nao esta na
 * lista, entao o log recebe `Missing program ... in override list` e o shader fica sem nada
 * para ligar — a aura simplesmente nao aparece, sem erro de GL.
 *
 * <p>O proprio Iris oferece `IrisPipelines.copyPipeline(de, para)`: copia a atribuicao de
 * shader de um pipeline para outro. Damos a ele o pipeline de linhas do jogo
 * ({@code LINES_TRANSLUCENT}), que usa exatamente o mesmo vertex format e blend do nosso,
 * para que o nosso seja desenhado com o shader de linhas do Iris — e continua atravessando
 * parede, porque o teste de profundidade continua sendo o do NOSSO pipeline (o shader e a
 * programa; a profundidade e o pipeline).
 *
 * <p>Chamada por reflexao de proposito: o Iris e opcional e nao pode virar dependencia de
 * compilacao, senao o mod deixa de carregar em quem nao o tem.
 *
 * <p>Este metodo roda no caminho de desenho da aura (so quando o Mestre segura o item com
 * trancas visiveis), e nao num evento de carga do shaderpack. Isso e intencional: o mapa de
 * shaders do Iris e reconstruido a cada carga/re-troca de shaderpack, e o
 * {@code copyPipeline} e idempotente e barato (duas operacoes de mapa). Reaplicar aqui
 * garante que a atribuicao exista sempre que a aura for desenhada, sem depender de hook de
 * recarga e sem custo quando ninguem esta olhando a aura.
 */
public final class IrisAuraSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("TabletopRPG-BlockLockAura");

    private static Boolean irisLoaded;
    private static Method copyPipeline;
    private static boolean warnedMissing;

    private IrisAuraSupport() {
    }

    /** Verdadeiro somente quando o mod Iris esta presente (nao quando ha shader ligado). */
    public static boolean isIrisPresent() {
        if (irisLoaded == null) {
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        }
        return irisLoaded;
    }

    /**
     * Diz ao Iris qual shader usar para o pipeline da aura, se o Iris estiver presente.
     * Silencioso e inofensivo quando o Iris nao esta instalado.
     */
    public static void ensurePipelineRegistered() {
        if (!isIrisPresent()) {
            return;
        }

        if (copyPipeline == null) {
            try {
                Class<?> irisPipelines = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
                copyPipeline = irisPipelines.getMethod(
                        "copyPipeline", RenderPipeline.class, RenderPipeline.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    LOGGER.warn("[Aura] Iris presente mas IrisPipelines.copyPipeline nao encontrado; "
                            + "a aura pode nao aparecer com shaders.", e);
                }
                return;
            }
        }

        try {
            // Origem: o pipeline de linhas do jogo (o Iris ja o conhece). Destino: o nosso.
            copyPipeline.invoke(null,
                    RenderTypes.LINES_TRANSLUCENT.pipeline(),
                    BlockLockAuraRenderType.pipelineThroughWalls());
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOGGER.warn("[Aura] Falha ao registrar o pipeline da aura no Iris.", e);
            }
        }
    }
}