package org.example.xyjaicontext.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

    private final Encoding encoding = Encodings.newLazyEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        return text == null || text.isBlank() ? 0 : encoding.countTokens(text);
    }

    public String slice(String text, int startInclusive, int endExclusive) {
        IntArrayList encoded = encoding.encode(text == null ? "" : text);
        int start = Math.max(0, Math.min(startInclusive, encoded.size()));
        int end = Math.max(start, Math.min(endExclusive, encoded.size()));
        IntArrayList selected = new IntArrayList(end - start);
        for (int i = start; i < end; i++) {
            selected.add(encoded.get(i));
        }
        return encoding.decode(selected);
    }
}
