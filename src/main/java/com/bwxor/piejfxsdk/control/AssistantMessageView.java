package com.bwxor.piejfxsdk.control;

import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows an assistant answer. The markdown is split into text and fenced code blocks:
 * text is rendered as markdown, each code block is shown in a read-only text box.
 * Setting the markdown again (e.g. while streaming) updates the existing nodes where possible.
 */
public class AssistantMessageView extends VBox {
    private static final Pattern OPEN_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern CLOSE_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*$");
    private static final int MAX_VISIBLE_CODE_ROWS = 20;

    private String mdString = "";

    public AssistantMessageView(String md) {
        setSpacing(4);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setMdString(md);
    }

    public String getMdString() {
        return mdString;
    }

    public void setMdString(String md) {
        mdString = md == null ? "" : md;

        List<Segment> segments = split(mdString);
        List<Node> nodes = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            Node existing = i < getChildren().size() ? getChildren().get(i) : null;

            if (segment.code()) {
                CodeTextBox box = existing instanceof CodeTextBox b ? b : new CodeTextBox();
                box.setCode(segment.content());
                nodes.add(box);
            } else {
                StyledMarkdownView view = existing instanceof StyledMarkdownView v ? v : new StyledMarkdownView("");
                if (!segment.content().equals(view.getMdString())) {
                    view.setMdString(segment.content());
                }
                nodes.add(view);
            }
        }

        getChildren().setAll(nodes);
    }

    /** Splits markdown into text segments and fenced code segments, in order. An unclosed fence runs to the end (streaming). */
    static List<Segment> split(String md) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder code = new StringBuilder();
        String openFence = null;

        for (String rawLine : md.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

            if (openFence == null) {
                Matcher open = OPEN_FENCE.matcher(line);
                if (open.matches()) {
                    addText(segments, text);
                    openFence = open.group(1);
                    code.setLength(0);
                } else {
                    text.append(line).append('\n');
                }
            } else {
                Matcher close = CLOSE_FENCE.matcher(line);
                if (close.matches()
                        && close.group(1).charAt(0) == openFence.charAt(0)
                        && close.group(1).length() >= openFence.length()) {
                    segments.add(new Segment(true, withoutTrailingNewline(code)));
                    openFence = null;
                } else {
                    code.append(line).append('\n');
                }
            }
        }

        if (openFence != null) {
            segments.add(new Segment(true, withoutTrailingNewline(code)));
        } else {
            addText(segments, text);
        }
        return segments;
    }

    private static void addText(List<Segment> segments, StringBuilder text) {
        if (!text.toString().isBlank()) {
            segments.add(new Segment(false, text.toString()));
        }
        text.setLength(0);
    }

    private static String withoutTrailingNewline(StringBuilder sb) {
        String s = sb.toString();
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    record Segment(boolean code, String content) {}

    /** Read-only text box for one fenced code block. Long lines wrap, so no horizontal scrollbar. */
    private static class CodeTextBox extends VBox {
        private final TextArea textArea = new TextArea();

        CodeTextBox() {
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.getStyleClass().add("crumb-code-textbox");

            setMinWidth(0);
            setMaxWidth(Double.MAX_VALUE);
            getChildren().add(textArea);
        }

        void setCode(String newCode) {
            if (!newCode.equals(textArea.getText())) {
                textArea.setText(newCode);
            }
            int rows = Math.max(1, Math.min(MAX_VISIBLE_CODE_ROWS, newCode.split("\n", -1).length));
            textArea.setPrefRowCount(rows);
        }
    }
}
