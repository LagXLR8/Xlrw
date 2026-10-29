package com.nnpg.alice.premium;

import java.util.List;
import java.util.Map;

final class NameProtectLogic {
    private NameProtectLogic() {
    }

    static String protect(String text, List<Map.Entry<String, String>> replacements) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) return text;

        String protectedText = text;
        for (int i = 0; i < replacements.size(); i++) {
            protectedText = replaceIgnoreCase(
                protectedText,
                replacements.get(i).getKey(),
                placeholder(i)
            );
        }
        for (int i = 0; i < replacements.size(); i++) {
            protectedText = protectedText.replace(
                placeholder(i),
                replacements.get(i).getValue()
            );
        }
        return protectedText;
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        if (target.isEmpty() || target.length() > text.length()) return text;

        StringBuilder result = null;
        int copiedUntil = 0;
        int index = 0;

        while (index <= text.length() - target.length()) {
            if (!text.regionMatches(true, index, target, 0, target.length())) {
                index++;
                continue;
            }

            if (result == null) result = new StringBuilder(text.length());
            result.append(text, copiedUntil, index);
            result.append(replacement);
            index += target.length();
            copiedUntil = index;
        }

        if (result == null) return text;
        return result.append(text, copiedUntil, text.length()).toString();
    }

    private static String placeholder(int index) {
        return new String(new char[] {
            (char) 0,
            (char) 0xE000,
            (char) (index >>> 16),
            (char) index,
            (char) 0xE001,
            (char) 0
        });
    }
}
