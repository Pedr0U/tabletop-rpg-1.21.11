package com.pedro.tabletoprpg.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Slider de horário do mundo (0-24000 ticks) usado no menu do mestre.
 *
 * <p>Internamente o {@link AbstractSliderButton} trabalha com um valor
 * normalizado 0.0-1.0; este widget converte entre esse valor e os ticks
 * do ciclo dia/noite do Minecraft (24000 ticks = 24 horas).
 *
 * <p>O rótulo mostra o horário formatado (ex: "06:00") e o callback
 * {@code onApply} (que recebe o horário em ticks) é disparado quando o
 * jogador solta o slider.
 */
public class TimeSlider extends AbstractSliderButton {

    private static final int DAY_LENGTH = 24000;

    private final IntConsumer onApply;

    public TimeSlider(int x, int y, int width, int height, long currentDayTime, IntConsumer onApply) {
        super(x, y, width, height, Component.empty(), normalize(currentDayTime));
        this.onApply = onApply;
        updateMessage();
    }

    /** Converte o horário do mundo (ticks) para o valor normalizado 0.0-1.0. */
    private static double normalize(long dayTime) {
        return Math.floorMod(dayTime, DAY_LENGTH) / (double) DAY_LENGTH;
    }

    /** Horário atual do slider em ticks do Minecraft (0-23999). */
    public int getTimeOfDay() {
        return (int) Math.round(this.value * DAY_LENGTH) % DAY_LENGTH;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal("World Time: " + formatTime(getTimeOfDay())));
    }

    @Override
    protected void applyValue() {
        if (onApply != null) {
            onApply.accept(getTimeOfDay());
        }
    }

    /** Formata ticks do Minecraft como horas:minutos (1000 ticks = 1 hora). */
    private static String formatTime(int ticks) {
        int totalMinutes = (int) Math.round(ticks / 1000.0 * 60.0);
        int hours = (totalMinutes / 60) % 24;
        int minutes = totalMinutes % 60;
        return String.format("%02d:%02d", hours, minutes);
    }
}