package dev.redish;

import com.github.lalyos.jfiglet.FigletFont;

public class Logo {

    private static final String FONT = "/fonts/slant.flf";
    private static final String TEXT = "Redish";

    public static void print() {
        try {
            String art = FigletFont.convertOneLine("classpath:" + FONT, TEXT);
            System.out.println(art);
        } catch (Exception e) {
            System.out.println("Redish");
        }
    }
}
