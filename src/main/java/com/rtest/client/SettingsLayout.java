package com.rtest.client;

/** GUI coordinates, already scaled by Minecraft. Keep footer out of the scroll viewport. */
record SettingsLayout(int left, int width, int tabColumns, int columns, int listTop, int listBottom, int footerTop) {
    static int controlWidth(int contentWidth, int columns) {
        return Math.max(1, (contentWidth - 4 - 10 * (columns - 1)) / columns);
    }
    static SettingsLayout forScreen(int screenWidth, int screenHeight) {
        return forScreen(screenWidth,screenHeight,5);
    }
    static SettingsLayout forScreen(int screenWidth,int screenHeight,int categories) {
        int width = Math.max(1, Math.min(1000, screenWidth - 24));
        int tabs = width >= categories*100 ? categories : 3;
        int footer = screenHeight - 28;
        int top = 30 + ((categories + tabs - 1) / tabs) * 24 + 20;
        return new SettingsLayout((screenWidth - width) / 2, width, tabs, width >= 600 ? 2 : 1,
            top, Math.max(top + 1, footer - 8), footer);
    }
}
