package ca.emilybelnavis.mcmods.economy.banking;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Enforces existing wire budgets after translation, without splitting deferred arguments or losing terms. */
public record UiPublication(List<String> lines, String status, List<String> labels) {
    public UiPublication { lines=List.copyOf(lines);labels=List.copyOf(labels); }
    public static UiPublication render(List<String> source, String status, List<String> labels, String language, int maximumLines) {
        var rendered = new ArrayList<String>();
        for (var line : source) split(rendered, UiText.render(line, language));
        String resultStatus = UiText.render(status, language);
        if (resultStatus.length() > 256) {
            split(rendered, resultStatus);
            resultStatus = UiText.render(UiText.t("ui.common.read_complete_terms"), language);
        }
        var renderedLabels = labels.stream().map(label -> UiText.render(label, language)).toList();
        int bytes = resultStatus.getBytes(StandardCharsets.UTF_8).length;
        for (var line : rendered) bytes += line.getBytes(StandardCharsets.UTF_8).length;
        for (var label : renderedLabels) {
            if (label.length() > 128) throw new IllegalArgumentException("UI_CHOICE_RENDER_BOUNDS");
            bytes += label.getBytes(StandardCharsets.UTF_8).length;
        }
        if (rendered.size() > maximumLines || bytes > 12_000) throw new IllegalArgumentException("UI_RENDER_BOUNDS");
        return new UiPublication(rendered, resultStatus, renderedLabels);
    }
    private static void split(List<String> out, String line) {
        if (line.isEmpty()) { out.add(line); return; }
        for (int start=0;start<line.length();) {
            int end=Math.min(start+240,line.length());
            if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1)))end--;
            out.add(line.substring(start,end));start=end;
        }
    }
}
