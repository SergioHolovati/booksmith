/**
 * Kernel compartilhado: erros, filtros web e utilitários sem regra de negócio.
 * Módulo aberto: todos os outros módulos podem usar qualquer pacote daqui.
 */
@ApplicationModule(displayName = "Common", type = ApplicationModule.Type.OPEN)
package com.booksmith.common;

import org.springframework.modulith.ApplicationModule;
