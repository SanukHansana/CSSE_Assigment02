package com.dmc.backend.controller;

import com.dmc.backend.routes.ApiRoutes;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DmcController {

    /** Reports application availability; this does not check MongoDB connectivity. */
    @GetMapping(ApiRoutes.DMC)
    public Map<String, String> status() {
        return Map.of("status", "UP", "application", "dmc-backend");
    }
}
