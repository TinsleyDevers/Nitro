package com.tinsl.nitro.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSetupTest {
    @Test
    void changesOnlyInstanceJavaSettingsAndIsIdempotent() throws Exception {
        String before = "[General]\r\nJavaPath=old\r\nJavaPath=duplicate\r\n"
            + "OverrideJavaLocation=false\r\nMaxMemAlloc=12000\r\n\r\n[UI]\r\nJavaPath=ui\r\n";
        String java = "C:/Program Files/Java/bin/javaw.exe";
        String after = JavaSetup.withJava(before, java);
        assertEquals("[General]\r\nJavaPath=" + java + "\r\nJavaPath=" + java
            + "\r\nOverrideJavaLocation=true\r\nMaxMemAlloc=12000\r\n"
            + "AutomaticJava=false\r\nIgnoreJavaCompatibility=true\r\n\r\n[UI]\r\nJavaPath=ui\r\n", after);
        assertEquals(after, JavaSetup.withJava(after, java));
    }
}
