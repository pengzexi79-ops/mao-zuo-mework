package com.douyin.mixcut.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropsVersionTest {

    @Test
    void managedMediaDirectoriesFollowConfiguredDataDirectory() throws Exception {
        Path dataDir = Files.createTempDirectory("mixcut-app-props-");
        AppProps props = new AppProps();
        props.setDataDir(dataDir.toString());
        props.setMaterialsDir("./data/materials");
        props.setOutputDir("./data/output");
        props.setCacheDir("./data/cache");

        assertEquals(dataDir.resolve("materials").toAbsolutePath().normalize(), props.materials());
        assertEquals(dataDir.resolve("output").toAbsolutePath().normalize(), props.output());
        assertEquals(dataDir.resolve("cache").toAbsolutePath().normalize(), props.cache());
        assertEquals(dataDir.resolve("materials/_downloads").toAbsolutePath().normalize(), props.downloads());
    }

    @Test
    void releaseVersionIsNotConfigurationBindable() {
        AppProps props = new AppProps();

        assertTrue(props.releaseVersion().matches("\\d+\\.\\d+\\.\\d+"));
        assertFalse(Arrays.stream(AppProps.class.getMethods())
                .anyMatch(method -> method.getName().equals("getVersion") || method.getName().equals("setVersion")));
    }
}
