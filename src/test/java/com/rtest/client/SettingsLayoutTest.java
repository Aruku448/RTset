package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression for footer overlap, off-center lists and fixed 220px rows. GUI-scaled coordinates. */
public final class SettingsLayoutTest {
    public static void main(String[] args) throws Exception {
        String screen=Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingSettingsScreen.java"));
        if(!screen.contains("super(minecraft, width, bottom - top, top, ROW_HEIGHT)")
                || !screen.contains("this.setX(left)") || !screen.contains("return Math.max(1, this.getWidth() - 20)"))
            throw new AssertionError("list call site must use viewport height, row height and actual panel bounds");
        int cases=0;
        for(int categories:new int[]{5,6})for(int w=320;w<=1920;w+=13)for(int h=180;h<=1080;h+=17) {
            var p=SettingsLayout.forScreen(w,h,categories);
            if(p.left()<0 || p.left()+p.width()>w || p.listTop()>=p.listBottom()
                    || p.listBottom()+8>p.footerTop() || p.footerTop()+20>h)
                throw new AssertionError("viewport/footer overlap at "+w+"x"+h);
            int tw=(p.width()-(p.tabColumns()-1)*4)/p.tabColumns();
            for(int i=0;i<categories;i++) {
                int x=p.left()+(i%p.tabColumns())*(tw+4),y=30+(i/p.tabColumns())*24;
                if(x<p.left() || x+tw>p.left()+p.width() || y+20>p.listTop()-20)
                    throw new AssertionError("category overlaps content");
            }
            int content=p.width()-24;
            int control=SettingsLayout.controlWidth(content,p.columns());
            if(control<240 || 4+p.columns()*control+(p.columns()-1)*10>content)
                throw new AssertionError("control text width / adjacent columns overlap");
            if(p.width()>=600 && p.columns()!=2 || p.width()<600 && p.columns()!=1)
                throw new AssertionError("responsive column threshold");
            cases++;
        }
        System.out.println("F9 layout: "+cases+" GUI sizes passed viewport, footer, category and column bounds; not in-game visual QA");
    }
}
