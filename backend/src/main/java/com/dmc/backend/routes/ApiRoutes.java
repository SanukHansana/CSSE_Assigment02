package com.dmc.backend.routes;

/** Shared URL paths. Spring MVC registers routes using annotations on controllers. */
public final class ApiRoutes {
    public static final String API = "/api";
    public static final String DMC = API + "/dmc";

    private ApiRoutes() {
    }
}
