package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import com.minion.gui.chat.MarkdownRenderer.Span;
import com.minion.gui.chat.MarkdownRenderer.TableRowData;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** Block 结构 → JavaFX 节点（样式类与 theme.css 对应） */
public class BlockNodeFactory {

    public static javafx.scene.Node create(Block b) {
        switch (b.type) {
            case HEADING: {
                Label l = new Label(b.text);
                l.setStyle("-fx-font-size: " + Math.max(13, 17 - b.level)
                        + "px; -fx-font-weight: bold; -fx-text-fill: #e6e8ee;");
                return l;
            }
            case CODE: {
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("code-block");
                return l;
            }
            case PARAGRAPH: {
                TextFlow flow = new TextFlow();
                if (b.spans.isEmpty()) flow.getChildren().add(new Text(b.text == null ? "" : b.text));
                for (Span s : b.spans) flow.getChildren().add(spanText(s));
                flow.setPadding(new Insets(2, 0, 2, 0));
                return flow;
            }
            case LIST: {
                VBox box = new VBox(2);
                for (Block item : b.items) {
                    HBox row = new HBox(6);
                    Label bullet = new Label("•");
                    bullet.getStyleClass().add("msg-thinking");
                    TextFlow flow = new TextFlow();
                    for (Span s : item.spans) flow.getChildren().add(spanText(s));
                    row.getChildren().addAll(bullet, flow);
                    box.getChildren().add(row);
                }
                return box;
            }
            case QUOTE: {
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-thinking");
                l.setStyle("-fx-border-color: #4f8cff; -fx-border-width: 0 0 0 3; -fx-padding: 4 8 4 8;");
                return l;
            }
            case TABLE: {
                GridPane grid = new GridPane();
                grid.setHgap(16);
                grid.setVgap(4);
                grid.getStyleClass().add("code-block");
                int rowIdx = 0;
                for (TableRowData r : b.rows) {
                    for (int c = 0; c < r.cells.size(); c++) {
                        Text t = new Text(r.cells.get(c));
                        if (r.header) {
                            t.setFont(Font.font(t.getFont().getFamily(), FontWeight.BOLD, t.getFont().getSize()));
                        }
                        grid.add(t, c, rowIdx);
                    }
                    rowIdx++;
                }
                return grid;
            }
            default:
                return new Label(b.text == null ? "" : b.text);
        }
    }

    private static Text spanText(Span s) {
        Text t = new Text(s.text);
        if (s.style.contains("bold")) {
            t.setFont(Font.font(t.getFont().getFamily(), FontWeight.BOLD, t.getFont().getSize()));
        }
        if (s.style.contains("italic")) {
            t.setFont(Font.font(t.getFont().getFamily(), FontPosture.ITALIC, t.getFont().getSize()));
        }
        if (s.style.contains("strike")) t.setStrikethrough(true);
        if (s.style.contains("code")) t.setStyle("-fx-font-family: Consolas; -fx-fill: #79c0ff;");
        return t;
    }
}
