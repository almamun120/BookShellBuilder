package com.bookshell.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** All settings that go into one generated book app. */
final class BuildSpec {
    String appName;
    String pkg;
    String versionName = "1.0";
    int versionCode = 1;

    String iconSource = "cover";    // custom | cover | letter
    File iconFile;
    String splashSource = "page";   // custom | page | none
    File splashFile;
    String splashMode = "fullFit";  // logo | fullFit | fullCrop | fullStretch
    int splashSize = 180;
    int splashBg = 0xFFFFFFFF;
    int splashMs = 1800;
    boolean splashName = true;
    boolean splashSpinner = true;

    int accent = 0xFF3B82F6;
    int readerBg = 0xFF1F2023;
    int paper = 0xFFFBF8F1;
    int ink = 0xFF1B1B1B;
    String orientation = "auto";
    boolean fullscreen;
    boolean keepScreenOn = true;
    boolean scrollBar = true;
    boolean alwaysLibrary;
    boolean volumeKeys = true;
    int textPercent = 100;
    int flipMs = 450;

    String aboutTitle = "";
    String aboutText = "";
    String aboutDeveloper = "";
    final List<String[]> aboutLinks = new ArrayList<>();

    final List<BookSpec> books = new ArrayList<>();
}
