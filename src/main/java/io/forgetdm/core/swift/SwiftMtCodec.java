package io.forgetdm.core.swift;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SWIFT MT (FIN) message codec (RFP §3.3 SWIFT/ISO preservation).
 *
 * <p>A FIN message is a sequence of blocks {@code {1:...}{2:...}{3:...}{4:...-}{5:...}}. Block 4 is
 * the text block: newline-separated tagged fields {@code :NN[a]:value} (values may span lines, e.g.
 * a party's name/address). This codec parses that structure and, critically, repacks it with the
 * <em>exact same blocks, tags and layout</em> — masking only sensitive token values in place, so the
 * message still parses and every field keeps its required format.
 *
 * <p>Field masking is delegated to a {@link FieldMasker} (the ForgeTDM masking engine), so BICs stay
 * valid BICs, accounts keep their length, names stay names, and everything is deterministic (same
 * input + salt → same output → referential integrity across messages).
 */
public final class SwiftMtCodec {

    /** Callback into the masking engine — each method masks one classified token type. */
    public interface FieldMasker {
        String bic(String salt, String value);
        String account(String salt, String value);
        String name(String salt, String value);
        String reference(String salt, String value);
    }

    public static final class Block {
        public final String id;      // "1".."5"
        public String content;       // raw content between the id-colon and the block terminator
        public final boolean isText; // block 4 (terminator "-}")
        Block(String id, String content, boolean isText) { this.id = id; this.content = content; this.isText = isText; }
    }

    // Tag at the start of a line in block 4: colon, 2 digits, optional letter, colon.
    private static final Pattern TAG = Pattern.compile("(?m)^:(\\d{2}[A-Z]?):");
    // A BIC: 4 letters (bank) + 2 letters (country) + 2 alnum (location) + optional 3 alnum (branch).
    private static final Pattern BIC = Pattern.compile("\\b[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b");
    // Party fields whose text lines carry a name/address (option without a leading BIC line).
    private static final java.util.Set<String> PARTY_TAGS = java.util.Set.of(
            "50", "50A", "50F", "50K", "59", "59A", "59F",
            "52D", "53D", "54D", "55D", "56D", "57D", "58D");
    // Reference fields.
    private static final java.util.Set<String> REF_TAGS = java.util.Set.of("20", "21", "108", "121");

    private SwiftMtCodec() {}

    /** True if the value looks like a FIN message (has at least block 1 and a text block). */
    public static boolean isSwiftMt(String s) {
        if (s == null) return false;
        return s.contains("{1:") && s.contains("{4:") && s.contains("-}");
    }

    /** Parse blocks preserving order; block 4 ends at "-}", others at the matching brace. */
    public static List<Block> parse(String raw) {
        List<Block> blocks = new ArrayList<>();
        int i = 0, n = raw.length();
        while (i < n) {
            if (raw.charAt(i) != '{') { i++; continue; }
            int colon = raw.indexOf(':', i);
            if (colon < 0) break;
            String id = raw.substring(i + 1, colon);
            if ("4".equals(id)) {
                int end = raw.indexOf("-}", colon);
                if (end < 0) end = n;
                blocks.add(new Block(id, raw.substring(colon + 1, end), true));
                i = end + 2;
            } else {
                int depth = 1, j = colon + 1;
                while (j < n && depth > 0) {
                    char c = raw.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    if (depth == 0) break;
                    j++;
                }
                blocks.add(new Block(id, raw.substring(colon + 1, j), false));
                i = j + 1;
            }
        }
        return blocks;
    }

    /** Reassemble a parsed message exactly. */
    public static String format(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            sb.append('{').append(b.id).append(':').append(b.content);
            sb.append(b.isText ? "-}" : "}");
        }
        return sb.toString();
    }

    /** Mask a whole message in place, preserving structure. */
    public static String mask(String raw, String salt, FieldMasker masker) {
        List<Block> blocks = parse(raw);
        for (Block b : blocks) {
            switch (b.id) {
                case "1", "2" -> b.content = maskHeader(b.id, b.content, salt + ":hdr", masker);
                case "4" -> b.content = maskTextBlock(b.content, salt, masker);
                default -> { /* block 3/5: leave control/trailer structure intact */ }
            }
        }
        return format(blocks);
    }

    // ---- block 4 -----------------------------------------------------------

    private static String maskTextBlock(String content, String salt, FieldMasker masker) {
        Matcher m = TAG.matcher(content);
        List<int[]> starts = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        while (m.find()) { starts.add(new int[]{m.start(), m.end()}); tags.add(m.group(1)); }
        if (starts.isEmpty()) return content;

        StringBuilder out = new StringBuilder(content.substring(0, starts.get(0)[0]));
        for (int k = 0; k < starts.size(); k++) {
            int valStart = starts.get(k)[1];
            int valEnd = (k + 1 < starts.size()) ? starts.get(k + 1)[0] : content.length();
            String tag = tags.get(k);
            String value = content.substring(valStart, valEnd);
            out.append(content, starts.get(k)[0], valStart);         // ":tag:" verbatim
            out.append(maskFieldValue(tag, value, salt + ":" + tag, masker));
        }
        return out.toString();
    }

    /** Mask the sensitive tokens inside one field's value, keeping line layout. */
    private static String maskFieldValue(String tag, String value, String salt, FieldMasker masker) {
        if (REF_TAGS.contains(tag)) {
            // Preserve any trailing newline; mask only the reference text of the first line.
            String eol = value.endsWith("\r\n") ? "\r\n" : value.endsWith("\n") ? "\n" : "";
            String core = value.substring(0, value.length() - eol.length());
            return masker.reference(salt, core) + eol;
        }
        String[] lines = value.split("(?<=\n)"); // keep the newline on each piece
        StringBuilder sb = new StringBuilder();
        boolean partyNameTaken = false;
        for (String piece : lines) {
            String eol = piece.endsWith("\r\n") ? "\r\n" : piece.endsWith("\n") ? "\n" : "";
            String line = piece.substring(0, piece.length() - eol.length());
            String masked;
            if (line.startsWith("/") && line.length() > 1) {
                masked = "/" + masker.account(salt, line.substring(1));
            } else if (BIC.matcher(line).matches()) {
                masked = masker.bic(salt, line);
            } else if (PARTY_TAGS.contains(tag) && !line.isBlank() && !partyNameTaken) {
                masked = masker.name(salt, line);   // first non-account line = the party name
                partyNameTaken = true;
            } else {
                masked = line;                       // amounts, dates, currencies, codes: untouched
            }
            sb.append(masked).append(eol);
        }
        return sb.toString();
    }

    // ---- blocks 1 & 2 (contiguous headers → structural extraction) ---------

    /**
     * Block 1 basic header: {@code <F/A/L><2 service digits><12-char LT address><10-char session/seq>}.
     * Block 2 input app header: {@code I<3-digit MT><12-char receiver LT address><priority...>}.
     * The 12-char LT address is BIC8 + LT code(1) + branch(3); we mask the BIC8, keep the rest.
     */
    private static String maskHeader(String id, String content, String salt, FieldMasker masker) {
        if ("1".equals(id) && content.length() >= 15 && Character.isLetter(content.charAt(0))) {
            return content.substring(0, 3) + maskAddress(content.substring(3, 15), salt, masker)
                    + content.substring(15);
        }
        if ("2".equals(id) && content.startsWith("I") && content.length() >= 16) {
            return content.substring(0, 4) + maskAddress(content.substring(4, 16), salt, masker)
                    + content.substring(16);
        }
        return content;  // output headers / unexpected shapes: leave intact
    }

    /** Mask the BIC8 of a 12-char logical-terminal address, preserving its length and LT/branch tail. */
    private static String maskAddress(String addr, String salt, FieldMasker masker) {
        if (addr.length() < 8) return addr;
        String bic8 = addr.substring(0, 8);
        String masked = masker.bic(salt, bic8);
        if (masked == null || masked.length() != 8) return addr;  // length-safety: never resize the header
        return masked + addr.substring(8);
    }
}
