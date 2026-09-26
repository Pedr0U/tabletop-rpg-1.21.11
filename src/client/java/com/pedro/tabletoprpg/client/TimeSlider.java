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
 * <p>O rótulo mostra o horário formatado (ex: "06:00" para o tick 0) e o
 * callback {@code onApply} (que recebe o horário em ticks) é disparado a
 * <b>cada mudança de valor</b>, inclusive durante o arraste — o
 * {@code AbstractSliderButton} do 1.21.11 chama {@code setValue()} de dentro de
 * {@code onDrag()}, e {@code setValue()} chama {@code applyValue()}. Ou seja,
 * o mundo ja acompanha o dedo do mestre; nao ha throttle nem "aplicar ao
 * soltar".
 */
public class TimeSlider extends AbstractSliderButton {

    private static final int DAY_LENGTH = 24000;

    /**
     * {@code dayTime = 0} corresponde a 06:00 no Minecraft, nao a 00:00.
     * Ver {@link #formatTime(int)}.
     */
    private static final int TIME_OFFSET_HOURS = 6;

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

    /**
     * Formata ticks do Minecraft como horas:minutos (1000 ticks = 1 hora).
     *
     * <p><b>Deslocamento de +6 horas (bug corrigido em 25/09/2026).</b> No
     * Minecraft, {@code dayTime = 0} <b>nao</b> e meia-noite: o ciclo do dia
     * comeca as 06:00. Sem o deslocamento, o meio-dia (6000 ticks) aparecia
     * como "00:00" -- que e' justamente a hora oposta, e por isso o slider
     * parecia invertido. O mapa real e:
     * <pre>
     *     0     -&gt; 06:00  (amanhecer)
     *     6000  -&gt; 12:00  (meio-dia, sol no zenith)
     *     12000 -&gt; 18:00  (por do sol)
     *     18000 -&gt; 00:00  (meia-noite)
     * </pre>
     *
     * <p>O {@code % 24} mantem o resultado em 0-23 depois do deslocamento.
     */
    private static String formatTime(int ticks) {
        int totalMinutes = (int) Math.round(ticks / 1000.0 * 60.0);
        int hours = (totalMinutes / 60 + TIME_OFFSET_HOURS) % 24;
        int minutes = totalMinutes % 60;
        return String.format("%02d:%02d", hours, minutes);
    }
}