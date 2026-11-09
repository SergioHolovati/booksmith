package com.booksmith;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Quebra o build se um módulo acessar o interior de outro ou criar dependência não declarada (ADR-001). */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BooksmithApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }
}
