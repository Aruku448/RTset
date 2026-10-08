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
            if(control-24<240)
                throw new AssertionError("reset button must leave 240px for setting text");
            if(p.width()>=600 && p.columns()!=2 || p.width()<600 && p.columns()!=1)
                throw new AssertionError("responsive column threshold");
            cases++;
        }
        checkSliderValues();
        System.out.println("F9 layout: "+cases+" GUI sizes passed viewport, footer, category and column bounds; not in-game visual QA");
    }

    private static void checkSliderValues() {
        double[][] ranges = {
            {1,16,4,1}, {0,4,2,1}, {0.0001,0.2,0.01,0},
            {0.001,1,0.01,0}, {-10,10,0,0}, {1000,40000,6500,0}, {0,0.1,0,0}
        };
        for (double[] range : ranges) {
            int decimals=SettingValueFormat.decimals(range[0],range[1],range[2],range[3]==1);
            for(int i=0;i<=10000;i++) {
                double input=range[0]+(range[1]-range[0])*i/10000.0;
                double stored=SettingValueFormat.snap(input,range[0],range[1],decimals);
                double displayed=Double.parseDouble(SettingValueFormat.format(stored,decimals));
                if(stored!=displayed || stored<range[0] || stored>range[1])
                    throw new AssertionError("slider stores a value different from its label or range");
                if(range[3]==1 && stored!=Math.rint(stored))
                    throw new AssertionError("integer slider must show and store an integer");
            }
            if(SettingValueFormat.snap(range[0],range[0],range[1],decimals)!=range[0]
                    || SettingValueFormat.snap(range[1],range[0],range[1],decimals)!=range[1])
                throw new AssertionError("slider endpoints must remain reachable");
        }
        if(SettingValueFormat.decimals(0.0001,0.2,0.01,false)<4)
            throw new AssertionError("NRD threshold precision cannot hide the nonzero minimum");
        System.out.println("F9 numeric controls: 70007 stored/displayed values and range endpoints passed");
    }
}
