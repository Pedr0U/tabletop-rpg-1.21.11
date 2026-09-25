package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Slider genérico de valor inteiro (min..max) com rótulo customizado.
 *
 * <p>Internamente o {@link AbstractSliderButton} trabalha com um valor
 * normalizado 0.0-1.0; este widget converte entre esse valor e o intervalo
 * inteiro configurado. O rótulo é gerado pelo {@code labelFormatter} (ex.:
 * "Zoom TopDown: +50") e o callback {@code onApply} (que recebe o valor
 * inteiro) é disparado quando o jogador solta o slider.
 */
public class RpgSlider extends AbstractSliderButton {

    private final int minValue;
    private final int maxValue;
    private final IntConsumer onApply;
    private final IntFunction<String> labelFormatter;

    public RpgSlider(int x, int y, int width, int height, int minValue, int maxValue, int currentValue,
                     IntConsumer onApply, IntFunction<String> labelFormatter) {
        super(x, y, width, height, Component.empty(),
                maxValue > minValue ? (currentValue - minValue) / (double) (maxValue - minValue) : 0.0);
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.onApply = onApply;
        this.labelFormatter = labelFormatter;
        updateMessage();
    }

    /** Valor inteiro atual do slider (min..max). */
    public int getValue() {
        return minValue + (int) Math.round(this.value * (maxValue - minValue));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(labelFormatter.apply(getValue())));
    }

    @Override
    protected void applyValue() {
        if (onApply != null) {
            onApply.accept(getValue());
        }
    }
}