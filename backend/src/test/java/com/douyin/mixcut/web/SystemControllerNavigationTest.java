package com.douyin.mixcut.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemControllerNavigationTest {
    @Test
    void directBrowserNavigationRedirectsToEnvironmentCenter() {
        SystemController controller = new SystemController(null, null, null, null, null,
                null, null, null, null, null, null);

        var response = controller.envPage();

        assertEquals(302, response.getStatusCode().value());
        assertEquals("/#/capabilities?view=environment", response.getHeaders().getLocation().toString());
    }
}
